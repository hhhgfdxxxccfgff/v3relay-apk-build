package com.example.relay.v2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self test of the V2Crypto port against RFC 7693 / 8439 / 7748 oracles
 *  and vectors generated from the reference client_ws.py implementation. */
class V2CryptoSelfTest {

    private fun rep(b: ByteArray, n: Int): ByteArray {
        val o = ByteArray(b.size * n)
        for (i in 0 until n) System.arraycopy(b, 0, o, i * b.size, b.size)
        return o
    }

    private fun concat(vararg xs: ByteArray): ByteArray {
        var tot = 0
        for (x in xs) tot += x.size
        val o = ByteArray(tot)
        var p = 0
        for (x in xs) {
            System.arraycopy(x, 0, o, p, x.size)
            p += x.size
        }
        return o
    }

    @Test
    fun blake2sRfc7693() {
        assertArrayEquals(
            V2Crypto.unhex("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"),
            V2Crypto.blake2s(ByteArray(0))
        )
        assertArrayEquals(
            V2Crypto.unhex("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982"),
            V2Crypto.blake2s("abc".toByteArray())
        )
        // big message: sha256("big") * 32 + sha256("tail")  (from reference vector set)
        val big = concat(
            rep(V2Crypto.sha256("big".toByteArray()), 32),
            V2Crypto.sha256("tail".toByteArray())
        )
        assertEquals(32 * 32 + 32, big.size)
        assertArrayEquals(
            V2Crypto.unhex("e465cd09d2f9ce5718aff5676c48ea595da9a0191cd9e796bdcec029d2d06248"),
            V2Crypto.blake2s(big)
        )
        val key32 = V2Crypto.unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        assertArrayEquals(
            V2Crypto.unhex("48a8997da407876b3d79c0d92325ad3b89cbb754d86ab71aee047ad345fd2c49"),
            V2Crypto.blake2s(ByteArray(0), key32)
        )
        assertArrayEquals(
            V2Crypto.unhex("a281f725754969a702f6fe36fc591b7def866e4b70173ece402fc01c064d6b65"),
            V2Crypto.blake2s("abc".toByteArray(), key32)
        )
        assertArrayEquals(
            V2Crypto.unhex("bd3b6f55e211030c948fbf15b0c2559111f70be38dd7c9a5b17807bf81c2334a"),
            V2Crypto.blake2s(big, key32)
        )
    }

    @Test
    fun b2hmacVectors() {
        val k = V2Crypto.unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        assertArrayEquals(
            V2Crypto.unhex("82623be5bc0a391b24dce53e67d028eff92d586de0485ac37822e597d049c74f"),
            V2Crypto.b2hmac(k, "abc".toByteArray())
        )
        assertArrayEquals(
            V2Crypto.unhex("e2641d24dfa8dd89e8cb77f1cdc77d8c006e5bebdfc4d6a3a3a6f8d5b7586fb0"),
            V2Crypto.b2hmac(k, ByteArray(0))
        )
    }

    @Test
    fun chachaBlockRfc8439() {
        val key = V2Crypto.unhex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F")
        val nonce = V2Crypto.unhex("000000090000004A00000000")
        assertArrayEquals(
            V2Crypto.unhex(
                "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                    "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e"
            ),
            V2Crypto.chachaBlock(key, 1, nonce)
        )
        // alt vector from reference: key=sha256("ck"), nonce=v2hkdf(key,"devsync-nbdown",12), counter 7
        val altKey = V2Crypto.sha256("ck".toByteArray())
        val altNonce = V2Crypto.v2hkdf(altKey, "devsync-nbdown".toByteArray(Charsets.US_ASCII), 12)
        assertArrayEquals(
            V2Crypto.unhex("2fd0a7bdfbfbfb70c2ab46410bf842ea8ca05a08285f95322b8afda68d4d14b161da158e991b0503fc672b3621961d939edb75d55e3635f74ae4c97c23f4d032"),
            V2Crypto.chachaBlock(altKey, 7, altNonce)
        )
    }

    @Test
    fun poly1305Rfc8439() {
        val key = V2Crypto.unhex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        val msg = "Cryptographic Forum Research Group".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(
            V2Crypto.unhex("a8061dc1305136c6c22b8baf0c0127a9"),
            V2Crypto.poly1305(key, msg)
        )
    }

