package com.safespace.guard

/** Helpers for reading DNS questions and building IPv4/UDP packets by hand. */
object DnsPacket {

    class Query(val name: String, val type: Int, val questionEnd: Int)

    /** Reads the first question of a DNS query. Returns null if this is not a plain query. */
    fun parseQuery(dns: ByteArray, len: Int): Query? {
        if (len < 17) return null
        if ((dns[2].toInt() and 0x80) != 0) return null // already a response
        val questionCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        if (questionCount < 1) return null

        var pos = 12
        val name = StringBuilder()
        while (true) {
            if (pos >= len) return null
            val labelLen = dns[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos++
                break
            }
            if ((labelLen and 0xC0) != 0) return null // compression is not used in questions
            if (pos + 1 + labelLen > len) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 1..labelLen) name.append((dns[pos + i].toInt() and 0xFF).toChar())
            pos += 1 + labelLen
        }
        if (pos + 4 > len) return null
        val type = ((dns[pos].toInt() and 0xFF) shl 8) or (dns[pos + 1].toInt() and 0xFF)
        return Query(name.toString().lowercase(), type, pos + 4)
    }

    /** Builds an NXDOMAIN reply, so the browser shows "site can't be reached". */
    fun buildBlockedResponse(query: ByteArray, q: Query): ByteArray {
        val out = ByteArray(q.questionEnd)
        System.arraycopy(query, 0, out, 0, q.questionEnd)
        out[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // QR=1, keep opcode and RD
        out[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        out[4] = 0
        out[5] = 1 // one question
        for (i in 6..11) out[i] = 0 // no answers, authority or additional records
        return out
    }

    /** Builds a DNS A-record response pointing domains to official SafeSearch/Restricted IPs. */
    fun buildSafeSearchResponse(query: ByteArray, q: Query, ipAddress: String): ByteArray {
        val ipBytes = ipAddress.split(".").map { it.toInt().toByte() }.toByteArray()
        if (ipBytes.size != 4) return buildBlockedResponse(query, q)

        val ansLen = 16
        val out = ByteArray(q.questionEnd + ansLen)
        System.arraycopy(query, 0, out, 0, q.questionEnd)

        // Header
        out[2] = 0x81.toByte() // QR=1, AA=1, RD=1
        out[3] = 0x80.toByte() // RA=1
        out[4] = 0
        out[5] = 1 // QDCOUNT = 1
        out[6] = 0
        out[7] = 1 // ANCOUNT = 1
        out[8] = 0
        out[9] = 0
        out[10] = 0
        out[11] = 0

        var pos = q.questionEnd
        // Answer: pointer to name at offset 12 (0xC00C)
        out[pos++] = 0xC0.toByte()
        out[pos++] = 0x0C.toByte()
        // Type A (1)
        out[pos++] = 0
        out[pos++] = 1
        // Class IN (1)
        out[pos++] = 0
        out[pos++] = 1
        // TTL (300 seconds)
        out[pos++] = 0
        out[pos++] = 0
        out[pos++] = 1
        out[pos++] = 0x2C.toByte()
        // RDLENGTH (4)
        out[pos++] = 0
        out[pos++] = 4
        // RDATA (IP)
        System.arraycopy(ipBytes, 0, out, pos, 4)
        return out
    }
}

/** Minimal IPv4 + UDP parsing and building (no options, no fragmentation). */
object Ipv4Udp {

    class Parsed(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLen: Int
    )

    fun parse(buf: ByteArray, len: Int): Parsed? {
        if (len < 28) return null
        val b0 = buf[0].toInt() and 0xFF
        if ((b0 shr 4) != 4) return null
        val ihl = (b0 and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != 17) return null // UDP only
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        val udpLen = u16(buf, ihl + 4)
        val payloadLen = minOf(udpLen - 8, len - ihl - 8)
        if (payloadLen < 0) return null
        return Parsed(
            buf.copyOfRange(12, 16),
            buf.copyOfRange(16, 20),
            srcPort,
            dstPort,
            ihl + 8,
            payloadLen
        )
    }

    fun build(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val total = 28 + payloadLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte() // IPv4, 20 byte header
        put16(p, 2, total)
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payloadLen)
        // UDP checksum 0 is legal for IPv4
        System.arraycopy(payload, 0, p, 28, payloadLen)
        return p
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
