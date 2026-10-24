package com.example.relay.ws

import android.util.Base64
import com.example.relay.crypto.StreamCrypt
import java.security.SecureRandom

data class WSAuthResult(
    val wireBytes: ByteArray,
    val nonce: ByteArray,
    val baseKey: ByteArray
)

object WSAuth {
    private val random = SecureRandom()

    fun createAuthMsg(
        secret: String,
        host: String,
        port: Int,
        firstChunk: ByteArray = ByteArray(0)
    ): WSAuthResult {
        val base = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))
        val kid = base.copyOfRange(0, 4) // kid = sha256(secret)[:4]
        val nonce = ByteArray(12)
        random.nextBytes(nonce)

        val firstChunkB64 = if (firstChunk.isNotEmpty()) {
            Base64.encodeToString(firstChunk, Base64.NO_WRAP)
        } else {
            ""
        }
        val plainString = "$host|$port|$firstChunkB64"
        val plain = plainString.toByteArray(Charsets.UTF_8)

        val sc = StreamCrypt(base, nonce, "up")
        val ct = sc.crypt(plain)

        val tagData = ByteArray(base.size + nonce.size + ct.size)
        System.arraycopy(base, 0, tagData, 0, base.size)
        System.arraycopy(nonce, 0, tagData, base.size, nonce.size)
        System.arraycopy(ct, 0, tagData, base.size + nonce.size, ct.size)

        val fullTag = StreamCrypt.sha256(tagData)
        val tag = fullTag.copyOfRange(0, 16)

        // v4 wire format: kid(4) + nonce(12) + ct + tag(16)
        val wire = ByteArray(4 + 12 + ct.size + 16)
        System.arraycopy(kid, 0, wire, 0, 4)
        System.arraycopy(nonce, 0, wire, 4, 12)
        System.arraycopy(ct, 0, wire, 16, ct.size)
        System.arraycopy(tag, 0, wire, 16 + ct.size, 16)

        return WSAuthResult(wire, nonce, base)
    }
}
