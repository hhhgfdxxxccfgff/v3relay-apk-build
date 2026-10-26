package com.example.relay.v2

import java.net.Socket
import kotlin.concurrent.thread

/**
 * Lifecycle equivalent of python `_V2` module state:
 *  - initStartup(): sets init info (worker, secret, kpub, clean_ip)
 *  - dispatch(): tunnel_v2 if a manager exists and is not covered;
 *    otherwise builds the manager ONCE (python `_V2["built"]`), retries, else v1.
 */
object V2Tunnel {

    private val lock = Any()
    @Volatile
    private var workerUrl: String? = null
    @Volatile
    private var secret: String? = null
    @Volatile
    private var kpub: String? = null
    @Volatile
    private var cleanIp: String? = null
    @Volatile
    private var built = false
    @Volatile
    private var mgr: V2Manager? = null

    /** python `_V2["init"] = info` - called at proxy startup */
    fun initStartup(worker: String, sec: String, kuPub: String, cIp: String?) {
        workerUrl = worker
        secret = sec
        kpub = kuPub
        cleanIp = cIp
    }

    fun resetStartup() {
        synchronized(lock) {
            workerUrl = null
            secret = null
            kpub = null
            cleanIp = null
            try { mgr?.stopAll() } catch (_: Throwable) {}
            mgr = null
            built = false
        }
    }

    fun shutdownAll() {
        synchronized(lock) {
            try { mgr?.stopAll() } catch (_: Throwable) {}
        }
    }

    /** manager exists and not covered (python `mgr is not None and not mgr.covered`) */
    fun v2Active(): Boolean {
        val m = mgr ?: return false
        return !m.covered
    }

    fun managerOrNull(): V2Manager? = mgr?.takeIf { !it.covered }

    /** python build_v2_manager() - sets `_V2["built"]` first, returns mgr or null */
    private fun buildV2Manager(): V2Manager? {
        built = true
        val w = workerUrl
        val k = kpub
        if (w == null || k.isNullOrEmpty()) return null
        val m = V2Manager(w, secret ?: "", k, cleanIp)
        mgr = m
        return m
    }

    /** python main(): eager manager build at startup */
    fun buildNow(): V2Manager? {
        synchronized(lock) {
            if (mgr != null) return mgr
            return buildV2Manager()
        }
    }

    /**
     * python tunnel_dispatch: returns true when the v2 path handled the socket,
     * false to fall back to v1 (WSTunnel).
     */
    fun dispatch(
        cli: Socket,
        host: String,
        port: Int,
        workerParam: String,
        secretParam: String,
        cleanIpParam: String?,
        first: ByteArray?
    ): Boolean {
        var m: V2Manager? = mgr
        if (m != null && !m.covered) {
            v2Tunnel(cli, host, port, first, m)
            return true
        }
        if (m == null && !built) {
            synchronized(lock) {
                if (!built) buildV2Manager()
                m = mgr
            }
            if (m != null && !m.covered) {
                v2Tunnel(cli, host, port, first, m)
                return true
            }
        }
        return false
    }

    /** python tunnel_v2 */
    fun v2Tunnel(cli: Socket, host: String, port: Int, firstIn: ByteArray?, manager: V2Manager) {
        val first = firstIn ?: ByteArray(0)
        val io = V2SocketIo(cli)
        val st = manager.openStream(io, host, port, first)
        io.st = st
        if (first.isEmpty()) {
            thread(name = "V2AppPump-" + st.stid, isDaemon = true) { io.pumpRun() }
        }
        try {
            st.done.await()
        } finally {
            manager.dropStream(st)
            try {
                cli.close()
            } catch (_: Throwable) {}
        }
    }
}

/** the app_sock adapter: python `_v2_app_pump` + sendall/shutdown semantics */
class V2SocketIo internal constructor(private val cli: Socket) : V2AppIo {

    internal var st: V2Stream? = null

    override fun sendAll(data: ByteArray) {
        val o = cli.getOutputStream()
        o.write(data)
        o.flush()
    }

    override fun finOut() {
        try {
            cli.shutdownOutput()
        } catch (_: Throwable) {
            try { cli.close() } catch (_: Throwable) {}
        }
    }

    override fun close() {
        try { cli.close() } catch (_: Throwable) {}
    }

    /** python `_v2_app_pump` */
    override fun pumpRun() {
        val s = st ?: return
        try {
            val inp = cli.getInputStream()
            val buf = ByteArray(65000)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                val d = if (n == buf.size) buf else buf.copyOf(n)
                val cur = s.upows
                s.upows = cur + d.size
                s.sendUp(cur, d)
            }
        } catch (_: Throwable) {}
        try {
            s.mgr.upQueue(0x04, s.stid, 0, byteArrayOf(2))
        } catch (_: Throwable) {}
        try {
            cli.shutdownOutput()
        } catch (_: Throwable) {}
    }
}
