package com.example.relay.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class V6Packet(
    val seq: Long,
    val pos: Long,
    val ct: ByteArray
)

/**
 * Custom stream cipher and v6r protocol crypto matching the python v3 relay implementation:
 * - SHA-256 keystream blocks: (base + nonce + dirb + idx)
 * - Positional crypting: pos_crypt
 * - HKDF key expansion: hkdf
 * - Packet framing and integrity: v6_seal / v6_open
 */
class StreamCrypt(
    private val base: ByteArray,
    private val nonce: ByteArray,
    direction: String
) {
    private val dirb: ByteArray = dirPad(direction)
    private var pos: Long = 0L

    companion object {
        fun sha256(data: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(data)
        }

        fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        fun dirPad(direction: String): ByteArray {
            val raw = direction.toByteArray(Charsets.UTF_8)
            val result = ByteArray(4)
            val len = minOf(raw.size, 4)
            System.arraycopy(raw, 0, result, 0, len)
            return result
        }

        fun ksAt(base: ByteArray, nonce: ByteArray, dirb: ByteArray, idx: Int): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(base)
            md.update(nonce)
            md.update(dirb)
            val idxBytes = byteArrayOf(
                ((idx ushr 24) and 0xFF).toByte(),
                ((idx ushr 16) and 0xFF).toByte(),
                ((idx ushr 8) and 0xFF).toByte(),
                (idx and 0xFF).toByte()
            )
            md.update(idxBytes)
            return md.digest()
        }

        /**
         * HKDF-SHA256 matching the python implementation:
         * prk = HMAC(nonce, base)
         * t = HMAC(prk, info + 0x01)
         * returns 32 bytes
         */
        fun hkdf(base: ByteArray, nonce: ByteArray, info: String): ByteArray {
            val prk = hmacSha256(nonce, base)
            val ib = info.toByteArray(Charsets.UTF_8)
            val stepInput = ByteArray(ib.size + 1)
            System.arraycopy(ib, 0, stepInput, 0, ib.size)
            stepInput[ib.size] = 1.toByte()
            val t = hmacSha256(prk, stepInput)
            return t.copyOf(32)
        }

        /**
         * Split-independent stream decrypt/encrypt at absolute byte position.
         */
        fun posCrypt(key: ByteArray, nonce: ByteArray, direction: String, pos: Long, data: ByteArray): ByteArray {
            val n = data.size
            if (n == 0) return ByteArray(0)
            val dirb = dirPad(direction)
            val firstBi = (pos shr 5).toInt()
            val lastBi = ((pos + n - 1) shr 5).toInt()
            val numBlocks = lastBi - firstBi + 1
            val ksCombined = ByteArray(numBlocks * 32)
            for (bi in firstBi..lastBi) {
                val block = ksAt(key, nonce, dirb, bi)
                val destOffset = (bi - firstBi) * 32
                System.arraycopy(block, 0, ksCombined, destOffset, 32)
            }
            val off = (pos and 31).toInt()
            val result = ByteArray(n)
            for (i in 0 until n) {
                result[i] = (data[i].toInt() xor ksCombined[off + i].toInt()).toByte()
            }
            return result
        }

        /**
         * v6_seal: h = seq(4) + pos(8); tag = HMAC(k, h + crypted)[:16]
         * wire = h + crypted + tag
         */
        fun v6Seal(k: ByteArray, seq: Long, pos: Long, crypted: ByteArray): ByteArray {
            val h = ByteArray(12)
            // seq (4 bytes big-endian)
            h[0] = ((seq ushr 24) and 0xFF).toByte()
            h[1] = ((seq ushr 16) and 0xFF).toByte()
            h[2] = ((seq ushr 8) and 0xFF).toByte()
            h[3] = (seq and 0xFF).toByte()
            // pos (8 bytes big-endian)
            h[4] = ((pos ushr 56) and 0xFF).toByte()
            h[5] = ((pos ushr 48) and 0xFF).toByte()
            h[6] = ((pos ushr 40) and 0xFF).toByte()
            h[7] = ((pos ushr 32) and 0xFF).toByte()
            h[8] = ((pos ushr 24) and 0xFF).toByte()
            h[9] = ((pos ushr 16) and 0xFF).toByte()
            h[10] = ((pos ushr 8) and 0xFF).toByte()
            h[11] = (pos and 0xFF).toByte()

            val hAndCrypted = ByteArray(12 + crypted.size)
            System.arraycopy(h, 0, hAndCrypted, 0, 12)
            System.arraycopy(crypted, 0, hAndCrypted, 12, crypted.size)

            val fullTag = hmacSha256(k, hAndCrypted)
            val tag16 = fullTag.copyOfRange(0, 16)

            val wire = ByteArray(12 + crypted.size + 16)
            System.arraycopy(h, 0, wire, 0, 12)
            System.arraycopy(crypted, 0, wire, 12, crypted.size)
            System.arraycopy(tag16, 0, wire, 12 + crypted.size, 16)
            return wire
        }

        /**
         * v6_open: checks HMAC tag, unpacks (seq, pos, ct)
         */
        fun v6Open(k: ByteArray, msg: ByteArray): V6Packet? {
            if (msg.size < 12 + 16) return null
            val h = msg.copyOfRange(0, 12)
            val ct = msg.copyOfRange(12, msg.size - 16)
            val tag = msg.copyOfRange(msg.size - 16, msg.size)

            val hAndCt = ByteArray(12 + ct.size)
            System.arraycopy(h, 0, hAndCt, 0, 12)
            System.arraycopy(ct, 0, hAndCt, 12, ct.size)

            val expectedTag = hmacSha256(k, hAndCt).copyOfRange(0, 16)
            if (!MessageDigest.isEqual(tag, expectedTag)) {
                return null
            }

            val seq = ((h[0].toLong() and 0xFF) shl 24) or
                    ((h[1].toLong() and 0xFF) shl 16) or
                    ((h[2].toLong() and 0xFF) shl 8) or
                    (h[3].toLong() and 0xFF)

            val pos = ((h[4].toLong() and 0xFF) shl 56) or
                    ((h[5].toLong() and 0xFF) shl 48) or
                    ((h[6].toLong() and 0xFF) shl 40) or
                    ((h[7].toLong() and 0xFF) shl 32) or
                    ((h[8].toLong() and 0xFF) shl 24) or
                    ((h[9].toLong() and 0xFF) shl 16) or
                    ((h[10].toLong() and 0xFF) shl 8) or
                    (h[11].toLong() and 0xFF)

            return V6Packet(seq = seq, pos = pos, ct = ct)
        }
    }

    @Synchronized
    fun crypt(data: ByteArray): ByteArray {
        val n = data.size
        if (n == 0) return ByteArray(0)

        val start = pos
        val end = start + n
        val firstBi = (start shr 5).toInt()
        val lastBi = ((end - 1) shr 5).toInt()

        val numBlocks = lastBi - firstBi + 1
        val ksCombined = ByteArray(numBlocks * 32)
        for (bi in firstBi..lastBi) {
            val block = ksAt(base, nonce, dirb, bi)
            val destOffset = (bi - firstBi) * 32
            System.arraycopy(block, 0, ksCombined, destOffset, 32)
        }

        val off = (start and 31).toInt()
        val result = ByteArray(n)
        for (i in 0 until n) {
            result[i] = (data[i].toInt() xor ksCombined[off + i].toInt()).toByte()
        }

        pos = end
        return result
    }
}
