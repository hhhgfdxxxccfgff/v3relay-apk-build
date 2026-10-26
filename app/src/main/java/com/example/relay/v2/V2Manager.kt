package com.example.relay.v2

import android.util.Log
import java.security.SecureRandom
import java.util.HashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class V2Ses(
    val sid: String,
    var exp: Long,
    val ver: Int,
    val keysUp: ByteArray,
    val keysDown: ByteArray,
    val nbUp: ByteArray,
    val nbDown: ByteArray,
    var seq: Long,
    val created: Double
)

/** app-side IO for a C2Stream: Socket (SOCKS/HTTP path) or TUN-session adapter */
interface V2AppIo {
    /** write data toward the app */
    fun sendAll(data: ByteArray)
    /** server half-closed: deliver FIN/EOF toward the app; upstream free */
    fun finOut()
    /** hard close of the app side */
    fun close()
    /** upstream read loop (app -> server), runs on its own thread */
    fun pumpRun()
}

class V2Stream internal constructor(
    private val mgr0: V2Manager,
    val stid: Int,
    val host: String,
    val port: Int,
    first: ByteArray
) {
    internal val mgr = mgr0
    @Volatile
    var f = 0L                       // frontier delivered to app (down)
    internal val pc = HashMap<Long, ByteArray>()   // pending pieces (pos -> data)
    @Volatile
    var acked = 0L
    @Volatile
    var upows = first.size.toLong()  // next up position
    internal val sent = java.util.concurrent.ConcurrentSkipListMap<Long, ByteArray>()
    @Volatile
    var complete = true
    @Volatile
    var fin = false
    @Volatile
    var finTold = false
    @Volatile
    var denied = false
    @Volatile
    var dead = false
    internal val done = CountDownLatch(1)
    @Volatile
    var appIo: V2AppIo? = null
    val created = System.currentTimeMillis() / 1000.0

    init {
        if (first.isNotEmpty()) sendUp(0L, first)
    }

    fun sendUp(pos: Long, data: ByteArray) {
        if (data.isEmpty()) return
        sent[pos] = data
        pruneSent()
        mgr.upQueue(0x01, stid, pos, data, true)
    }

    private fun pruneSent() {
        var tot = 0L
        for (v in sent.values) tot += v.size
        while (tot > V2Http.V2_WIN_CAP && sent.isNotEmpty()) {
            val k = sent.firstKey()
            tot -= sent.remove(k)!!.size
            complete = false
        }
    }

    fun onData(posIn: Long, dataIn: ByteArray) {
        if (dead) return
        if (posIn + dataIn.size <= f) return
        var data = dataIn
        if (posIn < f) {
            data = dataIn.copyOfRange((f - posIn).toInt(), dataIn.size)
        }
        if (posIn > f) {
            pc[posIn] = dataIn
            if (pc.size > V2Http.V2_MAX_PIECES) done.countDown()
            return
        }
        f += data.size
        val io = appIo ?: return
        try {
            io.sendAll(data)
        } catch (_: Throwable) {
            appClosed()
            return
        }
        while (true) {
            val nd = pc[f]
            if (nd == null || nd.isEmpty()) break
            pc.remove(f)
            f += nd.size
            try {
                io.sendAll(nd)
            } catch (_: Throwable) {
                appClosed()
                return
            }
        }
        if (fin && !finTold && pc.isEmpty()) {
            finTold = true
            appFin()
            return
        }
        if (f - acked >= V2Http.SYNC_ACK_BYTES) {
            acked = f
            mgr.upQueue(0x02, stid, f, ByteArray(0), false)
        }
    }

    fun onClose(reason: Int) {
        if (dead) return
        if (reason == 1) {
            fin = true
            if (pc.isEmpty() && appIo != null) {
                finTold = true
                appFin()
            }
        } else if (reason == 3) {
            denied = true
            done.countDown()
        } else {
            done.countDown()
        }
    }

    private fun appFin() {
        try {
            appIo?.finOut()
        } catch (_: Throwable) {}
    }

    internal fun appClosed() {
        done.countDown()
    }
}

