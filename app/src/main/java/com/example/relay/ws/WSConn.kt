package com.example.relay.ws

import android.net.Uri
import android.os.Build
import android.util.Base64
import com.example.relay.service.VpnProtector
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

private const val MAX_FRAME_SIZE = 16 * 1024 * 1024

class WSConn(
    private val workerUrl: String,
    private val path: String = "/api/live",
    private val cleanIp: String? = null,
    private val timeoutMs: Int = 12000,
    private val stealthJitter: Boolean = true
) {
    val sniHost: String
    val hostIp: String
    private val port: Int = 443

    var socket: Socket? = null
        private set
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val sendLock = Any()
    private val random = SecureRandom()

    init {
        var clean = workerUrl.trim()
        if (!clean.contains("://")) {
            clean = "https://$clean"
        }
        val uri = Uri.parse(clean)
        val host = uri.host?.trim()
            ?: clean.substringAfter("://").substringBefore("/").substringBefore(":").trim()
        if (host.isBlank()) throw IllegalArgumentException("Invalid worker URL: missing host")
        sniHost = host
        hostIp = if (!cleanIp.isNullOrBlank()) cleanIp.trim() else host
    }

    @Volatile
    private var jitLeft = 3

    private fun jittered(len: Int) {
        // Mirror the reference: jitter only the first 3 frames per connection.
        if (!stealthJitter || jitLeft <= 0) return
        jitLeft--
        if (len in 1..2048) {
            val ms = (1L..25L).random()
            try { Thread.sleep(ms) } catch (_: InterruptedException) {}
        }
    }

    fun connect(): WSConn {
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        try { rawSocket.keepAlive = true } catch (_: Exception) {}
        VpnProtector.protect(rawSocket)
        rawSocket.connect(InetSocketAddress(hostIp, port), timeoutMs)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, sniHost, port, true) as SSLSocket

        val sslParams = sslSocket.sslParameters
        val isIp = sniHost.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) || sniHost.contains(":")
        if (!isIp) {
            sslParams.serverNames = listOf(SNIHostName(sniHost))
        }

        // Chrome-like ALPN: "http/1.1" only (avoid h2 breaking manual WS Upgrade)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                sslParams.applicationProtocols = arrayOf("http/1.1")
            } catch (_: Exception) {}
        }

        // Tune toward Chrome-like ciphersuites where supported
        try {
            val supported = sslSocket.supportedCipherSuites.toSet()
            val preferredCiphers = listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
            ).filter { supported.contains(it) }

            if (preferredCiphers.isNotEmpty()) {
                val remaining = sslSocket.enabledCipherSuites.filter { !preferredCiphers.contains(it) }
                sslParams.cipherSuites = (preferredCiphers + remaining).toTypedArray()
            }
        } catch (_: Exception) {}

        sslSocket.sslParameters = sslParams
        sslSocket.soTimeout = timeoutMs
        sslSocket.startHandshake()

        val rawOut = BufferedOutputStream(sslSocket.outputStream)
        val rawIn = BufferedInputStream(sslSocket.inputStream)

        val keyBytes = ByteArray(16)
        random.nextBytes(keyBytes)
        val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $sniHost\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: https://$sniHost\r\n")
            append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36\r\n")
            append("\r\n")
        }

        rawOut.write(req.toByteArray(Charsets.UTF_8))
        rawOut.flush()

        // Read handshake response
        val respBuf = ByteArrayOutputStream()
        val temp = ByteArray(1)
        var matchCount = 0
        while (true) {
            val r = rawIn.read(temp)
            if (r == -1) throw IOException("WebSocket handshake closed prematurely")
            respBuf.write(temp, 0, 1)
            val b = temp[0].toInt()
            if ((matchCount == 0 || matchCount == 2) && b == '\r'.code) {
                matchCount++
            } else if ((matchCount == 1 || matchCount == 3) && b == '\n'.code) {
                matchCount++
                if (matchCount == 4) break
            } else {
                matchCount = if (b == '\r'.code) 1 else 0
            }

            if (respBuf.size() > 16384) {
                throw IOException("WebSocket handshake headers too long")
            }
        }

        val respHeaderStr = respBuf.toString("UTF-8")
        val statusLine = respHeaderStr.lineSequence().firstOrNull() ?: ""
        if (!statusLine.contains("101")) {
            throw IOException("WebSocket handshake failed (not 101): $statusLine")
        }

        sslSocket.soTimeout = 0
        socket = sslSocket
        inputStream = rawIn
        outputStream = rawOut

        return this
    }

    fun sendBin(data: ByteArray) {
        jittered(data.size)
        sendFrame(opcode = 0x02, payload = data)
    }

    fun sendText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        jittered(b.size)
        sendFrame(opcode = 0x01, payload = b)
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4)
        random.nextBytes(mask)

        val len = payload.size
        val header = ByteArrayOutputStream()
        header.write(0x80 or (opcode and 0x0F))

        val maskBit = 0x80
        when {
            len < 126 -> {
                header.write(maskBit or len)
            }
            len < 65536 -> {
                header.write(maskBit or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(maskBit or 127)
                val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(len.toLong()).array()
                header.write(lenBuf)
            }
        }

        header.write(mask)

        val maskedPayload = ByteArray(len)
        for (i in 0 until len) {
            maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        synchronized(sendLock) {
            val out = outputStream ?: throw IOException("Socket closed")
            out.write(header.toByteArray())
            out.write(maskedPayload)
            out.flush()
        }
    }

    private fun recvExact(n: Int): ByteArray {
        val inStream = inputStream ?: throw IOException("Socket not connected")
        val buf = ByteArray(n)
        var totalRead = 0
        while (totalRead < n) {
            val count = inStream.read(buf, totalRead, n - totalRead)
            if (count == -1) throw EOFException("WebSocket stream reached EOF")
            totalRead += count
        }
        return buf
    }

    /**
     * Reads a full WebSocket message frame. Handles ping/pong and fragmentation.
     * Returns Pair(opcode, payload).
     */
    fun recvMsg(): Pair<Int, ByteArray> {
        while (true) {
            val h = recvExact(2)
            val b1 = h[0].toInt() and 0xFF
            val b2 = h[1].toInt() and 0xFF

            val fin = (b1 and 0x80) != 0
            val op = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            var ln: Long = (b2 and 0x7F).toLong()

            if (ln == 126L) {
                val lenBytes = recvExact(2)
                ln = (((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)).toLong()
            } else if (ln == 127L) {
                val lenBytes = recvExact(8)
                ln = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).long
            }

            if (ln > MAX_FRAME_SIZE) throw IOException("WebSocket frame too large: $ln")

            val mk = if (masked) recvExact(4) else null

            var payload = if (ln > 0) recvExact(ln.toInt()) else ByteArray(0)
            if (mk != null && payload.isNotEmpty()) {
                val unmasked = ByteArray(payload.size)
                for (i in payload.indices) {
                    unmasked[i] = (payload[i].toInt() xor mk[i % 4].toInt()).toByte()
                }
                payload = unmasked
            }

            if (op == 0x09) { // Ping -> send Pong
                sendCtrl(0x0A, payload)
                continue
            }

            if (op == 0x08) { // Close
                throw IOException("WebSocket received close frame")
            }

            if (op == 0x01 || op == 0x02 || op == 0x00) {
                if (!fin) {
                    val frags = mutableListOf<ByteArray>()
                    frags.add(payload)
                    while (true) {
                        val h2 = recvExact(2)
                        val b1b = h2[0].toInt() and 0xFF
                        val b2b = h2[1].toInt() and 0xFF
                        val finB = (b1b and 0x80) != 0
                        var lnB = (b2b and 0x7F).toLong()

                        if (lnB == 126L) {
                            val lb = recvExact(2)
                            lnB = (((lb[0].toInt() and 0xFF) shl 8) or (lb[1].toInt() and 0xFF)).toLong()
                        } else if (lnB == 127L) {
                            lnB = ByteBuffer.wrap(recvExact(8)).order(ByteOrder.BIG_ENDIAN).long
                        }

                        if (lnB > MAX_FRAME_SIZE) throw IOException("WebSocket frame too large: $lnB")

                        val mkB = if ((b2b and 0x80) != 0) recvExact(4) else null
                        var pl = if (lnB > 0) recvExact(lnB.toInt()) else ByteArray(0)
                        if (mkB != null && pl.isNotEmpty()) {
                            val unmasked = ByteArray(pl.size)
                            for (i in pl.indices) {
                                unmasked[i] = (pl[i].toInt() xor mkB[i % 4].toInt()).toByte()
                            }
                            pl = unmasked
                        }
                        frags.add(pl)
                        if (finB) break
                    }
                    val totalSize = frags.sumOf { it.size }
                    if (totalSize > MAX_FRAME_SIZE) throw IOException("Fragmented WebSocket message too large: $totalSize")
                    val assembled = ByteArray(totalSize)
                    var off = 0
                    for (f in frags) {
                        System.arraycopy(f, 0, assembled, off, f.size)
                        off += f.size
                    }
                    payload = assembled
                }
                return Pair(op, payload)
            }
        }
    }

    private fun sendCtrl(op: Int, data: ByteArray) {
        // Control frames now share the full length encoding (fixes length > 125).
        try {
            sendFrame(opcode = op, payload = data)
        } catch (_: Exception) {}
    }

    fun setReadTimeout(ms: Int) {
        try { socket?.soTimeout = ms } catch (_: Exception) {}
    }

    fun close() {
        try {
            sendCtrl(0x08, ByteArray(0))
        } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
