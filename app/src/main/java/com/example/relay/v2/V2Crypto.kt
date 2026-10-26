package com.example.relay.v2

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v2 devsync crypto == exact port of client_ws.py blake2s/chacha20/poly1305/x25519 helpers.
 * All little-endian word semantics, constants and counter rules match the reference.
 */
object V2Crypto {

    private val IV = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt()
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
    )

    // ---------- hashlib-mirror: blake2s(m, key=32B max) ----------
    fun blake2s(data: ByteArray, key: ByteArray = ByteArray(0)): ByteArray {
        val h = IV.copyOf()
        val k = key.size
        h[0] = h[0] xor ((32 or (k shl 8) or (1 shl 16) or (1 shl 24)))
        val src = if (k > 0) {
            val kb = key.copyOf(64)          // zero-pad key to block size
            val d = ByteArray(64 + data.size)
            System.arraycopy(kb, 0, d, 0, 64)
            System.arraycopy(data, 0, d, 64, data.size)
            d
        } else data
        val n = src.size
        if (n == 0) {
            compress(h, ByteArray(64), 0, true)
            return dump(h)
        }
        val full = n / 64
        val rem = n - full * 64
        var t = 0L
        for (i in 0 until full) {
            t += 64L
            val blk = src.copyOfRange(i * 64, i * 64 + 64)
            compress(h, blk, t, rem == 0 && i == full - 1)
        }
        if (rem > 0L) {
            val blk = ByteArray(64)
            System.arraycopy(src, full * 64, blk, 0, rem)
            t += rem
            compress(h, blk, t, true)
        }
        return dump(h)
    }

    private fun compress(h: IntArray, blk: ByteArray, t: Long, last: Boolean) {
        val m = IntArray(16)
        for (i in 0 until 16) {
            val o = i * 4
            m[i] = (blk[o].toInt() and 0xFF) or
                ((blk[o + 1].toInt() and 0xFF) shl 8) or
                ((blk[o + 2].toInt() and 0xFF) shl 16) or
                ((blk[o + 3].toInt() and 0xFF) shl 24)
        }
        val v = IntArray(16)
        IV.copyInto(v)
        h.copyInto(v, 0, 0, 8)
        v[12] = v[12] xor t.toInt()
        v[13] = v[13] xor ((t shr 32).toInt())
        if (last) v[14] = v[14] xor -1
        for (r in 0 until 10) {
            val s = SIGMA[r]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        v[a] += v[b] + x
        v[d] = v[d] xor v[a]; v[d] = v[d].rotateRight(16)
        v[c] += v[d]
        v[b] = v[b] xor v[c]; v[b] = v[b].rotateRight(12)
        v[a] += v[b] + y
        v[d] = v[d] xor v[a]; v[d] = v[d].rotateRight(8)
        v[c] += v[d]
        v[b] = v[b] xor v[c]; v[b] = v[b].rotateRight(7)
    }

    private fun dump(h: IntArray): ByteArray {
        val o = ByteArray(32)
        for (i in 0 until 8) putLe32(o, i * 4, h[i])
        return o
    }

    private fun putLe32(b: ByteArray, o: Int, w: Int) {
        b[o] = (w and 0xFF).toByte()
        b[o + 1] = ((w shr 8) and 0xFF).toByte()
        b[o + 2] = ((w shr 16) and 0xFF).toByte()
        b[o + 3] = ((w shr 24) and 0xFF).toByte()
    }

    // ---------- b2s / b2hmac ----------
    fun b2s(vararg bufs: ByteArray): ByteArray = blake2s(concat(bufs))

    fun b2hmac(key: ByteArray, vararg bufs: ByteArray): ByteArray {
        val kk = if (key.size > 64) blake2s(key) else key
        val kpad = kk.copyOf(64)
        val innerKey = ByteArray(64); val outerKey = ByteArray(64)
        var i = 0
        while (i < 64) {
            val c = if (i < kpad.size) kpad[i].toInt() else 0
            innerKey[i] = (c xor 0x36).toByte()
            outerKey[i] = (c xor 0x5c).toByte()
            i++
        }
        val inner = blake2s(concat(arrayOf(innerKey, concat(bufs))), ByteArray(0))
        return blake2s(concat(arrayOf(outerKey, inner)), ByteArray(0))
    }

    fun concatBufs(vararg bs: ByteArray): ByteArray = concat(bs)

    fun v2hkdf(ikm: ByteArray, info: ByteArray, ln: Int): ByteArray {
        val prk = b2hmac(ByteArray(32), ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0); var ctr = 1
        while (out.size() < ln) {
            val msg = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, msg, 0, t.size)
            System.arraycopy(info, 0, msg, t.size, info.size)
            msg[msg.size - 1] = ctr.toByte()
            t = b2hmac(prk, msg)
            out.write(t); ctr++
        }
        return out.toByteArray().copyOf(ln)
    }

    // ---------- md helpers ----------
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun v2uid(secret: ByteArray): ByteArray = sha256(secret).copyOf(12)

    fun v2mac(secret: ByteArray, uid: ByteArray, e: ByteArray): ByteArray =
        b2hmac(secret, "devsync-auth-v1".toByteArray(Charsets.US_ASCII), uid, e).copyOf(16)

    fun v2nonce(base: ByteArray, ctr: Long): ByteArray {
        val n = base.copyOf()
        val b = ctr
        for (j in 0 until 8) {
            val shift = 56 - 8 * j
            val cntByte = ((b shr shift) and 0xFF).toInt()
            n[4 + j] = (n[4 + j].toInt() xor cntByte).toByte()
        }
        return n
    }

    // ---------- chacha20 block / keystream ----------
    fun chachaBlock(key: ByteArray, counter: Long, nonce: ByteArray): ByteArray {
        val kw = IntArray(8)
        for (i in 0 until 8) kw[i] = le32(key, i * 4)
        val nw = IntArray(3)
        for (i in 0 until 3) nw[i] = le32(nonce, i * 4)
        val st = intArrayOf(
            0x61707865, 0x3320646E.toInt(), 0x79622D32.toInt(), 0x6B206574.toInt(),
            kw[0], kw[1], kw[2], kw[3], kw[4], kw[5], kw[6], kw[7],
            (counter and 0xFFFFFFFFL).toInt(), nw[0], nw[1], nw[2]
        )
        val x = st.copyOf()
        repeat(10) {
            chachaQr(x, 0, 4, 8, 12); chachaQr(x, 1, 5, 9, 13)
            chachaQr(x, 2, 6, 10, 14); chachaQr(x, 3, 7, 11, 15)
            chachaQr(x, 0, 5, 10, 15); chachaQr(x, 1, 6, 11, 12)
            chachaQr(x, 2, 7, 8, 13); chachaQr(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) putLe32(out, i * 4, x[i] + st[i])
        return out
    }

    private fun chachaQr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = x[d] xor x[a]; x[d] = x[d].rotateLeft(16)
        x[c] += x[d]; x[b] = x[b] xor x[c]; x[b] = x[b].rotateLeft(12)
        x[a] += x[b]; x[d] = x[d] xor x[a]; x[d] = x[d].rotateLeft(8)
        x[c] += x[d]; x[b] = x[b] xor x[c]; x[b] = x[b].rotateLeft(7)
    }

    fun chachaKs(key: ByteArray, nonce: ByteArray, start: Long, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var left = n
        var pos = (start shr 6).toInt()
        var sk = (start and 63L).toInt()
        while (left > 0) {
            val blk = chachaBlock(key, pos.toLong(), nonce).copyOfRange(sk, 64)
            if (blk.size >= left) {
                out.write(blk, 0, left); break
            }
            out.write(blk)
            left -= blk.size
            pos++
            sk = 0
        }
        return out.toByteArray()
    }

    // ---------- poly1305 (pure, BigInteger) ----------
    private val POLY_P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val POLY_MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
    private val POLY_RMASK = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)

    fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val r = BigInteger(1, key.copyOfRange(0, 16)).and(POLY_RMASK)
        val s = BigInteger(1, key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var i = 0
        while (i < data.size) {
            val blk = data.copyOfRange(i, minOf(i + 16, data.size))
            val padded = ByteArray(17)
            System.arraycopy(blk, 0, padded, 0, blk.size)
            padded[blk.size] = 1
            val num = BigInteger(1, padded)
            acc = acc.add(num).multiply(r).mod(POLY_P)
            i += 16
        }
        return acc.add(s).and(POLY_MASK128).toByteArrayLe()
    }

    private fun BigInteger.toByteArrayLe(): ByteArray {
        val be = toByteArray()          // big-endian, may have leading zero sign byte
        val hit = if (be.size > 1 && be[0].toInt() == 0) be.copyOfRange(1, be.size) else be
        val out = ByteArray(16)
        var j = hit.size - 1; var o = 0
        while (j >= 0 && o < 16) { out[o] = hit[j]; j--; o++ }
        return out
    }

    // ---------- AEAD pure seal/open ----------
    private fun pad16(x: ByteArray): ByteArray {
        val r = x.size % 16
        if (r == 0) return ByteArray(0)
        return ByteArray(16 - r)
    }

    fun aeadMacData(aad: ByteArray, ct: ByteArray): ByteArray {
        val md = pad16(aad)
        val mc = pad16(ct)
        val out = ByteArray(aad.size + md.size + ct.size + mc.size + 16)
        var o = 0
        aad.copyInto(out, o); o += aad.size
        md.copyInto(out, o); o += md.size
        ct.copyInto(out, o); o += ct.size
        mc.copyInto(out, o); o += mc.size
        putLe64(out, o, aad.size.toLong()); o += 8
        putLe64(out, o, ct.size.toLong())
        return out
    }

    fun pureSeal(key: ByteArray, nonce: ByteArray, pt: ByteArray, aad: ByteArray): ByteArray {
        val pol = chachaBlock(key, 0, nonce).copyOfRange(0, 32)
        val ct = if (pt.isEmpty()) ByteArray(0)
        else {
            val ks = chachaKs(key, nonce, 64, pt.size)
            ByteArray(pt.size) { j -> (pt[j].toInt() xor ks[j].toInt()).toByte() }
        }
        val macData = aeadMacData(aad, ct)
        val tag = poly1305(pol, macData)
        val out = ByteArray(ct.size + 16)
        ct.copyInto(out)
        tag.copyInto(out, ct.size)
        return out
    }

    fun pureOpen(key: ByteArray, nonce: ByteArray, rec: ByteArray, aad: ByteArray): ByteArray? {
        if (rec.size < 16) return null
        val ct = rec.copyOfRange(0, rec.size - 16)
        val tag = rec.copyOfRange(rec.size - 16, rec.size)
        val pol = chachaBlock(key, 0, nonce).copyOfRange(0, 32)
        val macData = aeadMacData(aad, ct)
        if (!constEq(poly1305(pol, macData), tag)) return null
        if (ct.isEmpty()) return ByteArray(0)
        val ks = chachaKs(key, nonce, 64, ct.size)
        return ByteArray(ct.size) { j -> (ct[j].toInt() xor ks[j].toInt()).toByte() }
    }

    private fun constEq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    // ---------- seal/open dispatch: native on API 28+, pure fallback ----------
    private val nativeBroken = AtomicBoolean(false)

    fun v2seal(key: ByteArray, nonce: ByteArray, pt: ByteArray, aad: ByteArray): ByteArray {
        if (isAndroid28Plus() && !nativeBroken.get()) {
            try {
                val c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"),
                    ChaCha20ParameterSpec(nonce, 1))
                if (aad.isNotEmpty()) c.updateAAD(aad)
                return c.doFinal(pt)
            } catch (t: Throwable) {
                nativeBroken.set(true)
            }
        }
        return pureSeal(key, nonce, pt, aad)
    }

    fun v2open(key: ByteArray, nonce: ByteArray, rec: ByteArray, aad: ByteArray): ByteArray? {
        if (isAndroid28Plus() && !nativeBroken.get()) {
            try {
                val c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"),
                    ChaCha20ParameterSpec(nonce, 1))
                if (aad.isNotEmpty()) c.updateAAD(aad)
                return c.doFinal(rec)
            } catch (t: Throwable) {
                return null
            }
        }
        return pureOpen(key, nonce, rec, aad)
    }

    private fun isAndroid28Plus(): Boolean {
        return try {
            Class.forName("android.os.Build\$VERSION").getField("SDK_INT")
                .getInt(null) >= 28
        } catch (t: Throwable) {
            false
        }
    }

    // ---------- x25519 (pure, BigInteger ladder - RFC 7748) ----------
    private val XP = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    fun x25519(kBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val k = kBytes.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kk = BigInteger(1, k)
        val u = uBytes.copyOf()
        u[31] = (u[31].toInt() and 127).toByte()
        val x1 = BigInteger(1, u)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        // cswap(s, a, b): s is always 0 or 1; a^(s*(a^b)) == a if s==1 else b
        fun cswap(s: Int, a: BigInteger, b: BigInteger): Pair<BigInteger, BigInteger> {
            val d = if (s == 1) a.xor(b) else BigInteger.ZERO
            return Pair(a.xor(d), b.xor(d))
        }
        for (t in 254 downTo 0) {
            val kT = kk.shiftRight(t).and(BigInteger.ONE).intValueExact()
            swap = swap xor kT
            var p = cswap(swap, x2, x3); x2 = p.first; x3 = p.second
            p = cswap(swap, z2, z3); z2 = p.first; z3 = p.second
            swap = kT
            val Aa = x2.add(z2).mod(XP)
            val AA = Aa.multiply(Aa).mod(XP)
            val Bb = x2.subtract(z2).mod(XP)
            val BB = Bb.multiply(Bb).mod(XP)
            val E = AA.subtract(BB).mod(XP)
            val Cc = x3.add(z3).mod(XP)
            val Dd = x3.subtract(z3).mod(XP)
            val DA = Dd.multiply(Aa).mod(XP)
            val CB = Cc.multiply(Bb).mod(XP)
            x3 = DA.add(CB).mod(XP).pow(2).mod(XP)
            x2 = AA.multiply(BB).mod(XP)
            z3 = x1.multiply(DA.subtract(CB).mod(XP).pow(2)).mod(XP)
            z2 = E.multiply(AA.add(BigInteger.valueOf(121665L).multiply(E).mod(XP))).mod(XP)
        }
        var p = cswap(swap, x2, x3); x2 = p.first; x3 = p.second
        p = cswap(swap, z2, z3); z2 = p.first; z3 = p.second
        val zinv = z2.modPow(XP.subtract(BigInteger.TWO), XP)
        return x2.multiply(zinv).mod(XP).toLe32()
    }

    private fun BigInteger.toLe32(): ByteArray {
        val be = toByteArray()
        val hit = if (be.size > 1 && be[0].toInt() == 0) be.copyOfRange(1, be.size) else be
        val out = ByteArray(32)
        var j = hit.size - 1; var o = 0
        while (j >= 0 && o < 32) { out[o] = hit[j]; j--; o++ }
        return out
    }

    fun v2xPriv(): ByteArray {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        b[0] = (b[0].toInt() and 248).toByte()
        b[31] = ((b[31].toInt() and 127) or 64).toByte()
        return b
    }

    fun v2xPub(privRaw: ByteArray): ByteArray =
        x25519(privRaw, ByteArray(32).also { it[0] = 9 })

    fun v2xDh(privRaw: ByteArray, pubRaw: ByteArray): ByteArray = x25519(privRaw, pubRaw)

    // ---------- utils ----------
    fun hex(b: ByteArray): String = buildString(b.size * 2) {
        for (c in b) {
            val v = c.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }

    fun unhex(s: String): ByteArray {
        val cl = s.length / 2
        val out = ByteArray(cl)
        for (i in 0 until cl) {
            out[i] = ((hexNibble(s[i * 2]) shl 4) or hexNibble(s[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex: $c")
    }

    private fun concat(arrs: Array<out ByteArray>): ByteArray {
        var tot = 0
        for (a in arrs) tot += a.size
        val o = ByteArray(tot)
        var p = 0
        for (a in arrs) { a.copyInto(o, p); p += a.size }
        return o
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putLe64(b: ByteArray, o: Int, v: Long) {
        for (j in 0 until 8) b[o + j] = ((v shr (8 * j)) and 0xFF).toByte()
    }
}