    @Test
    fun aeadRfc8439() {
        val k = V2Crypto.unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val n = V2Crypto.unhex("070000004041424344454647")
        val aad = V2Crypto.unhex("50515253c0c1c2c3c4c5c6c7")
        val pt = V2Crypto.unhex(
            "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
                "73206f66202739393a204966204920636f756c64206f6666657220796f7520" +
                "6f6e6c79206f6e652074697020666f7220746865206675747572652c207375" +
                "6e73637265656e20776f756c642062652069742e"
        )
        val want = V2Crypto.unhex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3" +
                "692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7" +
                "bc3ff4def08e4b7a9de576d26586cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691"
        )
        assertArrayEquals(want, V2Crypto.pureSeal(k, n, pt, aad))
        assertArrayEquals(pt, V2Crypto.pureOpen(k, n, want, aad)!!)
    }

    @Test
    fun aeadRoundtripAndCorruption() {
        val k = V2Crypto.sha256("aek".toByteArray())
        val n = V2Crypto.v2hkdf(k, "devsync-up".toByteArray(Charsets.US_ASCII), 12)
        val pt = concat(rep(V2Crypto.sha256("aept".toByteArray()), 4), ByteArray(37) { 'x'.code.toByte() })
        val ct = V2Crypto.pureSeal(k, n, pt, ByteArray(0))
        assertArrayEquals(pt, V2Crypto.pureOpen(k, n, ct, ByteArray(0))!!)
        val bad = ct.clone()
        bad[25] = (bad[25].toInt() xor 1).toByte()
        assertNull(V2Crypto.pureOpen(k, n, bad, ByteArray(0)))
        assertNull(V2Crypto.pureOpen(k, n, ct.copyOf(ct.size - 1), ByteArray(0)))
    }

    @Test
    fun hkdfAndNonce() {
        val ikm = V2Crypto.sha256("k1".toByteArray())
        assertArrayEquals(
            V2Crypto.unhex("42e6d72ed6a5d61219ad62560086d78bcec5788ffaea14c199d6c9da9a6155d9"),
            V2Crypto.v2hkdf(ikm, "devsync-up".toByteArray(Charsets.US_ASCII), 32)
        )
        assertArrayEquals(
            V2Crypto.unhex("30bdb34715263d8fd4ad6c64"),
            V2Crypto.v2hkdf(ikm, "devsync-nbup".toByteArray(Charsets.US_ASCII), 12)
        )
        assertArrayEquals(
            V2Crypto.unhex("a7c6893f11a4b41c93eb74cb6dc871fd4f737ad0b42288720ce2dea69de4785e5f25da9e9249ce2024770d749bd1757f"),
            V2Crypto.v2hkdf(ikm, byteArrayOf(1, 2), 48)
        )
        val nb = V2Crypto.v2hkdf(ikm, "devsync-nbup".toByteArray(Charsets.US_ASCII), 12)
        assertArrayEquals(V2Crypto.unhex("30bdb34715263d8fd4ad6c64"), V2Crypto.v2nonce(nb, 0))
        assertArrayEquals(V2Crypto.unhex("30bdb34715263d8fd4ad6c65"), V2Crypto.v2nonce(nb, 1))
        assertArrayEquals(V2Crypto.unhex("30bdb34715263d8fd4ad6c4e"), V2Crypto.v2nonce(nb, 42))
        assertArrayEquals(V2Crypto.unhex("30bdb34715263c8fd4ad6c63"), V2Crypto.v2nonce(nb, (1L shl 40) + 7))
    }

    @Test
    fun x25519Rfc7748() {
        val a = V2Crypto.unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = V2Crypto.unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val base = ByteArray(32)
        base[0] = 9
        val aPub = V2Crypto.v2xPub(a)
        val bPub = V2Crypto.v2xPub(b)
        assertArrayEquals(
            V2Crypto.unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"),
            aPub
        )
        assertArrayEquals(
            V2Crypto.unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"),
            bPub
        )
        val sh = V2Crypto.v2xDh(a, bPub)
        assertArrayEquals(
            V2Crypto.unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"),
            sh
        )
        assertArrayEquals(sh, V2Crypto.v2xDh(b, aPub))
    }

    @Test
    fun v2UidMac() {
        val secret = "secret".toByteArray(Charsets.US_ASCII)
        val uid = V2Crypto.v2uid(secret)
        assertArrayEquals(
            V2Crypto.unhex("6f5230739913f9336fde5a02"),
            uid
        )
        val eRaw = V2Crypto.unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val mac = V2Crypto.v2mac(secret, uid, eRaw)
        assertEquals(16, mac.size)
        // kpub-derivation: sha256 (v2mac) must differ for different e inputs
        val mac2 = V2Crypto.v2mac(secret, uid, V2Crypto.unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"))
        assertTrue(!mac.contentEquals(mac2))
    }

    @Test
    fun v2SealOpenRoundtrip() {
        val key = V2Crypto.sha256("svk".toByteArray())
        val nb = V2Crypto.v2hkdf(key, "devsync-nbdown".toByteArray(Charsets.US_ASCII), 12)
        val aad = ByteArray(19)
        aad[0] = 0x09
        val pt = ByteArray(6533) { (it and 0xFF).toByte() }
        val rec = V2Crypto.v2seal(key, V2Crypto.v2nonce(nb, 5), pt, aad)
        assertEquals(pt.size + 16, rec.size)
        assertArrayEquals(pt, V2Crypto.v2open(key, V2Crypto.v2nonce(nb, 5), rec, aad)!!)
        val bad = rec.clone()
        bad[100] = (bad[100].toInt() xor 1).toByte()
        assertNull(V2Crypto.v2open(key, V2Crypto.v2nonce(nb, 5), bad, aad))
        assertNull(V2Crypto.v2open(key, V2Crypto.v2nonce(nb, 6), rec, aad))
    }
}
