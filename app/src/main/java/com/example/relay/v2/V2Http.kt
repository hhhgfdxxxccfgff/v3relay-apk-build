package com.example.relay.v2

import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import com.example.relay.service.VpnProtector

/**
 * Exact port of client_ws.py v2 HTTP layer: _v2_urlparse/_v2_http_conn/
 * _v2_http_post/_v2_sid_echo/_V2Body/_v2_bucket_pad over manual HTTP/1.1
 * on WSConn-style TLS (raw socket -> protect -> SSL wrap with SNI).
 */
object V2Http {

    const val V2_TICKET_TTL = 600
    const val V2_WIN_CAP = 2097152
    const val V2_SYNC_MAX = 1900000
    const val V2_MAX_PIECES = 512
    const val V2_STREAM_TIMEOUT = 40
    const val SYNC_ACK_BYTES = 262144

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    fun coverHeaders(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = UA
        m["X-Client-Ver"] = "2.3.1"
        m["X-Device"] = "mobile"
        m["Accept"] = "*/*"
        m["Accept-Language"] = "en-US,en;q=0.9"
        return m
    }

    class ParsedUrl(val https: Boolean, val host: String, val port: Int)

    fun parseWorker(worker: String): ParsedUrl {
        var clean = worker.trim()
        if (!clean.contains("://")) clean = "https://$clean"
        val u = Uri.parse(clean)
        val https = (u.scheme == "https" || u.scheme == "wss")
        val host = (u.host ?: "").trim()
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        return ParsedUrl(https, host, port)
    }

    fun sidEcho(data: ByteArray): String {
        try {
            val target = "\"s\":\"".toByteArray(Charsets.US_ASCII)
            val i = indexOfBytes(data, target)
            if (i < 0) return ""
            val j = i + target.size
            var k = j
            while (k < data.size) {
                if (data[k] == '"'.code.toByte()) break
                k++
            }
            if (k >= data.size) return ""
            return String(data, j, k - j, Charsets.US_ASCII)
        } catch (_: Throwable) {
            return ""
        }
    }

