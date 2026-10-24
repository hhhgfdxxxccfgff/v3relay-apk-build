package com.example.relay.data

import android.content.Context
import android.content.SharedPreferences
import com.example.relay.security.SecretBox

class ConfigRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("v3_proxy_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WORKER_URL = "worker_url"
        private const val KEY_SECRET = "secret"
        private const val KEY_CLEAN_IP = "clean_ip"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_MODE = "proxy_mode"
        private const val KEY_CONFIG_CODE = "config_code"
        private const val KEY_CONFIG_NAME = "config_name"
        private const val KEY_DNS = "dns_servers"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_ALLOW_LAN = "allow_lan"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"

        private const val DEFAULT_DNS = "1.1.1.1, 8.8.8.8"

        fun sanitizePort(port: Int, fallback: Int, allowZero: Boolean = false): Int {
            if (allowZero && port == 0) return 0
            return if (port in 1024..65535) port else fallback
        }
    }

    private fun readSecret(key: String): String = SecretBox.decrypt(prefs.getString(key, ""))

    private fun isLegacySecret(key: String): Boolean {
        val raw = prefs.getString(key, null) ?: return false
        return raw.isNotEmpty() && !SecretBox.isEncrypted(raw)
    }

    fun loadConfig(): ProxyConfig {
        val modeStr = prefs.getString(KEY_MODE, ProxyMode.VPN_MODE.name) ?: ProxyMode.VPN_MODE.name
        val mode = runCatching { ProxyMode.valueOf(modeStr) }.getOrDefault(ProxyMode.VPN_MODE)
        val themeStr = prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val themeMode = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK)
        val config = ProxyConfig(
            workerUrl = readSecret(KEY_WORKER_URL),
            secret = readSecret(KEY_SECRET),
            cleanIp = readSecret(KEY_CLEAN_IP),
            socksPort = sanitizePort(prefs.getInt(KEY_SOCKS_PORT, 1080), 1080),
            httpPort = sanitizePort(prefs.getInt(KEY_HTTP_PORT, 8080), 8080, allowZero = true),
            mode = mode,
            configCode = readSecret(KEY_CONFIG_CODE),
            configName = readSecret(KEY_CONFIG_NAME),
            dnsServers = prefs.getString(KEY_DNS, DEFAULT_DNS) ?: DEFAULT_DNS,
            excludedApps = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet(),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, false),
            allowLan = prefs.getBoolean(KEY_ALLOW_LAN, false),
            themeMode = themeMode,
            language = prefs.getString(KEY_LANGUAGE, "") ?: ""
        )
        val needsMigration = isLegacySecret(KEY_WORKER_URL) || isLegacySecret(KEY_SECRET) ||
            isLegacySecret(KEY_CLEAN_IP) || isLegacySecret(KEY_CONFIG_CODE) ||
            isLegacySecret(KEY_CONFIG_NAME)
        if (needsMigration) saveConfig(config)
        return config
    }

    fun saveConfig(config: ProxyConfig) {
        prefs.edit()
            .putString(KEY_WORKER_URL, SecretBox.encrypt(config.workerUrl.trim()))
            .putString(KEY_SECRET, SecretBox.encrypt(config.secret.trim()))
            .putString(KEY_CLEAN_IP, SecretBox.encrypt(config.cleanIp.trim()))
            .putInt(KEY_SOCKS_PORT, config.socksPort)
            .putInt(KEY_HTTP_PORT, config.httpPort)
            .putString(KEY_MODE, config.mode.name)
            .putString(KEY_CONFIG_CODE, SecretBox.encrypt(config.configCode.trim()))
            .putString(KEY_CONFIG_NAME, SecretBox.encrypt(config.configName.trim()))
            .putString(KEY_DNS, config.dnsServers.trim())
            .putStringSet(KEY_EXCLUDED_APPS, config.excludedApps)
            .putBoolean(KEY_AUTO_START, config.autoStartOnBoot)
            .putBoolean(KEY_ALLOW_LAN, config.allowLan)
            .putString(KEY_THEME, config.themeMode.name)
            .putString(KEY_LANGUAGE, config.language)
            .apply()
    }
}
