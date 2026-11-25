package com.safespace.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local VPN that never leaves the phone. It only captures DNS lookups:
 * blocked domains get an NXDOMAIN reply ("site can't be reached"),
 * everything else is forwarded to a family-safe upstream resolver.
 */
class FilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.safespace.guard.START"
        const val ACTION_STOP = "com.safespace.guard.STOP"

        @Volatile
        var isRunning = false

        private const val CHANNEL_ID = "protection"
        private const val NOTIF_ID = 1

        private const val TUN_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"

        // Cloudflare for Families: a second layer that filters adult content at the resolver.
        private val UPSTREAM_DNS = listOf("1.1.1.3", "1.0.0.3")

        // Public resolvers used to bypass filters. Routed into the tunnel and dropped in strict mode.
        private val BYPASS_RESOLVERS = listOf(
            "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
            "185.228.168.9", "185.228.169.9"
        )

        // Hostnames of DNS-over-HTTPS providers. Failing the lookup makes browsers fall back to our DNS.
        private val DOH_HOSTS = setOf(
            "dns.google", "cloudflare-dns.com", "one.one.one.one", "dns.quad9.net",
            "doh.opendns.com", "dns.adguard.com", "dns.adguard-dns.com", "doh.dns.sb",
            "dns.nextdns.io", "doh.cleanbrowsing.org", "use-application-dns.net"
        )

        private val safeSearchMap = mapOf(
            "google.com" to "216.239.38.120",
            "www.google.com" to "216.239.38.120",
            "bing.com" to "204.79.197.200",
            "www.bing.com" to "204.79.197.200",
            "th.bing.com" to "204.79.197.200",
            "bing.net" to "204.79.197.200",
            "youtube.com" to "216.239.38.119",
            "www.youtube.com" to "216.239.38.119",
            "m.youtube.com" to "216.239.38.119",
            "youtu.be" to "216.239.38.119"
        )
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    private lateinit var blocklist: Blocklist
    private val lastBlocked = HashMap<String, Long>()
    private var lastNotify = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFilter()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!isRunning) startFilter()
        return START_STICKY
    }

    private fun startFilter() {
        blocklist = Blocklist(this)
        val strict = Store.isStrict(this)

        val builder = Builder()
            .setSession("SafeSpace Guard")
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(FAKE_DNS)
            .addRoute(FAKE_DNS, 32)
            .setBlocking(true)
        if (strict) BYPASS_RESOLVERS.forEach { builder.addRoute(it, 32) }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            stopFilter()
            return
        }

        tun = fd
        isRunning = true
        Store.setProtectionEnabled(this, true)
        pool = Executors.newFixedThreadPool(8)
        Thread({ runLoop(fd, strict) }, "safespace-dns").start()
    }

    private fun runLoop(fd: ParcelFileDescriptor, strict: Boolean) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (isRunning) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val packet = buf.copyOf(n)
                // Anything that is not UDP/53 (e.g. blackholed public resolvers) is simply dropped.
                val parsed = Ipv4Udp.parse(packet, n) ?: continue
                if (parsed.dstPort != 53) continue
                pool?.execute { handleDns(packet, parsed, output, strict) }
            }
        } catch (e: Exception) {
            // Tunnel closed.
        } finally {
            if (isRunning) stopFilter()
        }
    }

    private fun handleDns(
        packet: ByteArray,
        p: Ipv4Udp.Parsed,
        out: FileOutputStream,
        strict: Boolean
    ) {
        val dns = packet.copyOfRange(p.payloadOffset, p.payloadOffset + p.payloadLen)
        val q = DnsPacket.parseQuery(dns, dns.size)

        var reply: ByteArray? = null
        if (q != null) {
            val safeIp = safeSearchMap[q.name]
            if (safeIp != null) {
                reply = DnsPacket.buildSafeSearchResponse(dns, q, safeIp)
            } else if (blocklist.isBlocked(q.name)) {
                if (shouldRecord(q.name)) {
                    Store.recordBlocked(this, q.name)
                    updateNotification()
                }
                reply = DnsPacket.buildBlockedResponse(dns, q)
            } else if (strict && isDohHost(q.name)) {
                reply = DnsPacket.buildBlockedResponse(dns, q)
            }
        }
        if (reply == null) reply = forward(dns)
        if (reply == null) return

        val ip = Ipv4Udp.build(p.dstIp, p.srcIp, 53, p.srcPort, reply, reply.size)
        try {
            synchronized(out) { out.write(ip) }
        } catch (e: Exception) {
            // Tunnel already closed.
        }
    }

    private fun forward(dns: ByteArray): ByteArray? {
        for (server in UPSTREAM_DNS) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket) // keep this socket outside the tunnel
                    socket.soTimeout = 3000
                    socket.connect(InetAddress.getByName(server), 53)
                    socket.send(DatagramPacket(dns, dns.size))
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    return buf.copyOf(resp.length)
                }
            } catch (e: Exception) {
                // Try the next upstream.
            }
        }
        return null
    }

    private fun isDohHost(name: String) =
        DOH_HOSTS.any { name == it || name.endsWith(".$it") }

    /** Browsers ask for A, AAAA and HTTPS records of the same site; count the site once. */
    private fun shouldRecord(name: String): Boolean = synchronized(lastBlocked) {
        val now = System.currentTimeMillis()
        val last = lastBlocked[name]
        if (last != null && now - last < 5000) {
            false
        } else {
            if (lastBlocked.size > 200) lastBlocked.clear()
            lastBlocked[name] = now
            true
        }
    }

    // ---- Notification ----

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("Protecting focus, clarity and healthy minds.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            )
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // ---- Shutdown ----

    private fun stopFilter() {
        isRunning = false
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        pool?.shutdownNow()
        pool = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Called when the user or another VPN app takes the VPN slot away. */
    override fun onRevoke() {
        stopFilter()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        pool?.shutdownNow()
        super.onDestroy()
    }
}
