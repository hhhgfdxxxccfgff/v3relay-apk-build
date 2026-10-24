package com.example.relay.service

import android.net.Uri
import com.example.relay.ws.WSConn
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Background cover traffic manager matching client_ws.py cover_loop:
 * Periodically generates realistic web-visitor traffic (occasional chat hello/ping
 * over WS and analytics beacon POST to /api/views).
 */
object CoverTrafficManager {

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private val random = SecureRandom()

    fun start(workerUrl: String, cleanIp: String?) {
        if (workerUrl.isBlank()) return
        if (isRunning.getAndSet(true)) return

        workerThread = thread(start = true, isDaemon = true, name = "CoverTraffic") {
            while (isRunning.get()) {
                try {
                    // Sleep 25 - 50 seconds
                    val sleepMs = (25000L..50000L).random()
                    Thread.sleep(sleepMs)
                    if (!isRunning.get()) break

                    // 1. WebSocket chat hello / ping
                    try {
                        val w = WSConn(workerUrl, "/api/live", cleanIp, timeoutMs = 6000).connect()
                        w.sendText("{\"hello\": 1}")
                        try {
                            w.setReadTimeout(6000)
                            w.recvMsg()
                        } catch (_: Throwable) {}
                        try {
                            val pingVal = random.nextInt(9999) + 1
                            w.sendText("{\"ping\": $pingVal}")
                        } catch (_: Throwable) {}
                        try {
                            w.close()
                        } catch (_: Throwable) {}
                    } catch (_: Throwable) {}

                    if (!isRunning.get()) break

                    // 2. HTTPS analytics beacon POST to /api/views
                    try {
                        sendAnalyticsBeacon(workerUrl, cleanIp)
                    } catch (_: Throwable) {}

                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {}
            }
        }
    }

    private fun sendAnalyticsBeacon(workerUrl: String, cleanIp: String?) {
        var clean = workerUrl.trim()
        if (!clean.contains("://")) clean = "https://$clean"
        val uri = Uri.parse(clean)
        val host = uri.host ?: return
        val connectHost = if (!cleanIp.isNullOrBlank()) cleanIp.trim() else host

        val pages = listOf("/", "/products", "/blog")
        val page = pages.random()
        val visitor = "v-" + randomHex(3)
        val data = "v." + randomHex(4) + "." + randomHex(10) + "." + randomHex(10) + "." + randomHex(8)

        val json = JSONObject().apply {
            put("page", page)
            put("visitor", visitor)
            put("data", data)
        }.toString()

        val jsonBytes = json.toByteArray(Charsets.UTF_8)

        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = 6000
            VpnProtector.protect(rawSocket)
            rawSocket.connect(InetSocketAddress(connectHost, 443), 6000)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val ssl = sslContext.socketFactory.createSocket(rawSocket, host, 443, true) as SSLSocket
            sslSocket = ssl

            val sslParams = ssl.sslParameters
            val isIp = host.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) || host.contains(":")
            if (!isIp) {
                sslParams.serverNames = listOf(SNIHostName(host))
            }
            ssl.sslParameters = sslParams
            ssl.soTimeout = 6000
            ssl.startHandshake()

            val out: OutputStream = ssl.getOutputStream()
            val req = buildString {
                append("POST /api/views HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Content-Type: application/json\r\n")
                append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36\r\n")
                append("Referer: $workerUrl/\r\n")
                append("Accept: application/json, text/plain, */*\r\n")
                append("Content-Length: ${jsonBytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            out.write(req.toByteArray(Charsets.UTF_8))
            out.write(jsonBytes)
            out.flush()

            val buf = ByteArray(1024)
            val inp = ssl.getInputStream()
            var totalBytesRead = 0
            try {
                while (totalBytesRead < 4096) {
                    val count = inp.read(buf)
                    if (count == -1) break
                    totalBytesRead += count
                }
            } catch (_: Throwable) {}

        } finally {
            try { sslSocket?.close() } catch (_: Throwable) {}
            try { rawSocket?.close() } catch (_: Throwable) {}
        }
    }

    private fun randomHex(numBytes: Int): String {
        val b = ByteArray(numBytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun stop() {
        isRunning.set(false)
        workerThread?.interrupt()
        workerThread = null
    }
}
