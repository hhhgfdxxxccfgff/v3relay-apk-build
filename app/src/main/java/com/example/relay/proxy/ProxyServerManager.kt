package com.example.relay.proxy

import com.example.relay.crypto.StreamCrypt
import com.example.relay.data.ConfigDecoder
import com.example.relay.data.LogEntry
import com.example.relay.data.LogType
import com.example.relay.data.ProxyConfig
import com.example.relay.data.ProxyStats
import com.example.relay.service.CoverTrafficManager
import com.example.relay.service.VpnProtector
import com.example.relay.tunnel.WSTunnel
import com.example.relay.v2.V2Tunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

object ProxyServerManager {

    private val _stats = MutableStateFlow(ProxyStats())
    val stats: StateFlow<ProxyStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var socksServerSocket: ServerSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private val isStopping = AtomicBoolean(false)

    private val totalUpload = AtomicLong(0L)
    private val totalDownload = AtomicLong(0L)
    private val activeConns = AtomicInteger(0)
    private val totalConns = AtomicLong(0L)
    private var startTime = 0L
    private val lastStatsEmitTime = AtomicLong(0L)

    private val activeClients = ConcurrentHashMap<Socket, Boolean>()

    fun start(config: ProxyConfig) {
        if (_isRunning.value) return
        isStopping.set(false)

        if (config.workerUrl.isBlank() || config.secret.isBlank()) {
            addLog("خطا: آدرس ورکر و کلید رمزگذاری یافت نشد.", LogType.ERROR)
            return
        }

        startTime = System.currentTimeMillis()
        totalUpload.set(0)
        totalDownload.set(0)
        activeConns.set(0)
        totalConns.set(0)
        lastStatsEmitTime.set(0L)
        updateStats()

        // python main(): انتخاب پروتکل دقیقا مثل مرجع
        var kpub = config.kpubPublic.trim()
        var pmode = if (config.pMode == 0) {
            if (kpub.isNotEmpty()) 2 else 1
        } else {
            config.pMode
        }
        if (kpub.isNotEmpty() && !(kpub.length == 64 && kpub.all { it in "0123456789abcdefABCDEF" })) {
            addLog("kpub invalid — v2 off", LogType.ERROR)
            kpub = ""
            pmode = 1
        }
        var proto = if (kpub.isNotEmpty() && pmode == 2) "v2" else "v1"
        // python `_V2["init"] = info` + eager build_v2_manager()
        try {
            V2Tunnel.resetStartup()
            if (proto == "v2") {
                V2Tunnel.initStartup(config.workerUrl, config.secret, kpub.lowercase(), config.cleanIp.ifBlank { null })
                val built = try {
                    V2Tunnel.buildNow() != null
                } catch (e: Throwable) {
                    null
                }
                if (built != true) {
                    proto = "v1"
                    addLog("v2 init failed → v1", LogType.ERROR)
                }
            }
        } catch (_: Throwable) {}

        addLog("شروع سرویس رله اختصاصی...", LogType.INFO)
        val targetLabel = if (config.configName.isNotBlank()) "پروفایل: ${config.configName}" else "پروفایل رمزنگاری‌شده"
        addLog("$targetLabel (محافظت‌شده و امن)", LogType.INFO)
        if (config.cleanIp.isNotBlank()) {
            addLog("آی‌پی تمیز فعال: ${config.cleanIp}", LogType.INFO)
        }
        addLog("protocol: $proto", LogType.INFO)

        val bindHost = if (config.allowLan) "0.0.0.0" else "127.0.0.1"

        try {
            // SOCKS5 Server
            socksServerSocket = ServerSocket(config.socksPort, 50, InetAddress.getByName(bindHost)).apply {
                reuseAddress = true
            }
            addLog("سرور SOCKS5 فعال روی $bindHost:${config.socksPort}", LogType.SUCCESS)
            if (config.allowLan) {
                addLog("هشدار: پروکسی روی شبکه محلی (LAN) در دسترس است", LogType.TRAFFIC)
            }

            thread(start = true, isDaemon = true, name = "SocksAcceptor") {
                while (!isStopping.get()) {
                    try {
                        val client = socksServerSocket?.accept() ?: break
                        totalConns.incrementAndGet()
                        activeConns.incrementAndGet()
                        activeClients[client] = true
                        updateStats()

                        thread(start = true, isDaemon = true, name = "SocksClient-${client.port}") {
                            try {
                                Socks5Handler.handle(
                                    clientSocket = client,
                                    workerUrl = config.workerUrl,
                                    secret = config.secret,
                                    cleanIp = config.cleanIp.ifBlank { null },
                                    callback = createTunnelCallback(client)
                                )
                            } catch (_: Throwable) {
                                try { client.close() } catch (_: Throwable) {}
                            } finally {
                                if (activeClients.remove(client) != null) {
                                    activeConns.decrementAndGet()
                                    updateStats()
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        if (isStopping.get()) break
                    }
                }
            }

            // HTTP Proxy Server
            if (config.httpPort > 0) {
                httpServerSocket = ServerSocket(config.httpPort, 50, InetAddress.getByName(bindHost)).apply {
                    reuseAddress = true
                }
                addLog("پروکسی HTTP فعال روی $bindHost:${config.httpPort}", LogType.SUCCESS)

                thread(start = true, isDaemon = true, name = "HttpAcceptor") {
                    while (!isStopping.get()) {
                        try {
                            val client = httpServerSocket?.accept() ?: break
                            totalConns.incrementAndGet()
                            activeConns.incrementAndGet()
                            activeClients[client] = true
                            updateStats()

                            thread(start = true, isDaemon = true, name = "HttpClient-${client.port}") {
                                try {
                                    HttpProxyHandler.handle(
                                        clientSocket = client,
                                        workerUrl = config.workerUrl,
                                        secret = config.secret,
                                        cleanIp = config.cleanIp.ifBlank { null },
                                        callback = createTunnelCallback(client)
                                    )
                                } catch (_: Throwable) {
                                    try { client.close() } catch (_: Throwable) {}
                                } finally {
                                    if (activeClients.remove(client) != null) {
                                        activeConns.decrementAndGet()
                                        updateStats()
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            if (isStopping.get()) break
                        }
                    }
                }
            }

            _isRunning.value = true
            addLog("تونل با موفقیت متصل شد و آماده انتقال ترافیک است.", LogType.SUCCESS)

            // Start background stealth cover traffic safely
            try {
                CoverTrafficManager.start(config.workerUrl, config.cleanIp.ifBlank { null })
            } catch (_: Throwable) {}

        } catch (e: Throwable) {
            addLog("خطا در باز کردن پورت محلی: ${e.message}", LogType.ERROR)
            stop()
        }
    }

    fun stop() {
        if (isStopping.getAndSet(true)) return
        if (!_isRunning.value) {
            isStopping.set(false)
            return
        }
        addLog("درحال متوقف‌سازی سرویس پروکسی...", LogType.INFO)

        try {
            socksServerSocket?.close()
        } catch (_: Throwable) {}
        socksServerSocket = null

        try {
            httpServerSocket?.close()
        } catch (_: Throwable) {}
        httpServerSocket = null

        activeClients.keys.forEach { client ->
            try { client.close() } catch (_: Throwable) {}
        }
        activeClients.clear()
        activeConns.set(0)

        // توقف v2 (python: عدم مکث طولانی؛ سشن‌ها بسته و manager ریست می‌شود)
        try { V2Tunnel.resetStartup() } catch (_: Throwable) {}

        _isRunning.value = false
        try {
            CoverTrafficManager.stop()
        } catch (_: Throwable) {}
        updateStats()
        addLog("سرویس متوقف شد.", LogType.INFO)
    }

    /**
     * Bulletproof Latency Measurement:
     * 1. URL normalization & sanitization
     * 2. If proxy is actively running, attempts local SOCKS5 tunnel ping
     * 3. Falls back to direct TCP/TLS handshake with clean IP fallback to host
     */
    suspend fun testLatency(workerUrl: String, cleanIp: String?): Result<Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        kotlin.runCatching {
            var raw = workerUrl.trim()
            if (raw.isBlank()) throw IllegalArgumentException("Worker URL is empty")

            // If raw is a config code, decode it
            if (ConfigDecoder.isConfigCode(raw)) {
                val decoded = ConfigDecoder.decodeConfig(raw).getOrNull()
                if (decoded != null) raw = decoded.workerUrl.trim()
            }

            if (!raw.contains("://")) {
                raw = "https://$raw"
            }

            val uri = android.net.Uri.parse(raw)
            val extractedHost = uri.host?.trim()
                ?: raw.substringAfter("://").substringBefore("/").substringBefore(":").trim()

            if (extractedHost.isBlank()) throw IllegalArgumentException("Invalid URL host")

            val targetPort = if (uri.port != -1) uri.port else (if (raw.startsWith("http://") || raw.startsWith("ws://")) 80 else 443)
            val cleanHost = cleanIp?.trim()?.ifBlank { null }

            // 1. If tunnel is active, measure real ping through local SOCKS5
            if (_isRunning.value && socksServerSocket != null) {
                try {
                    val activePort = socksServerSocket?.localPort ?: 1080
                    val socksLatency = measureViaLocalSocks(activePort, extractedHost, targetPort)
                    if (socksLatency > 0) return@runCatching socksLatency
                } catch (_: Throwable) {
                    // Fall back to direct test
                }
            }

            // 2. Direct measurement
            val hostToTest = cleanHost ?: extractedHost
            try {
                measureDirectTcpTls(hostToTest, extractedHost, targetPort)
            } catch (e: Throwable) {
                // If clean IP failed and was different from host, retry direct host
                if (cleanHost != null && cleanHost != extractedHost) {
                    measureDirectTcpTls(extractedHost, extractedHost, targetPort)
                } else {
                    throw e
                }
            }
        }
    }

    private fun measureDirectTcpTls(connectHost: String, sniHost: String, port: Int): Long {
        val start = System.currentTimeMillis()
        val socket = Socket()
        socket.tcpNoDelay = true
        VpnProtector.protect(socket)

        try {
            socket.connect(InetSocketAddress(connectHost, port), 4000)

            if (port == 443) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, null, null)
                val sslSocket = sslContext.socketFactory.createSocket(socket, sniHost, port, true) as SSLSocket
                val sslParams = sslSocket.sslParameters
                val isIp = sniHost.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) || sniHost.contains(":")
                if (!isIp) {
                    sslParams.serverNames = listOf(SNIHostName(sniHost))
                }
                sslSocket.sslParameters = sslParams
                sslSocket.soTimeout = 4000
                sslSocket.startHandshake()
                try { sslSocket.close() } catch (_: Throwable) {}
            }
            return maxOf(1L, System.currentTimeMillis() - start)
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun measureViaLocalSocks(socksPort: Int, targetHost: String, targetPort: Int): Long {
        val start = System.currentTimeMillis()
        val client = Socket()
        client.tcpNoDelay = true
        try {
            client.connect(InetSocketAddress("127.0.0.1", socksPort), 2500)
            val out = client.getOutputStream()
            val inp = client.getInputStream()

            // 1. Greeting: [0x05, 0x01, 0x00] (No Auth)
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()

            val greetingResp = ByteArray(2)
            readFully(inp, greetingResp)
            if (greetingResp[0] != 0x05.toByte() || greetingResp[1] != 0x00.toByte()) {
                throw IllegalStateException("SOCKS5 auth rejected")
            }

            // 2. Request: CONNECT to targetHost:targetPort
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            val req = ByteArray(4 + 1 + hostBytes.size + 2)
            req[0] = 0x05
            req[1] = 0x01 // CONNECT
            req[2] = 0x00 // RSV
            req[3] = 0x03 // DOMAINNAME
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            val pIdx = 5 + hostBytes.size
            req[pIdx] = ((targetPort shr 8) and 0xFF).toByte()
            req[pIdx + 1] = (targetPort and 0xFF).toByte()

            out.write(req)
            out.flush()

            val respHead = ByteArray(4)
            readFully(inp, respHead)
            if (respHead[1] != 0x00.toByte()) {
                throw IllegalStateException("SOCKS5 connect failed: ${respHead[1]}")
            }

            return maxOf(1L, System.currentTimeMillis() - start)
        } finally {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = inp.read(buf, off, buf.size - off)
            if (r == -1) throw java.io.EOFException("SOCKS stream closed")
            off += r
        }
    }

    private fun createTunnelCallback(client: Socket) = object : WSTunnel.TunnelCallback {
        override fun onConnectionOpened(target: String) {
            addLog("اتصال برقرار شد: $target", LogType.TRAFFIC)
            updateStats()
        }

        override fun onTraffic(uploadedBytes: Long, downloadedBytes: Long) {
            totalUpload.addAndGet(uploadedBytes)
            totalDownload.addAndGet(downloadedBytes)
            val now = System.currentTimeMillis()
            // Throttle UI stats emission to at most once per 350ms to prevent main thread Compose freezing
            if (now - lastStatsEmitTime.get() >= 350L) {
                lastStatsEmitTime.set(now)
                updateStats()
            }
        }

        override fun onConnectionClosed(target: String, error: String?) {
            if (activeClients.remove(client) != null) {
                activeConns.decrementAndGet()
            }
            updateStats()
            if (error != null) {
                addLog("بسته شد: $target ($error)", LogType.ERROR)
            } else {
                addLog("پایان اتصال: $target", LogType.INFO)
            }
        }
    }

    private fun updateStats() {
        _stats.value = ProxyStats(
            bytesUploaded = totalUpload.get(),
            bytesDownloaded = totalDownload.get(),
            activeConnections = maxOf(0, activeConns.get()),
            totalConnections = totalConns.get(),
            startTimeMillis = startTime
        )
    }

    fun addLog(msg: String, type: LogType = LogType.INFO) {
        _logs.update { current ->
            val newEntry = LogEntry(message = msg, type = type)
            (current + newEntry).takeLast(120)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
