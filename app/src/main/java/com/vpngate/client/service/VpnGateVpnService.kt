package com.vpngate.client.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.vpngate.client.VpnGateApplication
import com.vpngate.client.data.Config
import com.vpngate.client.data.ConnectionState
import com.vpngate.client.data.PreferencesManager
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.TrafficStats
import com.vpngate.client.data.VpnServer
import com.vpngate.client.protocol.L2tpEngine
import com.vpngate.client.protocol.OpenVpnEngine
import com.vpngate.client.protocol.ProtocolEngine
import com.vpngate.client.protocol.SoftEtherEngine
import com.vpngate.client.protocol.SstpEngine
import com.vpngate.client.util.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class VpnGateVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var currentEngine: ProtocolEngine? = null
    private var watchdog: WatchdogService? = null

    private lateinit var prefs: PreferencesManager
    private var activeServer: VpnServer? = null
    private var selectedProtocol: Protocol = Protocol.SSLVP
    private var autoReconnectAttempts = 0
    private val manualDisconnectRequested = AtomicBoolean(false)

    companion object {
        const val ACTION_CONNECT = "com.vpngate.client.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.vpngate.client.ACTION_DISCONNECT"
        const val EXTRA_PROTOCOL = "extra_protocol"

        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        prefs = (application as VpnGateApplication).preferencesManager
        watchdog = WatchdogService(prefs) {
            handleConnectivityLost()
        }
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            manualDisconnectRequested.set(true)
            disconnect(userInitiated = true)
            return START_NOT_STICKY
        }

        if (action == ACTION_CONNECT) {
            manualDisconnectRequested.set(false)
            autoReconnectAttempts = 0
            val protoCode = intent.getStringExtra(EXTRA_PROTOCOL)
            val protocol = protoCode?.let { Protocol.fromCode(it) } ?: prefs.defaultProtocol.value
            selectedProtocol = protocol

            val server = EventBus.activeServer.value
            if (server != null) {
                activeServer = server
                startVpnTunnel(server, protocol)
            } else {
                EventBus.log("Service", "Connect requested but no active server set.", isError = true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpnTunnel(server: VpnServer, protocol: Protocol) {
        connectionJob?.cancel()
        currentEngine?.disconnect()

        connectionJob = serviceScope.launch {
            EventBus.setConnectionState(ConnectionState.CONNECTING)
            updateNotification(ConnectionState.CONNECTING, TrafficStats())
            EventBus.log("Service", "Starting VPN tunnel to ${server.country} (${server.hostname}) via ${protocol.label}...")

            try {
                // 1. Establish TUN interface
                val builder = Builder()
                    .setSession("${server.country} - ${server.hostname}")
                    .addAddress("10.211.1.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .setMtu(1500)

                val pfd = builder.establish()
                if (pfd == null) {
                    EventBus.log("Service", "Failed to establish Android VPN TUN interface (permission missing?).", isError = true)
                    EventBus.setConnectionState(ConnectionState.ERROR)
                    return@launch
                }
                tunInterface = pfd

                // 2. Instantiate Protocol Engine
                val engine: ProtocolEngine = when (protocol) {
                    Protocol.SSLVP -> SoftEtherEngine()
                    Protocol.SSTP -> SstpEngine()
                    Protocol.L2TP -> L2tpEngine()
                    Protocol.OPENVPN -> OpenVpnEngine()
                }
                currentEngine = engine

                EventBus.setConnectionState(ConnectionState.CONNECTED)
                EventBus.log("Service", "✓ VPN TUN interface established. Routing traffic through ${protocol.shortLabel}.")
                startStatsUpdater()
                watchdog?.start(serviceScope)

                // 3. Connect Protocol Tunnel
                engine.connect(server, this@VpnGateVpnService, pfd.fileDescriptor) { msg, isErr ->
                    EventBus.log(protocol.shortLabel, msg, isErr)
                }

                // If engine.connect returns normally, tunnel ended
                handleTunnelEnded()

            } catch (e: Exception) {
                if (!manualDisconnectRequested.get()) {
                    EventBus.log("Service", "Tunnel error: ${e.message}", isError = true)
                    handleTunnelEnded()
                }
            }
        }
    }

    private fun handleConnectivityLost() {
        EventBus.log("Service", "Watchdog triggered connectivity loss. Re-evaluating tunnel...", isError = true)
        currentEngine?.disconnect()
    }

    private fun handleTunnelEnded() {
        if (manualDisconnectRequested.get()) {
            disconnect(userInitiated = true)
            return
        }

        // Check auto-reconnect
        if (prefs.autoReconnect.value && autoReconnectAttempts < prefs.autoReconnectRetries.value) {
            autoReconnectAttempts++
            val delaySec = prefs.autoReconnectDelay.value.coerceAtLeast(2)
            EventBus.setConnectionState(ConnectionState.RECONNECTING)
            EventBus.log(
                "Service",
                "Tunnel dropped. Auto-reconnecting in $delaySec seconds (attempt $autoReconnectAttempts/${prefs.autoReconnectRetries.value})...",
                isError = true
            )
            serviceScope.launch {
                delay(delaySec * 1000L)
                val server = activeServer
                if (server != null && !manualDisconnectRequested.get()) {
                    startVpnTunnel(server, selectedProtocol)
                }
            }
        } else {
            EventBus.setConnectionState(ConnectionState.IDLE)
            EventBus.log("Service", "VPN Tunnel disconnected.")
            disconnect(userInitiated = false)
        }
    }

    private fun startStatsUpdater() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && EventBus.connectionState.value == ConnectionState.CONNECTED) {
                delay(1000L)
                updateNotification(ConnectionState.CONNECTED, EventBus.trafficStats.value)
            }
        }
    }

    private fun updateNotification(state: ConnectionState, stats: TrafficStats) {
        val notification = NotificationHelper.buildNotification(
            this,
            state,
            activeServer,
            selectedProtocol,
            stats
        )
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun disconnect(userInitiated: Boolean) {
        manualDisconnectRequested.set(userInitiated)
        watchdog?.stop()
        statsJob?.cancel()
        statsJob = null
        connectionJob?.cancel()
        connectionJob = null

        currentEngine?.disconnect()
        currentEngine = null

        try {
            tunInterface?.close()
        } catch (_: Exception) {}
        tunInterface = null

        EventBus.setConnectionState(ConnectionState.IDLE)
        EventBus.updateTrafficStats(TrafficStats())

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isServiceRunning = false
        disconnect(userInitiated = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        EventBus.log("Service", "VPN permission revoked by system.", isError = true)
        disconnect(userInitiated = true)
        super.onRevoke()
    }
}
