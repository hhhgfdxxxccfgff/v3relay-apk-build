package com.example.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.example.relay.data.ConfigRepository
import com.example.relay.data.ProxyMode

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val config = ConfigRepository(context).loadConfig()
            if (!config.autoStartOnBoot) return
            if (config.workerUrl.isBlank() || config.secret.isBlank()) return

            if (config.mode == ProxyMode.VPN_MODE) {
                // Without prior user consent we cannot show the VPN permission dialog from background
                if (VpnService.prepare(context) == null) {
                    ServiceStarter.startVpn(context, config)
                } else {
                    Log.w("BootReceiver", "Auto-start skipped: VPN permission not granted")
                }
            } else {
                ServiceStarter.startProxy(context, config)
            }
        } catch (e: Throwable) {
            Log.e("BootReceiver", "Auto-start failed", e)
        }
    }
}
