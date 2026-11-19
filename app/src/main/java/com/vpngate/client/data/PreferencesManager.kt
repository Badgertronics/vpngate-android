package com.vpngate.client.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vpngate_settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _defaultProtocol = MutableStateFlow(
        Protocol.fromCode(prefs.getString("default_protocol", "sslvp") ?: "sslvp") ?: Protocol.SSLVP
    )
    val defaultProtocol: StateFlow<Protocol> = _defaultProtocol.asStateFlow()

    private val _connectTimeout = MutableStateFlow(
        prefs.getInt("connect_timeout", Config.DEFAULT_CONNECT_TIMEOUT_SEC)
    )
    val connectTimeout: StateFlow<Int> = _connectTimeout.asStateFlow()

    private val _connectRetries = MutableStateFlow(
        prefs.getInt("connect_retries", Config.DEFAULT_CONNECT_RETRIES)
    )
    val connectRetries: StateFlow<Int> = _connectRetries.asStateFlow()

    private val _autoReconnect = MutableStateFlow(
        prefs.getBoolean("auto_reconnect", Config.DEFAULT_AUTO_RECONNECT)
    )
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _autoReconnectRetries = MutableStateFlow(
        prefs.getInt("auto_reconnect_retries", Config.DEFAULT_AUTO_RECONNECT_RETRIES)
    )
    val autoReconnectRetries: StateFlow<Int> = _autoReconnectRetries.asStateFlow()

    private val _autoReconnectDelay = MutableStateFlow(
        prefs.getInt("auto_reconnect_delay", Config.DEFAULT_AUTO_RECONNECT_DELAY_SEC)
    )
    val autoReconnectDelay: StateFlow<Int> = _autoReconnectDelay.asStateFlow()

    private val _watchdogEnabled = MutableStateFlow(
        prefs.getBoolean("watchdog_enabled", true)
    )
    val watchdogEnabled: StateFlow<Boolean> = _watchdogEnabled.asStateFlow()

    private val _watchdogInterval = MutableStateFlow(
        prefs.getInt("watchdog_interval", Config.DEFAULT_WATCHDOG_INTERVAL_SEC)
    )
    val watchdogInterval: StateFlow<Int> = _watchdogInterval.asStateFlow()

    private val _watchdogFailures = MutableStateFlow(
        prefs.getInt("watchdog_failures", Config.DEFAULT_WATCHDOG_FAILURES)
    )
    val watchdogFailures: StateFlow<Int> = _watchdogFailures.asStateFlow()

    private val _connectivityTargets = MutableStateFlow(
        prefs.getStringSet("connectivity_targets", Config.DEFAULT_CONNECTIVITY_TARGETS.toSet())
            ?: Config.DEFAULT_CONNECTIVITY_TARGETS.toSet()
    )
    val connectivityTargets: StateFlow<Set<String>> = _connectivityTargets.asStateFlow()

    private val _mirrors = MutableStateFlow(
        prefs.getStringSet("mirrors", Config.DEFAULT_MIRRORS.toSet())?.toList()
            ?: Config.DEFAULT_MIRRORS
    )
    val mirrors: StateFlow<List<String>> = _mirrors.asStateFlow()

    fun setTheme(theme: String) {
        prefs.edit().putString("theme", theme).apply()
        _theme.value = theme
    }

    fun setDefaultProtocol(protocol: Protocol) {
        prefs.edit().putString("default_protocol", protocol.code).apply()
        _defaultProtocol.value = protocol
    }

    fun setConnectTimeout(seconds: Int) {
        prefs.edit().putInt("connect_timeout", seconds).apply()
        _connectTimeout.value = seconds
    }

    fun setConnectRetries(retries: Int) {
        prefs.edit().putInt("connect_retries", retries).apply()
        _connectRetries.value = retries
    }

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_reconnect", enabled).apply()
        _autoReconnect.value = enabled
    }

    fun setAutoReconnectRetries(retries: Int) {
        prefs.edit().putInt("auto_reconnect_retries", retries).apply()
        _autoReconnectRetries.value = retries
    }

    fun setAutoReconnectDelay(delaySec: Int) {
        prefs.edit().putInt("auto_reconnect_delay", delaySec).apply()
        _autoReconnectDelay.value = delaySec
    }

    fun setWatchdogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("watchdog_enabled", enabled).apply()
        _watchdogEnabled.value = enabled
    }

    fun setWatchdogInterval(seconds: Int) {
        prefs.edit().putInt("watchdog_interval", seconds).apply()
        _watchdogInterval.value = seconds
    }

    fun setWatchdogFailures(count: Int) {
        prefs.edit().putInt("watchdog_failures", count).apply()
        _watchdogFailures.value = count
    }

    fun setTargetEnabled(target: String, enabled: Boolean) {
        val current = _connectivityTargets.value.toMutableSet()
        if (enabled) {
            current.add(target)
        } else {
            current.remove(target)
        }
        prefs.edit().putStringSet("connectivity_targets", current).apply()
        _connectivityTargets.value = current
    }

    fun addCustomTarget(target: String) {
        val trimmed = target.trim()
        if (trimmed.isNotEmpty()) {
            val current = _connectivityTargets.value.toMutableSet()
            current.add(trimmed)
            prefs.edit().putStringSet("connectivity_targets", current).apply()
            _connectivityTargets.value = current
        }
    }

    fun removeCustomTarget(target: String) {
        val current = _connectivityTargets.value.toMutableSet()
        current.remove(target)
        prefs.edit().putStringSet("connectivity_targets", current).apply()
        _connectivityTargets.value = current
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _theme.value = "system"
        _defaultProtocol.value = Protocol.SSLVP
        _connectTimeout.value = Config.DEFAULT_CONNECT_TIMEOUT_SEC
        _connectRetries.value = Config.DEFAULT_CONNECT_RETRIES
        _autoReconnect.value = Config.DEFAULT_AUTO_RECONNECT
        _autoReconnectRetries.value = Config.DEFAULT_AUTO_RECONNECT_RETRIES
        _autoReconnectDelay.value = Config.DEFAULT_AUTO_RECONNECT_DELAY_SEC
        _watchdogEnabled.value = true
        _watchdogInterval.value = Config.DEFAULT_WATCHDOG_INTERVAL_SEC
        _watchdogFailures.value = Config.DEFAULT_WATCHDOG_FAILURES
        _connectivityTargets.value = Config.DEFAULT_CONNECTIVITY_TARGETS.toSet()
        _mirrors.value = Config.DEFAULT_MIRRORS
    }
}
