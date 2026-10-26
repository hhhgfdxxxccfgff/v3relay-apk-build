package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.relay.data.ConfigDecoder
import com.example.relay.data.ConfigRepository
import com.example.relay.data.LogEntry
import com.example.relay.data.LogType
import com.example.relay.data.ProxyConfig
import com.example.relay.data.ProxyMode
import com.example.relay.data.ProxyStats
import com.example.relay.data.ThemeMode
import com.example.relay.proxy.ProxyServerManager
import com.example.relay.service.RelayProxyService
import com.example.relay.service.RelayVpnService
import com.example.relay.service.ServiceStarter
import com.example.util.CrashGuardian
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PingState {
    object Idle : PingState()
    object Testing : PingState()
    data class Success(val latencyMs: Long) : PingState()
    data class Failed(val error: String) : PingState()
}

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConfigRepository(application)

    private val _config = MutableStateFlow(repository.loadConfig())
    val config: StateFlow<ProxyConfig> = _config.asStateFlow()

    val isRunning: StateFlow<Boolean> = ProxyServerManager.isRunning
    val stats: StateFlow<ProxyStats> = ProxyServerManager.stats
    val logs: StateFlow<List<LogEntry>> = ProxyServerManager.logs

    private val _pingState = MutableStateFlow<PingState>(PingState.Idle)
    val pingState: StateFlow<PingState> = _pingState.asStateFlow()

    private val _vpnPermissionRequest = MutableSharedFlow<Intent>()
    val vpnPermissionRequest: SharedFlow<Intent> = _vpnPermissionRequest.asSharedFlow()

    init {
        surfaceLastCrash()
        // Auto-test ping on startup if config is already present (skip if a session is already live)
        if (_config.value.workerUrl.isNotBlank() && !ProxyServerManager.isRunning.value) {
            viewModelScope.launch {
                delay(600)
                testPing()
            }
        }
    }

    fun updateMode(mode: ProxyMode) {
        val updated = _config.value.copy(mode = mode)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateCleanIp(cleanIp: String) {
        val updated = _config.value.copy(cleanIp = cleanIp)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateSocksPort(port: Int) {
        val updated = _config.value.copy(socksPort = port)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateHttpPort(port: Int) {
        val updated = _config.value.copy(httpPort = port)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateDnsServers(dns: String) {
        val updated = _config.value.copy(dnsServers = dns)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateExcludedApps(apps: Set<String>) {
        val updated = _config.value.copy(excludedApps = apps)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateAutoStartOnBoot(enabled: Boolean) {
        val updated = _config.value.copy(autoStartOnBoot = enabled)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateAllowLan(enabled: Boolean) {
        val updated = _config.value.copy(allowLan = enabled)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateThemeMode(mode: ThemeMode) {
        val updated = _config.value.copy(themeMode = mode)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun updateLanguage(language: String) {
        val updated = _config.value.copy(language = language)
        _config.value = updated
        repository.saveConfig(updated)
    }

    fun importConfigCode(code: String): Result<com.example.relay.data.DecodedConfig> {
        val res = ConfigDecoder.decodeConfig(code)
        res.onSuccess { decoded ->
            val updated = _config.value.copy(
                workerUrl = decoded.workerUrl,
                secret = decoded.secret,
                configCode = code.trim(),
                configName = decoded.name,
                cleanIp = if (decoded.cleanIp.isNotBlank()) decoded.cleanIp else _config.value.cleanIp,
                kpubPublic = decoded.kpub.trim(),
                pMode = decoded.protoP
            )
            _config.value = updated
            repository.saveConfig(updated)
            val nameDisplay = if (decoded.name.isNotBlank()) " (${decoded.name})" else ""
            ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_config_imported) + nameDisplay, LogType.SUCCESS)
            testPing()
        }.onFailure { err ->
            ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_config_error, err.message ?: ""), LogType.ERROR)
        }
        return res
    }

    fun clearConfig() {
        val updated = _config.value.copy(
            workerUrl = "",
            secret = "",
            configCode = "",
            configName = "",
            kpubPublic = "",
            pMode = 0
        )
        _config.value = updated
        repository.saveConfig(updated)
        ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_config_cleared), LogType.INFO)
    }

    fun toggleProxy(context: Context) {
        if (isRunning.value) {
            stopProxy(context)
        } else {
            val cfg = _config.value
            if (cfg.workerUrl.isBlank() || cfg.secret.isBlank()) {
                ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_need_config), LogType.ERROR)
                return
            }

            if (cfg.mode == ProxyMode.VPN_MODE) {
                try {
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        viewModelScope.launch {
                            _vpnPermissionRequest.emit(prepareIntent)
                        }
                    } else {
                        startVpnService(context)
                    }
                } catch (e: Exception) {
                    ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_vpn_prepare_error, e.message ?: ""), LogType.ERROR)
                }
            } else {
                startProxyOnlyService(context)
            }

            // Auto-ping after connection starts
            viewModelScope.launch {
                delay(1200)
                testPing()
            }
        }
    }

    private fun stopProxy(context: Context) {
        // 1) Close the proxy sockets and flip the running state immediately so the
        //    UI always reacts, even if service teardown misbehaves on this device.
        try {
            ProxyServerManager.stop()
        } catch (e: Throwable) {
            ProxyServerManager.addLog(
                getApplication<Application>().getString(R.string.vm_stop_error, e.message ?: ""),
                LogType.ERROR
            )
        }

        // 2) Ask the running foreground service to tear down gracefully; hard-stop
        //    the other one (a no-op when it is not running, so no service is spawned).
        if (_config.value.mode == ProxyMode.VPN_MODE) {
            try {
                RelayVpnService.stopService(context)
            } catch (_: Throwable) {
            }
            try {
                context.stopService(Intent(context, RelayProxyService::class.java))
            } catch (_: Throwable) {
            }
        } else {
            try {
                RelayProxyService.stopService(context)
            } catch (_: Throwable) {
            }
            try {
                context.stopService(Intent(context, RelayVpnService::class.java))
            } catch (_: Throwable) {
            }
        }

        _pingState.value = PingState.Idle

        // 3) Hard-stop fallback: if a service is still alive shortly after, kill it
        //    directly. This closes the TUN interface even if the graceful path failed.
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            delay(1200)
            if (!ProxyServerManager.isRunning.value) {
                try {
                    appContext.stopService(Intent(appContext, RelayVpnService::class.java))
                } catch (_: Throwable) {
                }
                try {
                    appContext.stopService(Intent(appContext, RelayProxyService::class.java))
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun surfaceLastCrash() {
        try {
            val crash = CrashGuardian.readLog(getApplication())
            if (crash.isBlank()) return
            val block = crash.substringAfterLast("=== ")
            val lines = block.lineSequence().filter { it.isNotBlank() }.take(12).toList()
            ProxyServerManager.addLog(getApplication<Application>().getString(R.string.crash_log_previous), LogType.ERROR)
            lines.forEach { line ->
                ProxyServerManager.addLog(line.take(240), LogType.ERROR)
            }
            CrashGuardian.clearLog(getApplication())
        } catch (_: Throwable) {
        }
    }

    fun onVpnPermissionGranted(context: Context) {
        try {
            startVpnService(context)
        } catch (e: Exception) {
            ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_vpn_start_error, e.message ?: ""), LogType.ERROR)
        }
    }

    private fun startVpnService(context: Context) {
        ServiceStarter.startVpn(context, _config.value)
    }

    private fun startProxyOnlyService(context: Context) {
        ServiceStarter.startProxy(context, _config.value)
    }

    fun testPing() {
        val cfg = _config.value
        if (cfg.workerUrl.isBlank()) {
            _pingState.value = PingState.Failed(getApplication<Application>().getString(R.string.vm_no_config))
            ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_ping_need_config), LogType.ERROR)
            return
        }
        viewModelScope.launch {
            _pingState.value = PingState.Testing
            val res = ProxyServerManager.testLatency(cfg.workerUrl, cfg.cleanIp)
            res.onSuccess { ms ->
                _pingState.value = PingState.Success(ms)
                ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_ping_success, ms), LogType.SUCCESS)
            }.onFailure { err ->
                val msg = err.message ?: getApplication<Application>().getString(R.string.vm_ping_generic_error)
                _pingState.value = PingState.Failed(msg)
                ProxyServerManager.addLog(getApplication<Application>().getString(R.string.vm_ping_failed, msg), LogType.ERROR)
            }
        }
    }

    fun clearLogs() {
        ProxyServerManager.clearLogs()
    }
}
