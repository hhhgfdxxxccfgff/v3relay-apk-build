package com.example.relay.service

import android.net.VpnService
import java.net.DatagramSocket
import java.net.Socket

object VpnProtector {
    @Volatile
    var activeVpn: VpnService? = null

    fun protect(socket: Socket): Boolean {
        return try {
            activeVpn?.protect(socket) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun protect(socket: DatagramSocket): Boolean {
        return try {
            activeVpn?.protect(socket) ?: false
        } catch (_: Exception) {
            false
        }
    }
}
