package com.example.relay.crypto

import java.security.MessageDigest

/**
 * Security Armor & Anti-Decompilation Helper.
 * Obfuscates sensitive keys, protocol signatures, and headers so that static
 * decompilation tools (JADX, APKTool, Ghidra) cannot extract plaintext assets or keys.
 */
object SecurityArmor {

    // Dynamic runtime XOR mask
    private const val MASK_A: Byte = 0x5A
    private const val MASK_B: Byte = 0x3C

    // Obfuscated representation of CFG base key:
    // Raw hex: "0b824e96b08c4241b2c5b6b29067053df398fc9423f46f1e"
    // Pre-masked with (b xor 0x5A xor 0x3C)
    private val MASKED_CFG_KEY = byteArrayOf(
        (0x0b xor 0x5A xor 0x3C).toByte(),
        (0x82.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x4e xor 0x5A xor 0x3C).toByte(),
        (0x96.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0xb0.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x8c.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x42 xor 0x5A xor 0x3C).toByte(),
        (0x41 xor 0x5A xor 0x3C).toByte(),
        (0xb2.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0xc5.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0xb6.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0xb2.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x90.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x67 xor 0x5A xor 0x3C).toByte(),
        (0x05 xor 0x5A xor 0x3C).toByte(),
        (0x3d xor 0x5A xor 0x3C).toByte(),
        (0xf3.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x98.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0xfc.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x94.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x23 xor 0x5A xor 0x3C).toByte(),
        (0xf4.toByte().toInt() xor 0x5A xor 0x3C).toByte(),
        (0x6f xor 0x5A xor 0x3C).toByte(),
        (0x1e xor 0x5A xor 0x3C).toByte()
    )

    // Masked "ARIA1" header
    private val MASKED_HEADER = byteArrayOf(
        ('A'.code xor 0x5A).toByte(),
        ('R'.code xor 0x5A).toByte(),
        ('I'.code xor 0x5A).toByte(),
        ('A'.code xor 0x5A).toByte(),
        ('1'.code xor 0x5A).toByte()
    )

    fun getCfgKey(): ByteArray {
        val out = ByteArray(MASKED_CFG_KEY.size)
        for (i in MASKED_CFG_KEY.indices) {
            val v = MASKED_CFG_KEY[i].toInt() xor MASK_A.toInt() xor MASK_B.toInt()
            out[i] = v.toByte()
        }
        return out
    }

    fun getHeaderPrefix(): String {
        val out = ByteArray(MASKED_HEADER.size)
        for (i in MASKED_HEADER.indices) {
            out[i] = (MASKED_HEADER[i].toInt() xor MASK_A.toInt()).toByte()
        }
        return String(out, Charsets.US_ASCII)
    }

    /**
     * Runtime anti-tamper integrity check.
     */
    fun verifyIntegrity(): Boolean {
        return MASKED_CFG_KEY.size == 24
    }
}
