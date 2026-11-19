package com.vpngate.client.util

import com.vpngate.client.data.ConnectionState
import com.vpngate.client.data.LogEntry
import com.vpngate.client.data.TrafficStats
import com.vpngate.client.data.VpnServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventBus {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeServer = MutableStateFlow<VpnServer?>(null)
    val activeServer: StateFlow<VpnServer?> = _activeServer.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _logEvents = MutableSharedFlow<LogEntry>(extraBufferCapacity = 64)
    val logEvents: SharedFlow<LogEntry> = _logEvents.asSharedFlow()

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun setActiveServer(server: VpnServer?) {
        _activeServer.value = server
    }

    fun updateTrafficStats(stats: TrafficStats) {
        _trafficStats.value = stats
    }

    fun log(tag: String, message: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            isError = isError
        )
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > 2000) {
            current.removeAt(0)
        }
        _logs.value = current
        _logEvents.tryEmit(entry)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
