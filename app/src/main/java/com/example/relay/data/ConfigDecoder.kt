package com.example.relay.data

import android.net.Uri
import android.util.Base64
import com.example.relay.crypto.SecurityArmor
import com.example.relay.crypto.StreamCrypt
import org.json.JSONObject
import java.net.URLDecoder

data class DecodedConfig(
    val version: Int,
    val workerUrl: String,
    val secret: String,
    val name: String = "",
    val cleanIp: String = "",
    val kpub: String = "",     // devsync v2 server X25519 pub (64 hex) - from "k"
    val protoP: Int = 0        // protocol mode from "p" (0 = unspecified => auto)
)

object ConfigDecoder {

    /**
     * Checks if input looks like a v3/aria/mo config code, base64 payload, json, or direct worker URL.
     */
    fun isConfigCode(code: String): Boolean {
        val s = code.trim().lowercase().removePrefix("\"").removeSuffix("\"")
        return s.startsWith("mo://") || 
               s.startsWith("aria://") || 
               s.startsWith("v3://") ||
               s.startsWith("{") ||
               s.contains("workers.dev") ||
               s.contains("pages.dev") ||
               s.startsWith("http://") ||
               s.startsWith("https://") ||
               s.startsWith("qvjjet") // Base64 of "ARIA1"
    }

