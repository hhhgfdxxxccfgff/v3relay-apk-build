package com.example.relay.tunnel

import android.util.Base64
import com.example.relay.crypto.StreamCrypt
import com.example.relay.ws.WSConn
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * v6r: Resumable Tunnel over short-lived WebSocket sessions.
 * Holds client socket open across WS reconnects; streams continue by absolute byte positions.
 */
object WSTunnel {

    interface TunnelCallback {
        fun onConnectionOpened(target: String)
        fun onTraffic(uploadedBytes: Long, downloadedBytes: Long)
        fun onConnectionClosed(target: String, error: String?)
    }

    private const val WIN = 2097152 // 2MB sliding window

    fun buildAuthWire(
        secret: String,
        targetHost: String,
        targetPort: Int,
        nonce: ByteArray,
        resume: Pair<String, Long>?,
        isRestart: Boolean,
        firstChunk: ByteArray = ByteArray(0)
    ): ByteArray {
        val baseKey = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))
        val firstB64 = if (firstChunk.isNotEmpty()) {
            try {
                Base64.encodeToString(firstChunk, Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(firstChunk)
            }
        } else ""
        val marker = if (resume != null) "v6r:${resume.first}:${resume.second}" else "v6r"
        val fb64 = if (resume != null || isRestart) "" else firstB64
        val plain = "$targetHost|$targetPort|$fb64|$marker".toByteArray(Charsets.UTF_8)
        val sc0 = StreamCrypt(baseKey, nonce, "up")
        val ct0 = sc0.crypt(plain)

        val baseAndNonceAndCt = ByteArray(baseKey.size + nonce.size + ct0.size)
        System.arraycopy(baseKey, 0, baseAndNonceAndCt, 0, baseKey.size)
        System.arraycopy(nonce, 0, baseAndNonceAndCt, baseKey.size, nonce.size)
        System.arraycopy(ct0, 0, baseAndNonceAndCt, baseKey.size + nonce.size, ct0.size)

        val tag0 = StreamCrypt.sha256(baseAndNonceAndCt).copyOfRange(0, 16)
        val kid = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)

        val wire = ByteArray(4 + 12 + ct0.size + 16)
        System.arraycopy(kid, 0, wire, 0, 4)
        System.arraycopy(nonce, 0, wire, 4, 12)
        System.arraycopy(ct0, 0, wire, 16, ct0.size)
        System.arraycopy(tag0, 0, wire, 16 + ct0.size, 16)
        return wire
    }

    fun tunnel(
        clientSocket: Socket,
        targetHost: String,
        targetPort: Int,
        workerUrl: String,
        secret: String,
        cleanIp: String?,
        firstChunk: ByteArray = ByteArray(0),
        callback: TunnelCallback? = null
    ) {
        val targetDesc = "$targetHost:$targetPort"
        callback?.onConnectionOpened(targetDesc)

        val baseKey = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))
        val firstB64 = if (firstChunk.isNotEmpty()) {
            try {
                Base64.encodeToString(firstChunk, Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(firstChunk)
            }
        } else ""

        var connId: String? = null
        val sentMap = ConcurrentSkipListMap<Long, ByteArray>()
        if (firstChunk.isNotEmpty()) {
            sentMap[0L] = firstChunk
        }
        val upMax = AtomicLong(firstChunk.size.toLong())
        val downF = AtomicLong(0L)
        val acked = AtomicLong(0L)
        val isComplete = AtomicBoolean(true)
        val replayQueue = mutableListOf<Pair<Long, ByteArray>>()
        val replayLock = Any()

        val done = AtomicBoolean(false)
        val lastError = AtomicReference<String?>(null)
        val random = SecureRandom()

        fun pruneSent() {
            var total = sentMap.values.sumOf { it.size.toLong() }
            while (total > WIN && sentMap.isNotEmpty()) {
                val firstK = sentMap.firstKey()
                val removed = sentMap.remove(firstK)
                if (removed != null) {
                    total -= removed.size
                    isComplete.set(false)
                }
            }
        }

        fun authWire(nonce: ByteArray, resume: Pair<String, Long>?): ByteArray {
            // Mirror the reference: fb64 is suppressed only while a fresh-restart
            // replay is pending (the queued frames carry the request instead).
            val replayPending = synchronized(replayLock) { replayQueue.isNotEmpty() }
            return buildAuthWire(
                secret = secret,
                targetHost = targetHost,
                targetPort = targetPort,
                nonce = nonce,
                resume = resume,
                isRestart = replayPending,
                firstChunk = firstChunk
            )
        }

        fun runSession(resume: Pair<String, Long>?): String {
            val isResume = (resume != null)
            val ws: WSConn
            try {
                ws = WSConn(workerUrl, "/api/live", cleanIp).connect()
            } catch (_: Exception) {
                return "ws-dead"
            }

            val nonce = ByteArray(12)
            random.nextBytes(nonce)
            val kUp = StreamCrypt.hkdf(baseKey, nonce, "v6-up")
            val kDn = StreamCrypt.hkdf(baseKey, nonce, "v6-down")

            val upSeq = AtomicLong(1L)
            var expSeq = 1L
            val inOrderBuf = ConcurrentHashMap<Long, Pair<Long, ByteArray>>()
            val needFresh = AtomicBoolean(false)
            val sessDone = AtomicBoolean(false)
            val sendLock = Any()

            fun sendRaw(pos: Long, data: ByteArray) {
                val ct = StreamCrypt.posCrypt(kUp, nonce, "up", pos, data)
                synchronized(sendLock) {
                    val sq = upSeq.getAndIncrement() and 0xFFFFFFFFL
                    val wire = StreamCrypt.v6Seal(kUp, sq, pos, ct)
                    ws.sendBin(wire)
                }
            }

            val upThread = thread(start = false, isDaemon = true, name = "v6r-up-$targetDesc") {
                val buf = ByteArray(32768)
                try {
                    if (done.get() || clientSocket.isClosed) {
                        done.set(true)
                        return@thread
                    }
                    val clientIn: InputStream = clientSocket.getInputStream()
                    clientSocket.soTimeout = 500
                    while (!done.get() && !sessDone.get()) {
                        // 1. Process pending replay queue
                        var rep: Pair<Long, ByteArray>? = null
                        synchronized(replayLock) {
                            if (replayQueue.isNotEmpty()) {
                                rep = replayQueue.removeAt(0)
                            }
                        }
                        if (rep != null) {
                            try {
                                sendRaw(rep!!.first, rep!!.second)
                                callback?.onTraffic(rep!!.second.size.toLong(), 0L)
                            } catch (_: Exception) {
                                break
                            }
                            continue
                        }

                        // 2. Read from client socket
                        val count: Int
                        try {
                            count = clientIn.read(buf)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }

                        if (count <= 0) {
                            done.set(true)
                            break
                        }

                        val chunk = ByteArray(count)
                        System.arraycopy(buf, 0, chunk, 0, count)

                        val pos = upMax.getAndAdd(count.toLong())
                        sentMap[pos] = chunk
                        pruneSent()

                        try {
                            sendRaw(pos, chunk)
                            callback?.onTraffic(count.toLong(), 0L)
                        } catch (_: Exception) {
                            break
                        }
                    }
                } catch (e: Throwable) {
                    if (!done.get()) lastError.set(e.message)
                } finally {
                    sessDone.set(true)
                    try { ws.close() } catch (_: Throwable) {}
                }
            }

            val downThread = thread(start = false, isDaemon = true, name = "v6r-down-$targetDesc") {
                try {
                    if (done.get() || clientSocket.isClosed) {
                        done.set(true)
                        return@thread
                    }
                    val clientOut: OutputStream = clientSocket.getOutputStream()
                    while (!done.get() && !sessDone.get()) {
                        val (op, payload) = try {
                            ws.recvMsg()
                        } catch (_: Exception) {
                            break
                        }

                        if (op == 0x01) { // JSON Control message
                            try {
                                val ctl = JSONObject(String(payload, Charsets.UTF_8))
                                val t = ctl.optString("t", "")
                                if (t == "id") {
                                    val id = ctl.optString("conn", "")
                                    if (isResume) {
                                        // اگر سشن resume بود و سرور نتونست (پیام id جدید داد):
                                        // فقط وقتی downF == 0 و بافر کامل باشه adopt + replay میکنه، وگرنه فوری fail
                                        if (downF.get() == 0L && isComplete.get()) {
                                            if (id.isNotBlank()) connId = id
                                            for ((pp, dd) in sentMap.entries) {
                                                try {
                                                    sendRaw(pp, dd)
                                                    callback?.onTraffic(dd.size.toLong(), 0L)
                                                } catch (_: Exception) {
                                                    break
                                                }
                                            }
                                        } else {
                                            done.set(true)
                                            lastError.set("Resume rejected by server after downstream data received (downF=${downF.get()})")
                                            sessDone.set(true)
                                            try { ws.close() } catch (_: Exception) {}
                                            break
                                        }
                                    } else {
                                        if (id.isNotBlank()) connId = id
                                    }
                                } else if (t == "ok") {
                                    val upF = ctl.optLong("upF", 0L)
                                    // Rewind: resend buffered bytes from server frontier
                                    for ((pp, dd) in sentMap.entries) {
                                        if (pp + dd.size <= upF) continue
                                        try {
                                            if (pp < upF) {
                                                // trim already-acked prefix before the frontier
                                                val off = (upF - pp).toInt()
                                                sendRaw(upF, dd.copyOfRange(off, dd.size))
                                                callback?.onTraffic((dd.size - off).toLong(), 0L)
                                            } else {
                                                sendRaw(pp, dd)
                                                callback?.onTraffic(dd.size.toLong(), 0L)
                                            }
                                        } catch (_: Exception) {
                                            break
                                        }
                                    }
                                } else if (t == "fresh") {
                                    // گارد {t:'fresh'}: اگر downF > 0 -> ادامه نمیده (restart وسط TLS = کوراپشن)
                                    if (downF.get() > 0L) {
                                        done.set(true)
                                        lastError.set("Cannot restart session: downstream data already received (downF=${downF.get()})")
                                        sessDone.set(true)
                                        try { ws.close() } catch (_: Exception) {}
                                        break
                                    }
                                    needFresh.set(true)
                                    break
                                } else if (t == "deny") {
                                    // سرور سشن را رد کرده (کلید نامعتبر/محدودیت)؛ اتصال را کامل ببند.
                                    val reason = ctl.optString("reason", "").trim()
                                    done.set(true)
                                    lastError.set(
                                        if (reason.isNotBlank()) "Server denied the session: $reason"
                                        else "Server denied the session (deny)"
                                    )
                                    sessDone.set(true)
                                    try { ws.close() } catch (_: Throwable) {}
                                    break
                                }
                            } catch (_: Exception) {}
                            continue
                        }

                        if (op != 0x02 || payload.isEmpty()) continue

                        val packet = StreamCrypt.v6Open(kDn, payload) ?: break
                        inOrderBuf[packet.seq] = Pair(packet.pos, packet.ct)

                        while (inOrderBuf.containsKey(expSeq)) {
                            val (pp0, cc0) = inOrderBuf.remove(expSeq) ?: break
                            expSeq = (expSeq + 1L) and 0xFFFFFFFFL
                            var pp = pp0
                            var cc = cc0
                            val frontier = downF.get()
                            if (pp + cc.size <= frontier) {
                                continue // already delivered (duplicate replay)
                            }
                            if (pp > frontier) {
                                // gap in stream: fail fast, never corrupt the app
                                done.set(true)
                                lastError.set("Downstream gap: expected $frontier, got $pp")
                                break
                            }
                            if (pp < frontier) {
                                cc = cc.copyOfRange((frontier - pp).toInt(), cc.size)
                                pp = frontier
                            }
                            val plain = StreamCrypt.posCrypt(kDn, nonce, "down", pp, cc)
                            try {
                                clientOut.write(plain)
                                clientOut.flush()
                                callback?.onTraffic(0L, plain.size.toLong())
                            } catch (_: Exception) {
                                done.set(true)
                                break
                            }
                            if (pp + cc.size > downF.get()) {
                                downF.set(pp + cc.size)
                            }
                            if (downF.get() - acked.get() >= 262144L) {
                                acked.set(downF.get())
                                try {
                                    sendRaw(downF.get(), ByteArray(0))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (!done.get()) lastError.set(e.message)
                } finally {
                    sessDone.set(true)
                    try { ws.close() } catch (_: Throwable) {}
                }
            }

            // Auth frame must hit the wire before any data frame (mirror reference).
            try {
                ws.sendBin(authWire(nonce, resume))
            } catch (_: Exception) {
                try { ws.close() } catch (_: Exception) {}
                return "ws-dead"
            }
            upThread.start()
            downThread.start()

            while (!sessDone.get() && !done.get()) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                if (needFresh.get()) break
            }

            sessDone.set(true)
            try { upThread.join(3000) } catch (_: Exception) {}
            try { downThread.join(3000) } catch (_: Exception) {}
            try { ws.close() } catch (_: Exception) {}

            return when {
                done.get() -> "app-closed"
                needFresh.get() -> "fresh"
                else -> "ws-dead"
            }
        }

        try {
            clientSocket.tcpNoDelay = true
            val backoffs = longArrayOf(0L, 1000L, 2000L, 5000L, 10000L, 15000L, 20000L)
            var attempt = 0

            while (!done.get()) {
                if (attempt >= backoffs.size) break
                val waitTime = backoffs[attempt]
                if (waitTime > 0) {
                    try {
                        Thread.sleep(waitTime)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                val resume = if (connId != null) Pair(connId!!, downF.get()) else null
                val sessStart = System.currentTimeMillis()
                val res = runSession(resume)

                if (res == "app-closed") break

                if (res == "fresh") {
                    if (downF.get() > 0L || !isComplete.get()) {
                        // Window dropped bytes or downstream data delivered -> fresh replay impossible
                        done.set(true)
                        lastError.set("Fresh restart aborted: downF=${downF.get()}, complete=${isComplete.get()}")
                        break
                    }
                    connId = null
                    downF.set(0L)
                    synchronized(replayLock) {
                        replayQueue.clear()
                        upMax.set(0L)
                        val sortedItems = sentMap.entries.toList()
                        sentMap.clear()
                        for (entry in sortedItems) {
                            val pos = upMax.getAndAdd(entry.value.size.toLong())
                            sentMap[pos] = entry.value
                            replayQueue.add(Pair(pos, entry.value))
                        }
                    }
                    attempt = 0
                    continue
                }

                // ws-dead: retry
                if (System.currentTimeMillis() - sessStart > 30000) {
                    attempt = 0
                } else {
                    attempt++
                }
            }
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            callback?.onConnectionClosed(targetDesc, lastError.get())
        }
    }
}
