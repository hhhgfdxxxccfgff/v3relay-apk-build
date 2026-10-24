package com.example.relay.service

import android.content.Context
import com.example.relay.data.ProxyConfig
import com.example.relay.data.ProxyMode

object ServiceStarter {

    fun start(context: Context, config: ProxyConfig) {
        if (config.mode == ProxyMode.VPN_MODE) {
            startVpn(context, config)
        } else {
            startProxy(context, config)
        }
    }

    fun startVpn(context: Context, config: ProxyConfig) {
        RelayVpnService.startService(
            context = context,
            workerUrl = config.workerUrl,
            secret = config.secret,
            cleanIp = config.cleanIp,
            socksPort = config.socksPort,
            httpPort = config.httpPort,
            dnsServers = config.dnsServers,
            excludedApps = ArrayList(config.excludedApps),
            allowLan = config.allowLan
        )
    }

    fun startProxy(context: Context, config: ProxyConfig) {
        RelayProxyService.startService(
            context = context,
            workerUrl = config.workerUrl,
            secret = config.secret,
            cleanIp = config.cleanIp,
            socksPort = config.socksPort,
            httpPort = config.httpPort,
            allowLan = config.allowLan
        )
    }
}