class V2Manager(
    private val worker: String,
    private val secret: String,
    private val kpub: String,
    private val cleanIp: String?
) {
    private val plock = ReentrantLock()
    private val streams = ConcurrentHashMap<Int, V2Stream>()
    private val rng = SecureRandom()
    private var nextStid = (rng.nextInt(100) + 1).toLong() and 0xFFFFFFFFL
    @Volatile
    private var lastAct = 0.0
    @Volatile
    var stop = false
        private set
    @Volatile
    var covered = false
        private set
    private val ready = AtomicBoolean(false)
    private val forceRenew = AtomicBoolean(false)
    @Volatile
    internal var ses: V2Ses? = null
    @Volatile
    private var lastSid: String? = null
    private val upq = java.util.ArrayDeque<V2Manager.UpItem>()
    private val uevLock = ReentrantLock()
    private val uevCond = uevLock.newCondition()
    @Volatile
    var uevFlag = false
        private set
    private var syncFail = 0
    @Volatile
    private var gen = 0

    class UpItem(val typ: Long, val stid: Int, val pos: Long, val pt: ByteArray, val pad: Boolean)

    init {
        thread(name = "V2Supervisor", isDaemon = true) { supervisorLoop() }
        thread(name = "V2Flusher", isDaemon = true) { flusherLoop() }
        thread(name = "V2Keepalive", isDaemon = true) { keepaliveLoop() }
    }

    private fun now(): Double = System.currentTimeMillis() / 1000.0

    private fun setUev() {
        uevLock.lock()
        try {
            uevFlag = true
            uevCond.signalAll()
        } finally {
            uevLock.unlock()
        }
    }

    private fun waitUev() {
        uevLock.lock()
        try {
            if (!uevFlag) uevCond.await(250, TimeUnit.MILLISECONDS)
            uevFlag = false
        } finally {
            uevLock.unlock()
        }
    }

    // ---------- handshake ----------
    fun handshake(useAdopt: Boolean): Boolean {
        var prev: String? = null
        plock.lock()
        try {
            if (useAdopt) {
                prev = lastSid ?: ses?.sid
            }
        } finally {
            plock.unlock()
        }
        val res = try {
            v2Handshake(worker, secret, kpub, prev, cleanIp)
        } catch (e: Throwable) {
            Log.d(TAG, "hs-err ${e.javaClass.simpleName}")
            null
        }
        return when (res) {
            is V2Ses -> {
                plock.lock()
                try {
                    ses = res
                    lastSid = res.sid
                    lastAct = now()
                } finally {
                    plock.unlock()
                }
                Log.d(TAG, "hs-ok")
                true
            }
            "cover" -> {
                covered = true
                Log.d(TAG, "hs-cover")
                false
            }
            else -> {
                Log.d(TAG, "hs-fail")
                false
            }
        }
    }

    /** returns V2Ses, "cover", or null - direct port of python `v2_handshake` */
    private fun v2Handshake(
        workerUrl: String,
        secret: String,
        kpubHex: String,
        prevSid: String?,
        cleanIp: String?
    ): Any {
        val epriv = V2Crypto.v2xPriv()
        val eRaw = V2Crypto.v2xPub(epriv)
        val dhEs = V2Crypto.v2xDh(epriv, V2Crypto.unhex(kpubHex))
        val skey = secret.toByteArray(Charsets.UTF_8)
        val uid = V2Crypto.v2uid(skey)
        val mac = V2Crypto.v2mac(skey, uid, eRaw)
        val pt = ByteArray(48)
        System.arraycopy(uid, 0, pt, 0, uid.size)
        System.arraycopy(mac, 0, pt, 12, mac.size)
        pt[28] = if (prevSid != null) 2 else 0
        if (prevSid != null) {
            val pv = prevSid.take(12).toByteArray(Charsets.US_ASCII)
            System.arraycopy(pv, 0, pt, 29, pv.size)
        }
        val rec = V2Crypto.v2seal(
            V2Crypto.v2hkdf(dhEs, "devsync-m1".toByteArray(Charsets.US_ASCII), 32),
            ByteArray(12), pt, eRaw
        )
        val body = ByteArray(eRaw.size + rec.size)
        System.arraycopy(eRaw, 0, body, 0, eRaw.size)
        System.arraycopy(rec, 0, body, eRaw.size, rec.size)
        val res = v2HttpPost(workerUrl, "/v1/handshake", body, null, cleanIp, 15000)
        if (res.code != 200) return "fail"
        if (res.ctype.lowercase().startsWith("application/json")) return "cover"
        if (res.data.size < 80) return "fail"
        val ePraw = res.data.copyOfRange(0, 32)
        val dhEe = V2Crypto.v2xDh(epriv, ePraw)
        val t0 = try {
            V2Crypto.v2open(
                V2Crypto.v2hkdf(dhEe, "devsync-m2".toByteArray(Charsets.US_ASCII), 32),
                ByteArray(12),
                res.data.copyOfRange(32, res.data.size),
                V2Crypto.concatBufs(eRaw, ePraw)
            )
        } catch (_: Throwable) {
            return "fail"
        } ?: return "fail"
        val sid = V2Crypto.hex(sliceN(t0, 0, 12))
        val exp = bigEndianBytesToLong(sliceN(t0, 12, 20))
        val ikm = V2Crypto.concatBufs(dhEs, dhEe)
        val ver = if (t0.size > 20) t0[20].toInt() and 0xFF else 0
        return V2Ses(
            sid, exp, ver,
            V2Crypto.v2hkdf(ikm, "devsync-up".toByteArray(Charsets.US_ASCII), 32),
            V2Crypto.v2hkdf(ikm, "devsync-down".toByteArray(Charsets.US_ASCII), 32),
            V2Crypto.v2hkdf(ikm, "devsync-nbup".toByteArray(Charsets.US_ASCII), 12),
            V2Crypto.v2hkdf(ikm, "devsync-nbdown".toByteArray(Charsets.US_ASCII), 12),
            0, now()
        )
    }

    private fun sliceN(b: ByteArray, from: Int, to: Int): ByteArray {
        val a = if (from > b.size) b.size else from
        val c = if (to > b.size) b.size else to
        return if (c <= a) ByteArray(0) else b.copyOfRange(a, c)
    }

    private fun bigEndianBytesToLong(b: ByteArray): Long {
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    // ---------- frame building ----------
    private fun frameWire(typ: Int, stid: Int, pos: Long, ptIn: ByteArray, pad: Boolean): ByteArray? {
        val s = ses ?: return null
        var pt = ptIn
        if (pad && pt.isNotEmpty()) pt = V2Http.bucketPad(pt)
        val hdr = ByteArray(19)
        hdr[0] = (typ and 0xFF).toByte()
        hdr[1] = ((stid ushr 24) and 0xFF).toByte()
        hdr[2] = ((stid ushr 16) and 0xFF).toByte()
        hdr[3] = ((stid ushr 8) and 0xFF).toByte()
        hdr[4] = (stid and 0xFF).toByte()
        var seq: Long
        plock.lock()
        try {
            seq = s.seq and 0xFFFFFFFFL
            s.seq = (s.seq + 1) and 0xFFFFFFFFL
        } finally {
            plock.unlock()
        }
        hdr[5] = ((seq ushr 24) and 0xFF).toByte()
        hdr[6] = ((seq ushr 16) and 0xFF).toByte()
        hdr[7] = ((seq ushr 8) and 0xFF).toByte()
        hdr[8] = (seq and 0xFF).toByte()
        var p = pos
        if (p < 0) p = 0
        for (j in 0 until 8) {
            hdr[9 + j] = ((p ushr (8 * (7 - j))) and 0xFF).toByte()
        }
        hdr[17] = ((pt.size ushr 8) and 0xFF).toByte()
        hdr[18] = (pt.size and 0xFF).toByte()
        val rec = V2Crypto.v2seal(s.keysUp, V2Crypto.v2nonce(s.nbUp, seq), pt, hdr)
        return V2Crypto.concatBufs(hdr, rec)
    }

    fun upQueue(typ: Int, stid: Int, pos: Long, pt: ByteArray = ByteArray(0), pad: Boolean = false) {
        plock.lock()
        try {
            upq.addLast(UpItem(typ.toLong(), stid, pos, pt, pad))
        } finally {
            plock.unlock()
        }
        setUev()
    }

    // ---------- flusher ----------
    private fun flusherLoop() {
        var idle = 0
        while (!stop) {
            try {
                waitUev()
                if (stop) break
                var s = ses
                if (s == null) continue
                val batch = ArrayList<ByteArray>()
                val raws = ArrayList<UpItem>()
                var total = 0
                var noSes = false
                plock.lock()
                try {
                    s = ses
                    if (s == null) {
                        noSes = true
                    } else {
                        while (upq.isNotEmpty()) {
                            val item = upq.peekFirst()!!
                            val w = frameWire(item.typ.toInt(), item.stid, item.pos, item.pt, item.pad)
                            if (w == null) break
                            if (total + w.size > V2Http.V2_SYNC_MAX && batch.isNotEmpty()) break
                            batch.add(w)
                            raws.add(item)
                            total += w.size
                            upq.pollFirst()
                        }
                    }
                } finally {
                    plock.unlock()
                }
                if (noSes) continue
                if (batch.isEmpty()) continue
                var miss = false
                try {
                    val full = concatFrames(batch)
                    val hdr2 = HashMap<String, String>()
                    hdr2["Content-Type"] = "application/octet-stream"
                    hdr2["X-Sid"] = s!!.sid
                    val res = v2HttpPost(worker, "/v1/sync", full, hdr2, cleanIp, 15000)
                    if (res.code == 200 && V2Http.sidEcho(res.data) == s!!.sid) {
                        syncFail = 0
                        lastAct = now()
                    } else {
                        miss = true
                    }
                } catch (_: Throwable) {
                    requeue(raws)
                    syncFail++
                    if (syncFail >= 4) forceRenew.set(true)
                }
                if (miss) {
                    requeue(raws)
                    gen++
                    continue
                }
                if (stop) break
                var backlog = 0
                plock.lock()
                try {
                    backlog = upq.size
                } finally {
                    plock.unlock()
                }
                if (backlog > 0) continue
                val base = when (idle) {
                    0 -> 120L
                    1 -> 50L
                    else -> 30L
                }
                val jitter = (rng.nextDouble() * 180).toLong()
                Thread.sleep(base + jitter)
                idle++
                if (idle > 2) idle = 2
            } catch (_: Throwable) {
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun requeue(raws: List<UpItem>) {
        plock.lock()
        try {
            for (i in raws.indices.reversed()) upq.addFirst(raws[i])
        } finally {
            plock.unlock()
        }
    }

    private fun concatFrames(batch: List<ByteArray>): ByteArray {
        var tot = 0
        for (b in batch) tot += b.size
        val o = ByteArray(tot)
        var p = 0
        for (b in batch) {
            System.arraycopy(b, 0, o, p, b.size)
            p += b.size
        }
        return o
    }

    fun dropStream(stid: Int) {
        val st: V2Stream?
        plock.lock()
        try {
            st = streams.remove(stid)
        } finally {
            plock.unlock()
        }
        if (st != null) {
            st.dead = true
            st.done.countDown()
        }
        upQueue(0x04, stid, 0, byteArrayOf(2), false)
    }

    fun dropStream(st: V2Stream) {
        dropStream(st.stid)
    }

    // ---------- supervisor ----------
    private val backoff = longArrayOf(0, 1000, 2000, 5000, 10000, 15000, 20000, 30000, 45000)

    private fun supervisorLoop() {
        var bi = 0
        while (!stop) {
            try {
                if (!ready.get()) {
                    if (!handshake(false)) {
                        if (covered) break
                        val bo = backoff[if (bi < backoff.size) bi else backoff.size - 1]
                        Thread.sleep(if (bo == 0L) 500 else bo)
                        bi++
                        continue
                    }
                    bi = 0
                }
                var out = streamLoop(false)
                if (out == "fresh") {
                    handleFresh()
                    bi = 0
                    Thread.sleep(250)
                    continue
                }
                if (out == "ok") {
                    Thread.sleep((50 + rng.nextInt(351)).toLong())
                    bi = 0
                    continue
                }
                if (stop) break
                Thread.sleep(220 + rng.nextInt(581).toLong())
                val out2 = streamLoop(true)
                if (out2 == "ok") {
                    bi = 0
                    Thread.sleep((50 + rng.nextInt(351)).toLong())
                    continue
                }
                if (forceRenew.get() || !handshake(true)) {
                    bi++
                    if (forceRenew.get()) {
                        forceRenew.set(false)
                        plock.lock()
                        try { ses = null } finally { plock.unlock() }
                    } else if (covered) {
                        val bo2 = backoff[if (bi < backoff.size) bi else backoff.size - 1]
                        Thread.sleep(bo2)
                    }
                }
            } catch (_: Throwable) {
                Thread.sleep(1000)
            }
        }
    }

    // ---------- stream GET / fresh ----------
    private fun handleFresh() {
        Log.d(TAG, "fresh-entry")
        val stids: List<Int>
        plock.lock()
        try { stids = ArrayList(streams.keys) } finally { plock.unlock() }
        for (stid in stids) {
            val st = streams[stid] ?: continue
            if (st.f > 0 || !st.complete) {
                dropStream(st)
                continue
            }
            val items: List<Pair<Long, ByteArray>>
            synchronized(st) {
                items = st.sent.entries.map { Pair(it.key, it.value) }
                st.sent.clear()
                st.upows = 0
                st.acked = 0
                st.pc.clear()
            }
            val dst = (st.host + ":" + st.port).toByteArray(Charsets.US_ASCII)
            val frame = ByteArray(1 + dst.size)
            frame[0] = dst.size.toByte()
            System.arraycopy(dst, 0, frame, 1, dst.size)
            upQueue(0x03, stid, 0, frame, false)
            for ((_, d) in items) {
                upQueue(0x01, stid, st.upows, d, true)
                synchronized(st) {
                    st.sent[st.upows] = d
                    st.upows += d.size
                }
            }
        }
        plock.lock()
        try { ses = null } finally { plock.unlock() }
    }

    private fun streamLoop(tryClaim: Boolean): String {
        gen++
        val mygen = gen
        val s = ses ?: return "noses"
        val conn = V2HttpConn(worker, cleanIp, V2Http.V2_STREAM_TIMEOUT * 1000)
        val resp: V2HttpConn.V2Response
        try {
            val hdrs = V2Http.coverHeaders()
            hdrs["X-Sid"] = s.sid
            resp = conn.request("GET", "/v1/stream", null, hdrs)
            val ctype = resp.ctype.lowercase()
            if (resp.code != 200) {
                conn.close()
                return "miss"
            }
            if (ctype.startsWith("application/json") || ctype.startsWith("text/")) {
                resp.read()
                try { conn.close() } catch (_: Throwable) {}
                return "miss"
            }
            ready.set(true)
            Log.d(TAG, "stream 200 sid=" + s.sid)
            val stids: List<Int>
            plock.lock()
            try { stids = streams.keys.sorted() } finally { plock.unlock() }
            for (stid in stids) {
                val st = streams[stid] ?: continue
                if (st.dead) continue
                upQueue(0x08, stid, st.f, ByteArray(0), false)
                resendRecent(st)
            }
            lastAct = now()
        } catch (_: Throwable) {
            try { conn.close() } catch (_: Throwable) {}
            return "net"
        }
        var raw = ByteArray(0)
        try {
            while (!stop && gen == mygen) {
                val ch = resp.read(65536)
                if (ch.isEmpty()) {
                    Log.d(TAG, "stream end ch-empty")
                    return "detach"
                }
                raw = raw + ch
                val res = parse(s, raw)
                raw = res.second
                if (res.first == "fresh") return "fresh"
            }
        } catch (t: Throwable) {
            Log.d(TAG, "stream except " + t.javaClass.simpleName)
            return "net"
        } finally {
            try { conn.close() } catch (_: Throwable) {}
        }
        return "detach"
    }

    private fun resendRecent(st: V2Stream) {
        val items: List<Pair<Long, ByteArray>>
        synchronized(st) {
            items = st.sent.entries.map { Pair(it.key, it.value) }
        }
        for ((p, d) in items) {
            upQueue(0x01, st.stid, p, d, true)
        }
    }

    private fun parse(ses: V2Ses, raw: ByteArray): Pair<String, ByteArray> {
        var o = 0
        val n = raw.size
        while (n - o >= 19) {
            val plen = ((raw[o + 17].toInt() and 0xFF) shl 8) or (raw[o + 18].toInt() and 0xFF)
            val tot = 19 + plen + 16
            if (n - o < tot) break
            val hdr = raw.copyOfRange(o, o + 19)
            val rec = raw.copyOfRange(o + 19, o + tot)
            val typ = hdr[0].toInt() and 0xFF
            val stid = ((hdr[1].toInt() and 0xFF) shl 24) or ((hdr[2].toInt() and 0xFF) shl 16) or
                ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[4].toInt() and 0xFF)
            val seq = ((hdr[5].toLong() and 0xFF) shl 24) or ((hdr[6].toLong() and 0xFF) shl 16) or
                ((hdr[7].toLong() and 0xFF) shl 8) or (hdr[8].toLong() and 0xFF)
            var pos = 0L
            for (j in 0 until 8) {
                pos = (pos shl 8) or (hdr[9 + j].toLong() and 0xFF)
            }
            val pt = V2Crypto.v2open(
                ses.keysDown,
                V2Crypto.v2nonce(ses.nbDown, seq),
                rec,
                hdr
            )
            o += tot
            if (pt == null) continue
            when (typ) {
                0x01 -> {
                    var data = ByteArray(0)
                    if (pt.size > 2) {
                        val pl = (pt[0].toInt() and 0xFF) or ((pt[1].toInt() and 0xFF) shl 8)
                        if (2 + pl <= pt.size) {
                            data = pt.copyOfRange(2 + pl, pt.size)
                        }
                    }
                    val st = streams[stid]
                    Log.d(TAG, "data stid=$stid pos=$pos dlen=${data.size}")
                    if (st != null) st.onData(pos, data)
                }
                0x04 -> {
                    val st = streams[stid]
                    val reason = if (pt.isNotEmpty()) pt[0].toInt() and 0xFF else 0
                    if (st != null && stid != 0) st.onClose(reason)
                }
                0x05 -> lastAct = now()
                0x06 -> {
                    try {
                        val text = String(pt, Charsets.ISO_8859_1)
                        val parts = text.split("|")
                        if (parts.size >= 3 && parts[0] == "TK1") {
                            plock.lock()
                            try {
                                val s2 = ses
                                if (s2 != null) {
                                    val e = parts[2].trim().toLongOrNull()
                                    if (e != null) s2.exp = e
                                }
                            } finally {
                                plock.unlock()
                            }
                        }
                    } catch (_: Throwable) {}
                }
                0x09 -> return Pair("fresh", raw.copyOfRange(o, raw.size))
                else -> {}
            }
        }
        return Pair("ok", raw.copyOfRange(o, raw.size))
    }

    // ---------- API ----------
    fun openStream(io: V2AppIo, host: String, port: Int, first: ByteArray): V2Stream {
        var stid = (nextStid and 0xFFFFFFFFL).toInt()
        plock.lock()
        try {
            nextStid = (nextStid + 1) and 0xFFFFFFFFL
            while (streams.containsKey(stid)) {
                stid = (stid + 1) and 0xFFFFFFFF.toInt()
            }
        } finally {
            plock.unlock()
        }
        val st = V2Stream(this, stid, host, port, first)
        st.appIo = io
        plock.lock()
        try {
            streams[stid] = st
        } finally {
            plock.unlock()
        }
        Log.d(TAG, "open stid=$stid $host:$port first=${first.size}")
        val dst = (host + ":" + port).toByteArray(Charsets.US_ASCII)
        val frame = ByteArray(1 + dst.size)
        frame[0] = dst.size.toByte()
        System.arraycopy(dst, 0, frame, 1, dst.size)
        upQueue(0x03, stid, 0, frame, false)
        for (p in st.sent.keys.toSortedSet()) {
            upQueue(0x01, stid, p, st.sent[p]!!, true)
        }
        lastAct = now()
        return st
    }

    private fun keepaliveLoop() {
        while (!stop) {
            try {
                Thread.sleep((40 * 1000L + rng.nextInt(31 * 1000)))
                if (stop) break
                val s = ses
                if (s != null && now() - lastAct > 30) {
                    val n = rng.nextInt(48) + 1
                    val b = ByteArray(n)
                    rng.nextBytes(b)
                    upQueue(0x05, 0, 0, b, false)
                }
            } catch (_: Throwable) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }
    }

    fun stopAll() {
        stop = true
        plock.lock()
        try { ses = null } finally { plock.unlock() }
    }

    companion object {
        private const val TAG = "V2Manager"
    }
}
