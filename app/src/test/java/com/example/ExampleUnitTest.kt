package com.example

import android.util.Base64
import com.example.relay.crypto.SecurityArmor
import com.example.relay.crypto.StreamCrypt
import com.example.relay.crypto.TlsFragmenter
import com.example.relay.data.ConfigDecoder
import com.example.relay.tunnel.WSTunnel
import com.example.relay.ws.WSAuth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testStreamCryptSymmetry() {
        val base = StreamCrypt.sha256("my-secret-key".toByteArray())
        val nonce = ByteArray(12) { it.toByte() }

        val sender = StreamCrypt(base, nonce, "up")
        val receiver = StreamCrypt(base, nonce, "up")

        val originalText = "Hello world! This is a test message to verify the custom stream cipher."
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val encrypted = sender.crypt(originalBytes)
        val decrypted = receiver.crypt(encrypted)

        assertArrayEquals(originalBytes, decrypted)
        assertEquals(originalText, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testStreamCryptMultiBlock() {
        val base = StreamCrypt.sha256("test-secret".toByteArray())
        val nonce = ByteArray(12) { (it * 3).toByte() }

        val encrypter = StreamCrypt(base, nonce, "down")
        val decrypter = StreamCrypt(base, nonce, "down")

        // 1000 bytes spanning multiple 32-byte SHA256 blocks
        val payload = ByteArray(1000) { (it % 256).toByte() }

        val enc = encrypter.crypt(payload)
        val dec = decrypter.crypt(enc)

        assertArrayEquals(payload, dec)
    }

    @Test
    fun testV4AuthWireFormat() {
        val secret = "my-secret-v4"
        val expectedKid = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)

        val auth = WSAuth.createAuthMsg(
            secret = secret,
            host = "example.com",
            port = 443
        )

        val actualKid = auth.wireBytes.copyOfRange(0, 4)
        assertArrayEquals(expectedKid, actualKid)

        val actualNonce = auth.wireBytes.copyOfRange(4, 16)
        assertArrayEquals(auth.nonce, actualNonce)

        assertTrue(auth.wireBytes.size >= 4 + 12 + 1 + 16)
    }

    @Test
    fun testSecurityArmorKeyIntegrity() {
        assertTrue(SecurityArmor.verifyIntegrity())
        val header = SecurityArmor.getHeaderPrefix()
        assertEquals("ARIA1", header)
        val cfgKey = SecurityArmor.getCfgKey()
        assertEquals(24, cfgKey.size)
    }

    @Test
    fun testHkdfKeyDerivation() {
        val base = StreamCrypt.sha256("secret".toByteArray())
        val nonce = ByteArray(12) { it.toByte() }
        val kUp = StreamCrypt.hkdf(base, nonce, "v6-up")
        val kDn = StreamCrypt.hkdf(base, nonce, "v6-down")

        assertEquals(32, kUp.size)
        assertEquals(32, kDn.size)
        // Up and down keys must be distinct
        assertTrue(!kUp.contentEquals(kDn))
    }

    @Test
    fun testPosCryptAndV6Sealing() {
        val key = StreamCrypt.sha256("session-key".toByteArray())
        val nonce = ByteArray(12) { (it + 5).toByte() }
        val payload = "Testing positional encryption with arbitrary stream positions.".toByteArray(Charsets.UTF_8)
        val pos = 1024L
        val seq = 42L

        val crypted = StreamCrypt.posCrypt(key, nonce, "up", pos, payload)
        val decrypted = StreamCrypt.posCrypt(key, nonce, "up", pos, crypted)
        assertArrayEquals(payload, decrypted)

        // Seal into wire format
        val wire = StreamCrypt.v6Seal(key, seq, pos, crypted)
        assertEquals(12 + crypted.size + 16, wire.size)

        // Open wire format
        val opened = StreamCrypt.v6Open(key, wire)
        assertNotNull(opened)
        assertEquals(seq, opened!!.seq)
        assertEquals(pos, opened.pos)
        assertArrayEquals(crypted, opened.ct)

        // Verify tampering is rejected
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0x01).toByte()
        assertNull(StreamCrypt.v6Open(key, wire))
    }

    @Test
    fun testTlsFragmentation() {
        // Build mock TLS record
        val mockPayload = ByteArray(100) { it.toByte() }
        val mockHello = TlsFragmenter.tlsRecordFrag(mockPayload)
        assertEquals(105, mockHello.size)
        assertEquals(0x16.toByte(), mockHello[0])

        val fragments = TlsFragmenter.fragTlsRecords(mockHello, mode = "half")
        assertTrue(fragments.size >= 2)
        // Verify all fragments are valid TLS records
        for (f in fragments) {
            assertEquals(0x16.toByte(), f[0])
            val flen = ((f[3].toInt() and 0xFF) shl 8) or (f[4].toInt() and 0xFF)
            assertEquals(f.size - 5, flen)
        }
    }

    @Test
    fun testConfigDecoderPlainJson() {
        val json = """{"w": "https://worker.example.workers.dev", "s": "my_secret_token", "n": "TestServer", "clean": "104.21.5.1"}"""
        val result = ConfigDecoder.decodeConfig(json)
        assertTrue(result.isSuccess)
        val cfg = result.getOrThrow()
        assertEquals("https://worker.example.workers.dev", cfg.workerUrl)
        assertEquals("my_secret_token", cfg.secret)
        assertEquals("TestServer", cfg.name)
        assertEquals("104.21.5.1", cfg.cleanIp)
    }

    @Test
    fun testConfigDecoderDirectWorkerUrl() {
        val url = "https://relay-node.example.workers.dev?s=secret123&clean=104.16.2.1#FastRelay"
        val result = ConfigDecoder.decodeConfig(url)
        assertTrue(result.isSuccess)
        val cfg = result.getOrThrow()
        assertEquals("https://relay-node.example.workers.dev", cfg.workerUrl)
        assertEquals("secret123", cfg.secret)
        assertEquals("FastRelay", cfg.name)
        assertEquals("104.16.2.1", cfg.cleanIp)
    }

    @Test
    fun testConfigDecoderTelegramMultilineMessage() {
        val msg = """
            سلام داداش گلم اینم کانفیگ جدید پرسرعت:
            https://special-v3.workers.dev?s=pass999#VIP
            حتما تست کن!
        """.trimIndent()
        val result = ConfigDecoder.decodeConfig(msg)
        assertTrue(result.isSuccess)
        val cfg = result.getOrThrow()
        assertEquals("https://special-v3.workers.dev", cfg.workerUrl)
        assertEquals("pass999", cfg.secret)
        assertEquals("VIP", cfg.name)
    }

    @Test
    fun testAuthWireInitialSessionWithFirstChunk() {
        val secret = "test-secret"
        val nonce = ByteArray(12) { it.toByte() }
        val firstChunk = "GET /index.html HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8)
        val expectedFb64 = java.util.Base64.getEncoder().encodeToString(firstChunk)

        val wire = WSTunnel.buildAuthWire(
            secret = secret,
            targetHost = "api.target.com",
            targetPort = 443,
            nonce = nonce,
            resume = null,
            isRestart = false,
            firstChunk = firstChunk
        )

        // Decrypt auth plaintext
        val ct0 = wire.copyOfRange(16, wire.size - 16)
        val baseKey = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))
        val sc0 = StreamCrypt(baseKey, nonce, "up")
        val plain = String(sc0.crypt(ct0), Charsets.UTF_8)

        assertEquals("api.target.com|443|$expectedFb64|v6r", plain)
    }

    @Test
    fun testAuthWireRestartOmitsFb64ToPreventDoubleRequest() {
        val secret = "test-secret"
        val nonce = ByteArray(12) { it.toByte() }
        val firstChunk = "GET /index.html HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8)

        // On restart, fb64 must be empty to avoid double request to origin
        val wire = WSTunnel.buildAuthWire(
            secret = secret,
            targetHost = "api.target.com",
            targetPort = 443,
            nonce = nonce,
            resume = null,
            isRestart = true,
            firstChunk = firstChunk
        )

        val ct0 = wire.copyOfRange(16, wire.size - 16)
        val baseKey = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))
        val sc0 = StreamCrypt(baseKey, nonce, "up")
        val plain = String(sc0.crypt(ct0), Charsets.UTF_8)

        assertEquals("api.target.com|443||v6r", plain)
    }

    @Test
    fun testAuthWireResumeOmitsFb64AndIncludesResumeMarker() {
        val secret = "test-secret"
        val nonce = ByteArray(12) { it.toByte() }
        val firstChunk = "GET /index.html HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8)

        // On resume, fb64 must be empty and marker must have connId and downF
        val wire = WSTunnel.buildAuthWire(
            secret = secret,
            targetHost = "api.target.com",
            targetPort = 443,
            nonce = nonce,
            resume = Pair("sess-9988", 8192L),
            isRestart = false,
            firstChunk = firstChunk
        )

        val ct0 = wire.copyOfRange(16, wire.size - 16)
        val baseKey = StreamCrypt.sha256(secret.toByteArray(Charsets.UTF_8))
        val sc0 = StreamCrypt(baseKey, nonce, "up")
        val plain = String(sc0.crypt(ct0), Charsets.UTF_8)

        assertEquals("api.target.com|443||v6r:sess-9988:8192", plain)
    }
}
