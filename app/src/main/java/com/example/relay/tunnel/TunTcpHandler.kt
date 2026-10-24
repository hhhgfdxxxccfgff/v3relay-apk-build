package com.example.relay.tunnel

import com.example.relay.crypto.StreamCrypt
import com.example.relay.data.LogType
import com.example.relay.proxy.ProxyServerManager
import com.example.relay.ws.WSConn
import org.json.JSONObject
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class TunTcpHandler(
    private val workerUrl: String,
    private val secret: String,
    private val cleanIp: String?,
    private val outStream: FileOutputStream
) {
    companion object {
        private const val MAX_ACTIVE_SESSIONS = 16
    }

    private class TunTcpSession(
        val clientIp: ByteArray,
        val clientPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
        var mySeq: Long,
        var expectedClientSeq: Long
    ) {
        val isClosed = AtomicBoolean(false)
        val outgoingQueue = LinkedBlockingQueue<ByteArray>(64)
        var wsConn: WSConn? = null
        var lastActivityTime = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, TunTcpSession>()
    private val workerCounter = AtomicLong(1L)
    private val sessionExecutor = Executors.newCachedThreadPool { r ->
        Thread(r).apply {
            isDaemon = true
            name = "TunTcpWorker-${workerCounter.getAndIncrement()}"
        }
    }
    private val isHandlerClosed = AtomicBoolean(false)
    private val random = SecureRandom()
    private val baseKey = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))

    fun handleTcpPacket(packet: ByteArray, length: Int) {
        if (isHandlerClosed.get() || length < 40) return

        try {
            val srcIp = packet.copyOfRange(12, 16)
            val dstIp = packet.copyOfRange(16, 20)
            val srcPort = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)
            val dstPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)

            // Ignore loopback or local proxy ports
            if (dstIp[0] == 127.toByte() || srcIp[0] == 127.toByte()) return

            val seq = ((packet[24].toLong() and 0xFF) shl 24) or
                    ((packet[25].toLong() and 0xFF) shl 16) or
                    ((packet[26].toLong() and 0xFF) shl 8) or
                    (packet[27].toLong() and 0xFF)

            val tcpDataOffset = ((packet[32].toInt() and 0xF0) ushr 4) * 4
            if (tcpDataOffset < 20 || 20 + tcpDataOffset > length) return

            val flags = packet[33].toInt() and 0xFF
            val isSyn = (flags and 0x02) != 0
            val isAck = (flags and 0x10) != 0
            val isFin = (flags and 0x01) != 0
            val isRst = (flags and 0x04) != 0

            val payloadOffset = 20 + tcpDataOffset
            val payloadLen = length - payloadOffset

            val sessionKey = "${TunPacketUtils.ipToString(srcIp)}:$srcPort->${TunPacketUtils.ipToString(dstIp)}:$dstPort"

            if (isRst) {
                val session = sessions.remove(sessionKey)
                session?.isClosed?.set(true)
                try { session?.wsConn?.close() } catch (_: Throwable) {}
                return
            }

            if (isSyn && !isAck) {
                // Prune old sessions if limit reached
                pruneOldSessions()

                if (sessions.size >= MAX_ACTIVE_SESSIONS) {
                    // Send RST to reject extra background connection gracefully
                    sendRstPacket(srcIp, dstIp, srcPort, dstPort, seq)
                    return
                }

                val myInitialSeq = (100000L..900000L).random()
                val newSession = TunTcpSession(
                    clientIp = srcIp,
                    clientPort = srcPort,
                    dstIp = dstIp,
                    dstPort = dstPort,
                    mySeq = (myInitialSeq + 1) and 0xFFFFFFFFL,
                    expectedClientSeq = (seq + 1) and 0xFFFFFFFFL
                )
                sessions[sessionKey] = newSession

                val synAck = TunPacketUtils.createTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = myInitialSeq,
                    ack = newSession.expectedClientSeq,
                    flags = 0x12 // SYN | ACK
                )
                synchronized(outStream) {
                    try {
                        outStream.write(synAck)
                        outStream.flush()
                    } catch (_: Throwable) {}
                }

                val dstHost = TunPacketUtils.ipToString(dstIp)
                startSessionWorker(sessionKey, newSession, dstHost, dstPort)
                return
            }

            val session = sessions[sessionKey] ?: return
            session.lastActivityTime = System.currentTimeMillis()

            if (isFin) {
                session.isClosed.set(true)
                sessions.remove(sessionKey)
                if (isAck) {
                    val finAck = TunPacketUtils.createTcpPacket(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        seq = session.mySeq,
                        ack = (seq + 1) and 0xFFFFFFFFL,
                        flags = 0x11
                    )
                    synchronized(outStream) {
                        try {
                            outStream.write(finAck)
                            outStream.flush()
                        } catch (_: Throwable) {}
                    }
                }
                try { session.wsConn?.close() } catch (_: Throwable) {}
                return
            }

            if (payloadLen > 0 && payloadOffset + payloadLen <= length) {
                val payload = ByteArray(payloadLen)
                System.arraycopy(packet, payloadOffset, payload, 0, payloadLen)

                session.expectedClientSeq = (seq + payloadLen) and 0xFFFFFFFFL

                // Send immediate ACK back to client kernel
                val ackPacket = TunPacketUtils.createTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seq = session.mySeq,
                    ack = session.expectedClientSeq,
                    flags = 0x10
                )
                synchronized(outStream) {
                    try {
                        outStream.write(ackPacket)
                        outStream.flush()
                    } catch (_: Throwable) {}
                }

                // Queue payload safely
                session.outgoingQueue.offer(payload)
            }
        } catch (_: Throwable) {
            // Never crash TUN thread on corrupted packets
        }
    }

    private fun sendRstPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, seq: Long) {
        try {
            val rst = TunPacketUtils.createTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seq = 0L,
                ack = (seq + 1) and 0xFFFFFFFFL,
                flags = 0x14 // RST | ACK
            )
            synchronized(outStream) {
                outStream.write(rst)
                outStream.flush()
            }
        } catch (_: Throwable) {}
    }

    private fun pruneOldSessions() {
        val now = System.currentTimeMillis()
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isClosed.get() || now - entry.value.lastActivityTime > 25000) {
                entry.value.isClosed.set(true)
                try { entry.value.wsConn?.close() } catch (_: Throwable) {}
                it.remove()
            }
        }
    }

    private fun startSessionWorker(
        sessionKey: String,
        session: TunTcpSession,
        dstHost: String,
        dstPort: Int
    ) {
        if (isHandlerClosed.get()) return

        try {
            sessionExecutor.submit {
                var ws: WSConn? = null
                var senderThread: Thread? = null

                try {
                    ProxyServerManager.addLog("تونل TCP مستقیم: $dstHost:$dstPort", LogType.TRAFFIC)

                    ws = WSConn(
                        workerUrl = workerUrl,
                        path = "/api/live",
                        cleanIp = cleanIp,
                        timeoutMs = 8000
                    ).connect()
                    session.wsConn = ws

                    val nonce = ByteArray(12)
                    random.nextBytes(nonce)
                    val kUp = StreamCrypt.hkdf(baseKey, nonce, "v6-up")
                    val kDn = StreamCrypt.hkdf(baseKey, nonce, "v6-down")

                    // v6r wire auth
                    val plainAuth = "$dstHost|$dstPort||v6r".toByteArray(Charsets.UTF_8)
                    val sc0 = StreamCrypt(baseKey, nonce, "up")
                    val ct0 = sc0.crypt(plainAuth)

                    val baseAndNonceAndCt = ByteArray(baseKey.size + nonce.size + ct0.size)
                    System.arraycopy(baseKey, 0, baseAndNonceAndCt, 0, baseKey.size)
                    System.arraycopy(nonce, 0, baseAndNonceAndCt, baseKey.size, nonce.size)
                    System.arraycopy(ct0, 0, baseAndNonceAndCt, baseKey.size + nonce.size, ct0.size)

                    val tag0 = StreamCrypt.sha256(baseAndNonceAndCt).copyOfRange(0, 16)
                    val kid = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)

                    val authWire = ByteArray(4 + 12 + ct0.size + 16)
                    System.arraycopy(kid, 0, authWire, 0, 4)
                    System.arraycopy(nonce, 0, authWire, 4, 12)
                    System.arraycopy(ct0, 0, authWire, 16, ct0.size)
                    System.arraycopy(tag0, 0, authWire, 16 + ct0.size, 16)

                    // Send wire auth
                    ws.sendBin(authWire)

                    val upSeq = AtomicLong(1L)
                    val upPos = AtomicLong(0L)

                    // Dedicated sender thread (avoids nested pool starvation)
                    senderThread = thread(start = true, isDaemon = true, name = "TunSender-$sessionKey") {
                        try {
                            while (!session.isClosed.get() && !isHandlerClosed.get()) {
                                val data = session.outgoingQueue.poll(400, TimeUnit.MILLISECONDS)
                                if (data != null && data.isNotEmpty()) {
                                    val pos = upPos.getAndAdd(data.size.toLong())
                                    val sq = upSeq.getAndIncrement() and 0xFFFFFFFFL
                                    val ct = StreamCrypt.posCrypt(kUp, nonce, "up", pos, data)
                                    val wire = StreamCrypt.v6Seal(kUp, sq, pos, ct)
                                    ws.sendBin(wire)
                                    session.lastActivityTime = System.currentTimeMillis()
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    // Receiver loop (worker -> client)
                    while (!session.isClosed.get() && !isHandlerClosed.get()) {
                        val (op, payload) = ws.recvMsg()
                        if (op == 0x01) {
                            // Control frame: "deny" means the server rejected this raw session.
                            val denyReason = try {
                                val ctl = JSONObject(String(payload, Charsets.UTF_8))
                                if (ctl.optString("t", "") == "deny") ctl.optString("reason", "").trim() else null
                            } catch (_: Throwable) {
                                null
                            }
                            if (denyReason != null) {
                                ProxyServerManager.addLog(
                                    if (denyReason.isNotBlank()) "سرور اتصال را رد کرد: $denyReason"
                                    else "سرور اتصال را رد کرد (deny)",
                                    LogType.ERROR
                                )
                                session.isClosed.set(true)
                                break
                            }
                            continue // other control frames are ignored
                        }
                        if (op != 0x02 || payload.isEmpty()) continue

                        val packet = StreamCrypt.v6Open(kDn, payload) ?: break
                        val plain = StreamCrypt.posCrypt(kDn, nonce, "down", packet.pos, packet.ct)
                        session.lastActivityTime = System.currentTimeMillis()

                        // Chunk into MTU-safe chunks (max 1360 bytes each)
                        var offset = 0
                        while (offset < plain.size && !session.isClosed.get()) {
                            val chunkSize = minOf(1360, plain.size - offset)
                            val chunk = ByteArray(chunkSize)
                            System.arraycopy(plain, offset, chunk, 0, chunkSize)

                            val dataPacket = TunPacketUtils.createTcpPacket(
                                srcIp = session.dstIp,
                                dstIp = session.clientIp,
                                srcPort = session.dstPort,
                                dstPort = session.clientPort,
                                seq = session.mySeq,
                                ack = session.expectedClientSeq,
                                flags = 0x18, // PSH | ACK
                                payload = chunk
                            )

                            session.mySeq = (session.mySeq + chunkSize) and 0xFFFFFFFFL

                            synchronized(outStream) {
                                outStream.write(dataPacket)
                                outStream.flush()
                            }

                            offset += chunkSize
                        }
                    }

                } catch (_: Throwable) {
                    // Handled gracefully
                } finally {
                    session.isClosed.set(true)
                    sessions.remove(sessionKey)
                    try { senderThread?.interrupt() } catch (_: Throwable) {}
                    try { ws?.close() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            // Rejected execution or shutdown handled safely
        }
    }

    fun close() {
        isHandlerClosed.set(true)
        for ((_, session) in sessions) {
            session.isClosed.set(true)
            try { session.wsConn?.close() } catch (_: Throwable) {}
        }
        sessions.clear()
        try {
            sessionExecutor.shutdownNow()
        } catch (_: Throwable) {}
    }
}
