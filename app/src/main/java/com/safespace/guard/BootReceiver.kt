package com.safespace.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/** Restarts protection after a reboot, if the parent had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Store.isProtectionEnabled(context)) return
        if (VpnService.prepare(context) != null) return // VPN permission no longer granted
        ContextCompat.startForegroundService(
            context,
            Intent(context, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_START)
        )
    }
}