    private fun indexOfBytes(data: ByteArray, target: ByteArray): Int {
        if (data.size < target.size) return -1
        outer@ for (i in 0..data.size - target.size) {
            for (j in target.indices) {
                if (data[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // bucket pad, weights 40/35/25 (16/32/64KiB) - exactly like the server
    private val padRnd = SecureRandom()

    fun bucketPad(pt: ByteArray): ByteArray {
        if (pt.size > 65533) {
            val out = ByteArray(2 + 65533)
            out[0] = 0x00; out[1] = 0x00
            System.arraycopy(pt, 0, out, 2, 65533)
            return out
        }
        val r = padRnd.nextDouble() * 100.0
        var want = 32768
        var a = 0
        for (sz in intArrayOf(16384, 32768, 65536)) {
            a += when (sz) {
                16384 -> 40
                32768 -> 35
                else -> 25
            }
            if (r < a) {
                want = sz
                break
            }
        }
        if (want > 2 + pt.size) {
            var pl = want - 2 - pt.size
            val cap = 65535 - 2 - pt.size
            if (pl > cap) pl = cap
            if (pl < 0) pl = 0
            val out = ByteArray(2 + pl + pt.size)
            out[0] = (pl and 0xFF).toByte()
            out[1] = ((pl shr 8) and 0xFF).toByte()
            val pad = ByteArray(pl)
            padRnd.nextBytes(pad)
            System.arraycopy(pad, 0, out, 2, pl)
            System.arraycopy(pt, 0, out, 2 + pl, pt.size)
            return out
        }
        val out = ByteArray(2 + pt.size)
        out[0] = 0x00; out[1] = 0x00
        System.arraycopy(pt, 0, out, 2, pt.size)
        return out
    }
}

data class V2PostRes(val data: ByteArray, val ctype: String, val code: Int)

/**
 * One V2 HTTP connection: mirrors `_v2_http_conn` + `http.client` response
 * access patterns (post/getresponse/read + _V2Body chunked streaming).
 */
class V2HttpConn(
    workerUrl: String,
    cleanIp: String?,
    timeoutMs: Int
) {
    private val u = V2Http.parseWorker(workerUrl)
    private val sniHost: String = u.host
    private val connectIp: String = if (!cleanIp.isNullOrBlank()) cleanIp.trim() else u.host
    private val port: Int = u.port
    private val timeout: Int = timeoutMs
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    fun connect() {
        val raw = Socket()
        raw.tcpNoDelay = true
        try { raw.keepAlive = true } catch (_: Throwable) {}
        VpnProtector.protect(raw)
        raw.connect(InetSocketAddress(connectIp, port), timeout)
        if (u.https) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val ssl = ctx.socketFactory.createSocket(raw, sniHost, port, true) as javax.net.ssl.SSLSocket
            try {
                val isIp = sniHost.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) || sniHost.contains(":")
                if (!isIp) {
                    val p = ssl.sslParameters
                    p.serverNames = listOf(SNIHostName(sniHost))
                    ssl.sslParameters = p
                }
                ssl.soTimeout = timeout
                ssl.startHandshake()
            } catch (t: Throwable) {
                try { ssl.close() } catch (_: Throwable) {}
                throw t
            }
            socket = ssl
            input = BufferedInputStream(ssl.inputStream)
            output = BufferedOutputStream(ssl.outputStream)
        } else {
            raw.soTimeout = timeout
            socket = raw
            input = BufferedInputStream(raw.inputStream)
            output = BufferedOutputStream(raw.outputStream)
        }
    }

    /** extra: headers merged over the cover set (like python hdrs.update) */
    fun request(method: String, path: String, body: ByteArray?, extra: Map<String, String>?): V2Response {
        val hdrs = V2Http.coverHeaders()
        if (extra != null) for ((k, v) in extra) hdrs[k] = v
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(sniHost).append("\r\n")
        for ((k, v) in hdrs) sb.append(k).append(": ").append(v).append("\r\n")
        if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        val os = output!!
        os.write(head)
        if (body != null && body.isNotEmpty()) os.write(body)
        os.flush()
        return readResponseHeaders()
    }

    private class Line(val bytes: ByteArray, val eof: Boolean)

    private fun readLine(max: Int): Line {
        val buf = java.io.ByteArrayOutputStream()
        var c = 0
        while (c < max) {
            val b = input!!.read()
            if (b < 0) {
                return Line(buf.toByteArray(), true)
            }
            if (b == '\n'.code.toInt()) {
                return Line(buf.toByteArray(), false)
            }
            buf.write(b)
            c++
        }
        return Line(buf.toByteArray(), false)
    }

    private fun readResponseHeaders(): V2Response {
        val statusLine = readLine(4096)
        if (statusLine.eof) throw java.io.IOException("eof status line")
        val parts = statusLine.bytes.toString(Charsets.ISO_8859_1).trim().split(" ")
        if (parts.size < 2) throw java.io.IOException("bad status line")
        val code = parts[1].toIntOrNull() ?: throw java.io.IOException("bad status code")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(4096)
            if (line.eof) throw java.io.IOException("eof headers")
            val t = line.bytes.toString(Charsets.ISO_8859_1).trimEnd('\r')
            if (t.isEmpty()) break
            val idx = t.indexOf(':')
            if (idx > 0) headers[t.substring(0, idx).lowercase()] = t.substring(idx + 1).trim()
        }
        val ctype = headers["content-type"] ?: ""
        val te = (headers["transfer-encoding"] ?: "").lowercase()
        val chunked = te.contains("chunked")
        val cl = headers["content-length"]?.toLongOrNull() ?: -1L
        return V2Response(this, code, ctype, chunked, cl)
    }

    class V2Response internal constructor(
        private val conn: V2HttpConn,
        val code: Int,
        val ctype: String,
        private val chunked: Boolean,
        private val length: Long
    ) {
        private var done = false
        private var cleft = -1L          // -1: need size line
        private var bodyLeft = length    // -1 = unbounded (close-delimited)

        /** semantics of the reference `_V2Body.read` */
        fun read(n: Int = 65536): ByteArray {
            if (done) return ByteArray(0)
            val inp = conn.input ?: run { done = true; return ByteArray(0) }
            return try {
                if (chunked) {
                    if (cleft < 0) {
                        val line = conn.readLine(4096)
                        if (line.eof) { done = true; return ByteArray(0) }
                        var txt = line.bytes.toString(Charsets.ISO_8859_1)
                        val semi = txt.indexOf(';')
                        if (semi >= 0) txt = txt.substring(0, semi)
                        txt = txt.trim { it <= ' ' || it == '\r' }
                        val sz = txt.toLongOrNull(16)
                        if (sz == null) { done = true; return ByteArray(0) }
                        if (sz == 0L) {
                            while (true) {
                                val t = conn.readLine(4096)
                                if (t.eof) break
                                val s = t.bytes.toString(Charsets.ISO_8859_1)
                                if (s.isEmpty() || s == "\r") break
                            }
                            done = true
                            return ByteArray(0)
                        }
                        cleft = sz
                    }
                    val want = java.lang.Math.min(n.toLong(), cleft).toInt()
                    if (want <= 0) { done = true; return ByteArray(0) }
                    val buf = ByteArray(want)
                    val got = inp.read(buf, 0, want)
                    if (got <= 0) { done = true; return ByteArray(0) }
                    cleft -= got
                    if (cleft <= 0) {
                        cleft = -1
                        var left = 2
                        while (left > 0) {
                            val b = inp.read()
                            if (b < 0) { done = true; break }
                            left--
                        }
                    }
                    buf.copyOf(got)
                } else {
                    if (bodyLeft == 0L) { done = true; return ByteArray(0) }
                    val want = if (bodyLeft > 0) {
                        java.lang.Math.min(n.toLong(), bodyLeft).toInt()
                    } else n
                    if (want <= 0) { done = true; return ByteArray(0) }
                    val buf = ByteArray(want)
                    val got = inp.read(buf, 0, want)
                    if (got <= 0) { done = true; return ByteArray(0) }
                    if (bodyLeft > 0) bodyLeft -= got
                    buf.copyOf(got)
                }
            } catch (_: Throwable) {
                done = true
                ByteArray(0)
            }
        }

        fun fullBody(): ByteArray {
            val ba = java.io.ByteArrayOutputStream()
            while (true) {
                val d = read()
                if (d.isEmpty()) break
                ba.write(d)
            }
            return ba.toByteArray()
        }

        fun closeConn() {
            conn.close()
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        input = null
        output = null
    }
}

/** one POST call per fresh connection (mirrors python `_v2_http_post` own-conn flow) */
fun v2HttpPost(
    worker: String, path: String, body: ByteArray, extra: Map<String, String>?,
    cleanIp: String?, timeoutMs: Int
): V2PostRes {
    val conn = V2HttpConn(worker, cleanIp, timeoutMs)
    try {
        conn.connect()
        val resp = conn.request("POST", path, body, extra)
        val data = resp.fullBody()
        val ctype = resp.ctype
        val code = resp.code
        conn.close()
        return V2PostRes(data, ctype, code)
    } catch (t: Throwable) {
        conn.close()
        throw t
    }
}
