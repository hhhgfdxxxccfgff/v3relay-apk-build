package com.example.relay.crypto

import java.util.Random

/**
 * Pure byte-level TLS ClientHello fragmentation logic matching the python reference.
 * Bypasses DPI systems that perform single-record SNI inspection by splitting
 * the ClientHello record across record boundaries.
 */
object TlsFragmenter {

    fun tlsRecordFrag(payload: ByteArray, ver: ByteArray = byteArrayOf(0x03, 0x01)): ByteArray {
        val len = payload.size
        val rec = ByteArray(5 + len)
        rec[0] = 0x16 // Handshake record type
        rec[1] = ver[0]
        rec[2] = ver[1]
        rec[3] = ((len ushr 8) and 0xFF).toByte()
        rec[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, rec, 5, len)
        return rec
    }

    /**
     * Locate (sni_start, sni_end) offsets of the SNI hostname inside a TLS ClientHello record.
     */
    fun findSniBounds(hello: ByteArray): Pair<Int, Int>? {
        try {
            if (hello.size < 5 || hello[0] != 0x16.toByte()) return null
            val recLen = ((hello[3].toInt() and 0xFF) shl 8) or (hello[4].toInt() and 0xFF)
            if (hello.size < 5 + recLen) return null
            val body = hello.copyOfRange(5, 5 + recLen)
            if (body.size < 4 || body[0] != 0x01.toByte()) return null // Handshake ClientHello = 0x01
            val hsLen = ((body[1].toInt() and 0xFF) shl 16) or ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
            if (body.size < 4 + hsLen) return null
            val hs = body.copyOfRange(4, 4 + hsLen)
            if (hs.size < 34) return null

            var o = 2 + 32 // version(2) + random(32)
            val sidLen = hs[o].toInt() and 0xFF
            o += 1 + sidLen
            val csLen = ((hs[o].toInt() and 0xFF) shl 8) or (hs[o + 1].toInt() and 0xFF)
            o += 2 + csLen
            val cmLen = hs[o].toInt() and 0xFF
            o += 1 + cmLen

            val extTotal = ((hs[o].toInt() and 0xFF) shl 8) or (hs[o + 1].toInt() and 0xFF)
            o += 2
            val end = o + extTotal
            val base = 5 + 4 // record header + handshake header

            while (o + 4 <= hs.size && o < end) {
                val et = ((hs[o].toInt() and 0xFF) shl 8) or (hs[o + 1].toInt() and 0xFF)
                val el = ((hs[o + 2].toInt() and 0xFF) shl 8) or (hs[o + 3].toInt() and 0xFF)
                if (et == 0x0000) { // server_name extension
                    val nl = ((hs[o + 7].toInt() and 0xFF) shl 8) or (hs[o + 8].toInt() and 0xFF)
                    val ns = base + o + 9
                    return Pair(ns, ns + nl)
                }
                o += 4 + el
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Splits one ClientHello TLS record into multiple TLS records.
     */
    fun fragTlsRecords(hello: ByteArray, mode: String = "sni", seed: Long = 0xC10CL): List<ByteArray> {
        val rnd = Random(seed)
        if (hello.isEmpty() || hello[0] != 0x16.toByte()) return listOf(hello)
        val body = hello.copyOfRange(5, hello.size)
        val n = body.size
        val cuts = sortedSetOf<Int>()

        when (mode) {
            "sni" -> {
                val b = findSniBounds(hello)
                if (b != null) {
                    val cut = b.first + maxOf(1, (b.second - b.first) / 2) - 5
                    val clamped = maxOf(1, minOf(n - 1, cut))
                    cuts.add(clamped)
                } else {
                    cuts.add(n / 2)
                }
            }
            "half" -> cuts.add(n / 2)
            "chunked" -> {
                val c = 40
                var k = c
                while (k < n) {
                    cuts.add(k)
                    k += c
                }
            }
            "random" -> {
                val k = 16 + rnd.nextInt(maxOf(1, n - 32))
                cuts.add(k)
                if (n > 200 && rnd.nextBoolean()) {
                    cuts.add(16 + rnd.nextInt(maxOf(1, n - 32)))
                }
            }
            else -> return listOf(hello)
        }

        val validCuts = cuts.filter { it in 1 until n }
        if (validCuts.isEmpty()) return listOf(hello)

        val out = mutableListOf<ByteArray>()
        var prev = 0
        for (c in validCuts + listOf(n)) {
            val chunk = body.copyOfRange(prev, c)
            out.add(tlsRecordFrag(chunk))
            prev = c
        }
        return out
    }
}
