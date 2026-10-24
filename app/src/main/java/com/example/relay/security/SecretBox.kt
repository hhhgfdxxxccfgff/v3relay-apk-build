package com.example.relay.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small AES-256-GCM helper backed by the Android Keystore.
 * Values are stored as "enc1:" + base64(iv || ciphertext).
 */
object SecretBox {
    private const val TAG = "SecretBox"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "v3relay_config_key"
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(body, 0, out, iv.size, body.size)
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "encrypt failed", t)
            plain
        }
    }

    fun decrypt(stored: String?): String {
        val value = stored ?: return ""
        if (!value.startsWith(PREFIX)) return value
        return try {
            val raw = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "decrypt failed", t)
            ""
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing
        return try {
            generateKey(256)
        } catch (t: Throwable) {
            // A few older devices only expose 128-bit AES in the Keystore.
            generateKey(128)
        }
    }

    private fun generateKey(size: Int): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(size)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