    /**
     * Resilient decoder handling:
     * - mo://, aria://, v3:// prefixes
     * - Direct JSON payloads {"w": "...", "s": "..."}
     * - Base64-encoded JSON payloads
     * - Encrypted ARIA1 binary payloads with HMAC verification
     * - Direct Worker URLs with secret in query or fragment (e.g. https://worker.workers.dev?s=KEY#Name)
     * - Telegram messages containing config links amidst Persian text
     * - URL fragments and query parameters
     */
    fun decodeConfig(input: String): Result<DecodedConfig> = kotlin.runCatching {
        var raw = input.trim().removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
        if (raw.isBlank()) {
            throw IllegalArgumentException("متن کانفیگ خالی است")
        }

        // 1. Extract link if surrounded by multi-line Telegram text
        raw = extractConfigToken(raw)

        // 2. Extract fragment (e.g. #ServerName) and query parameters (e.g. ?clean=1.2.3.4&s=...)
        var customName = ""
        if (raw.contains("#")) {
            val frag = raw.substringAfter("#").trim()
            customName = safeUrlDecode(frag)
            raw = raw.substringBefore("#").trim()
        }

        var secretFromQuery = ""
        var cleanIpFromQuery = ""
        var kpubFromQuery = ""
        var pmodeFromQuery = 0
        if (raw.contains("?")) {
            val query = raw.substringAfter("?")
            raw = raw.substringBefore("?")
            val params = query.split("&")
            for (p in params) {
                val key = p.substringBefore("=").lowercase().trim()
                val value = safeUrlDecode(p.substringAfter("=", "").trim())
                when (key) {
                    "clean", "clean_ip", "cleanip", "ip" -> cleanIpFromQuery = value
                    "s", "secret", "key", "password", "token" -> secretFromQuery = value
                    "name", "remark", "tag" -> if (customName.isBlank()) customName = value
                    "k", "kpub", "pub", "public" -> if (kpubFromQuery.isBlank()) kpubFromQuery = value
                    "p", "pmode", "proto" -> if (pmodeFromQuery == 0) pmodeFromQuery = value.toIntOrNull() ?: 0
                }
            }
        }

        // 3. Check if raw JSON directly
        if (raw.startsWith("{") && raw.endsWith("}")) {
            val jsonCfg = parseJsonConfig(raw, "", "", kpubFromQuery, pmodeFromQuery)
            if (jsonCfg != null) return@runCatching jsonCfg
        }

        // 4. Check if it's a direct Worker / HTTP(S) URL (and not raw JSON)
        val lowerRaw = raw.lowercase()
        if (!raw.startsWith("{") && !raw.startsWith("[") &&
            (lowerRaw.startsWith("http://") || lowerRaw.startsWith("https://") || 
            lowerRaw.contains("workers.dev") || lowerRaw.contains("pages.dev"))) {
            val workerUrl = if (!raw.contains("://")) "https://$raw" else raw
            // If secret was in query or fragment
            val secret = when {
                secretFromQuery.isNotBlank() -> secretFromQuery
                customName.isNotBlank() && !customName.contains(" ") && customName.length >= 8 -> customName
                else -> "aria-v3-secret" // Default fallback secret if worker allows open access
            }
            return@runCatching DecodedConfig(
                version = 1,
                workerUrl = workerUrl,
                secret = secret,
                name = if (customName.isNotBlank() && customName != secret) customName else "ورکر ابری",
                cleanIp = cleanIpFromQuery,
                kpub = kpubFromQuery,
                protoP = pmodeFromQuery
            )
        }

        // 5. Strip custom protocol prefixes
        var s = raw.trim()
        val low = s.lowercase()
        when {
            low.startsWith("mo://") -> s = s.substring(5).trim()
            low.startsWith("aria://") -> s = s.substring(7).trim()
            low.startsWith("v3://") -> s = s.substring(5).trim()
        }

        // Remove whitespace, line breaks, or carriage returns inside base64
        s = s.replace("\n", "").replace("\r", "").replace(" ", "").replace("\t", "")

        // Pad Base64 if needed
        val pad = (-s.length % 4 + 4) % 4
        s += "=".repeat(pad)

        // Try decoding with URL_SAFE, fallback to DEFAULT
        var wire: ByteArray? = try {
            Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }

        if (wire == null || wire.isEmpty()) {
            val normalized = s.replace('-', '+').replace('_', '/')
            wire = try {
                Base64.decode(normalized, Base64.DEFAULT)
            } catch (_: Exception) {
                throw IllegalArgumentException("فرمت دیتای کانفیگ نامعتبر است (Base64 صحیح نیست)")
            }
        }

        if (wire == null || wire.isEmpty()) {
            throw IllegalArgumentException("دیتای کانفیگ پس از رمزگشایی خالی است")
        }

        // 6. Check if decoded base64 is a JSON string (Standard Base64 JSON config)
        try {
            val possibleJsonStr = String(wire, Charsets.UTF_8).trim()
            if (possibleJsonStr.startsWith("{") && possibleJsonStr.endsWith("}")) {
                val jsonCfg = parseJsonConfig(possibleJsonStr, customName, cleanIpFromQuery, kpubFromQuery, pmodeFromQuery)
                if (jsonCfg != null) return@runCatching jsonCfg
            }
        } catch (_: Exception) {}

        // 7. Check if binary ARIA1 / V3 encrypted format
        val expectedPrefix = SecurityArmor.getHeaderPrefix() // "ARIA1"
        val prefixLen = expectedPrefix.length
        val minLen = prefixLen + 12 + 1 + 16 // Prefix + Nonce(12) + CT(min 1) + Tag(16)

        if (wire.size >= minLen) {
            val prefix = String(wire.copyOfRange(0, prefixLen), Charsets.US_ASCII)
            if (prefix == expectedPrefix || prefix == "ARIA1" || prefix == "V3REL") {
                val baseIn = SecurityArmor.getCfgKey()
                val base = StreamCrypt.sha256(baseIn)

                val nonce = wire.copyOfRange(prefixLen, prefixLen + 12)
                val ct = wire.copyOfRange(prefixLen + 12, wire.size - 16)
                val tag = wire.copyOfRange(wire.size - 16, wire.size)

                val checkData = ByteArray(base.size + nonce.size + ct.size)
                System.arraycopy(base, 0, checkData, 0, base.size)
                System.arraycopy(nonce, 0, checkData, base.size, nonce.size)
                System.arraycopy(ct, 0, checkData, base.size + nonce.size, ct.size)

                val expectedTag = StreamCrypt.sha256(checkData).copyOfRange(0, 16)
                if (tag.contentEquals(expectedTag)) {
                    val sc = StreamCrypt(base, nonce, "cfg")
                    val plainBytes = sc.crypt(ct)
                    val plainJson = String(plainBytes, Charsets.UTF_8)
                    val jsonCfg = parseJsonConfig(plainJson, customName, cleanIpFromQuery, kpubFromQuery, pmodeFromQuery)
                    if (jsonCfg != null) return@runCatching jsonCfg
                }
            }
        }

        // 8. Try direct UTF-8 string parse as fallback (e.g. host|secret|clean format)
        try {
            val asString = String(wire, Charsets.UTF_8).trim()
            if (asString.contains("|")) {
                val parts = asString.split("|")
                if (parts.size >= 2) {
                    val w = parts[0].trim()
                    val sec = parts[1].trim()
                    if (w.isNotBlank() && sec.isNotBlank()) {
                        return@runCatching DecodedConfig(
                            version = 1,
                            workerUrl = if (!w.contains("://")) "https://$w" else w,
                            secret = sec,
                            name = customName.ifBlank { "کانفیگ اختصاصی" },
                            cleanIp = cleanIpFromQuery
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        throw IllegalArgumentException("ساختار کد کانفیگ پشتیبانی نمی‌شود یا کلید نامعتبر است")
    }

    private fun parseJsonConfig(
        jsonStr: String,
        customName: String = "",
        cleanIpFallback: String = "",
        qKpub: String = "",
        qPmode: Int = 0
    ): DecodedConfig? {
        return try {
            var w = ""
            var s = ""
            var n = ""
            var clean = ""
            var kpub = ""
            var pmode = 0

            try {
                val json = JSONObject(jsonStr)
                w = json.optString("w", "").ifBlank {
                    json.optString("worker", "").ifBlank {
                        json.optString("workerUrl", "").ifBlank {
                            json.optString("url", "").ifBlank {
                                json.optString("host", "").ifBlank {
                                    json.optString("server", "")
                                }
                            }
                        }
                    }
                }

                s = json.optString("s", "").ifBlank {
                    json.optString("secret", "").ifBlank {
                        json.optString("key", "").ifBlank {
                            json.optString("password", "").ifBlank {
                                json.optString("token", "")
                            }
                        }
                    }
                }

                n = json.optString("n", "").ifBlank {
                    json.optString("name", "").ifBlank {
                        json.optString("remark", "").ifBlank {
                            json.optString("tag", "")
                        }
                    }
                }

                clean = json.optString("clean", "").ifBlank {
                    json.optString("clean_ip", "").ifBlank {
                        json.optString("cleanIp", "").ifBlank {
                            json.optString("ip", cleanIpFallback)
                        }
                    }
                }

                kpub = json.optString("k", "").ifBlank {
                    json.optString("kpub", "").ifBlank {
                        json.optString("pub", "")
                    }
                }

                pmode = try { json.getInt("p") } catch (_: Throwable) {
                    try { json.getInt("pmode") } catch (_: Throwable) { 0 }
                }

                // mirror the reference decoder: only version 1 configs are accepted
                if (json.has("v") && json.optInt("v", 1) != 1) {
                    return null
                }
            } catch (_: Throwable) {}

            // Fallback for JVM unit tests / environments where JSONObject is stubbed
            if (w.isBlank()) {
                w = extractJsonField(jsonStr, "w").ifBlank {
                    extractJsonField(jsonStr, "worker").ifBlank {
                        extractJsonField(jsonStr, "workerUrl").ifBlank {
                            extractJsonField(jsonStr, "url").ifBlank {
                                extractJsonField(jsonStr, "host").ifBlank {
                                    extractJsonField(jsonStr, "server")
                                }
                            }
                        }
                    }
                }
            }

            if (s.isBlank()) {
                s = extractJsonField(jsonStr, "s").ifBlank {
                    extractJsonField(jsonStr, "secret").ifBlank {
                        extractJsonField(jsonStr, "key").ifBlank {
                            extractJsonField(jsonStr, "password").ifBlank {
                                extractJsonField(jsonStr, "token")
                            }
                        }
                    }
                }
            }

            if (n.isBlank()) {
                n = extractJsonField(jsonStr, "n").ifBlank {
                    extractJsonField(jsonStr, "name").ifBlank {
                        extractJsonField(jsonStr, "remark").ifBlank {
                            extractJsonField(jsonStr, "tag")
                        }
                    }
                }
            }

            if (clean.isBlank()) {
                clean = extractJsonField(jsonStr, "clean").ifBlank {
                    extractJsonField(jsonStr, "clean_ip").ifBlank {
                        extractJsonField(jsonStr, "cleanIp").ifBlank {
                            extractJsonField(jsonStr, "ip").ifBlank { cleanIpFallback }
                        }
                    }
                }
            }

            if (kpub.isBlank()) {
                kpub = extractJsonField(jsonStr, "k").ifBlank {
                    extractJsonField(jsonStr, "kpub").ifBlank {
                        extractJsonField(jsonStr, "pub")
                    }
                }
            }
            if (pmode == 0) {
                pmode = extractJsonField(jsonStr, "p").toIntOrNull()
                    ?: extractJsonField(jsonStr, "pmode").toIntOrNull() ?: 0
            }
            if (kpub.isBlank()) kpub = qKpub
            if (pmode == 0) pmode = qPmode

            if (w.isNotBlank() && s.isNotBlank()) {
                val formattedUrl = if (!w.contains("://")) "https://$w" else w
                val finalName = when {
                    customName.isNotBlank() -> customName
                    n.isNotBlank() -> n
                    else -> "سرور کلودفلر"
                }
                DecodedConfig(
                    version = 1,
                    workerUrl = formattedUrl,
                    secret = s,
                    name = finalName,
                    cleanIp = clean,
                    kpub = kpub,
                    protoP = pmode
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonField(jsonStr: String, key: String): String {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(jsonStr)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractConfigToken(text: String): String {
        val trimmedAll = text.trim()
        if (trimmedAll.startsWith("{") && trimmedAll.endsWith("}")) {
            return trimmedAll
        }

        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            val tokens = trimmed.split("\\s+".toRegex())
            for (token in tokens) {
                val low = token.lowercase()
                if (low.startsWith("aria://") || low.startsWith("mo://") || low.startsWith("v3://") ||
                    low.startsWith("https://") || low.startsWith("http://") ||
                    low.contains("workers.dev") || low.contains("pages.dev")) {
                    return token.trim().removePrefix("<").removeSuffix(">").removePrefix("\"").removeSuffix("\"").removeSuffix(",")
                }
            }
        }
        return lines.firstOrNull { it.isNotBlank() }?.trim() ?: text.trim()
    }

    private fun safeUrlDecode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
