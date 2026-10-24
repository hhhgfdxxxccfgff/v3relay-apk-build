package com.example.relay.data

import java.util.concurrent.atomic.AtomicLong

enum class LogType {
    INFO,
    SUCCESS,
    ERROR,
    TRAFFIC
}

enum class ProxyMode {
    VPN_MODE,    // Full Device VPN (all phone apps)
    PROXY_ONLY   // Local SOCKS5 / HTTP (custom app configuration)
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val globalLogIdCounter = AtomicLong(1L)

data class LogEntry(
    val id: Long = globalLogIdCounter.getAndIncrement(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val type: LogType = LogType.INFO
)

data class ProxyConfig(
    val workerUrl: String = "",
    val secret: String = "",
    val cleanIp: String = "",
    val socksPort: Int = 1080,
    val httpPort: Int = 8080,              // 0 = HTTP proxy disabled
    val mode: ProxyMode = ProxyMode.VPN_MODE,
    val configCode: String = "",
    val configName: String = "",
    val dnsServers: String = "1.1.1.1, 8.8.8.8",
    val excludedApps: Set<String> = emptySet(),
    val autoStartOnBoot: Boolean = false,
    val allowLan: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: String = ""              // "" = system, "fa", "en"
)

data class ProxyStats(
    val bytesUploaded: Long = 0L,
    val bytesDownloaded: Long = 0L,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0L,
    val startTimeMillis: Long = 0L
)
