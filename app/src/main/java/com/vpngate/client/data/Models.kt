package com.vpngate.client.data

import java.util.Locale

enum class Protocol(val code: String, val label: String, val shortLabel: String) {
    SSLVP("sslvp", "SSL-VPN (SoftEther)", "SSL-VPN"),
    L2TP("l2tp", "L2TP/IPsec", "L2TP"),
    SSTP("sstp", "MS-SSTP", "SSTP"),
    OPENVPN("openvpn", "OpenVPN", "OpenVPN");

    companion object {
        val ORDER = listOf(SSLVP, L2TP, SSTP, OPENVPN)

        fun fromCode(code: String): Protocol? {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }
}

enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    RECONNECTING,
    ERROR
}

data class TrafficStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxSpeedBps: Long = 0L,
    val txSpeedBps: Long = 0L,
    val connectedSeconds: Long = 0L
) {
    val rxFormatted: String
        get() = formatBytes(rxBytes)
    val txFormatted: String
        get() = formatBytes(txBytes)
    val rxSpeedFormatted: String
        get() = "${formatBytes(rxSpeedBps)}/s"
    val txSpeedFormatted: String
        get() = "${formatBytes(txSpeedBps)}/s"

    val durationFormatted: String
        get() {
            val hours = connectedSeconds / 3600
            val minutes = (connectedSeconds % 3600) / 60
            val seconds = connectedSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
            val pre = "KMGTPE"[exp - 1]
            return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
        }
    }
}

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false
) {
    override fun toString(): String = "[$timestamp] [$tag] $message"
}

enum class SortOption(val title: String) {
    RECOMMENDED("Recommended"),
    REAL_PING("Real Ping"),
    SPEED("Speed"),
    VPNGATE_PING("VPN Gate Ping"),
    SESSIONS("Sessions"),
    UPTIME("Uptime"),
    TOTAL_TRAFFIC("Total Traffic"),
    COUNTRY("Country"),
    HOSTNAME("Hostname")
}

data class VpnServer(
    val hostname: String,
    val ip: String,
    val score: Long = 0L,
    val pingMs: Int = 0,
    val speedMbps: Double = 0.0,
    val country: String = "",
    val countryCode: String = "",
    val sessions: Int = 0,
    val uptimeSeconds: Long = 0L,
    val totalUsers: Long = 0L,
    val totalTrafficBytes: Long = 0L,
    val logPolicy: String = "",
    val operator: String = "",
    val openVpnConfigBase64: String = "",

    // Protocols
    val sslVpnPort: Int? = null,
    val l2tpSupported: Boolean = false,
    val openvpnTcpPort: Int? = null,
    val openvpnUdpPort: Int? = null,
    val sstpHost: String = "",
    val sstpPort: Int? = null,

    // Real latency measured from client device (-1 = unreachable, null = unmeasured)
    var realPingMs: Int? = null
) {
    val supportedProtocols: List<Protocol>
        get() {
            val list = mutableListOf<Protocol>()
            if (sslVpnPort != null && sslVpnPort > 0) list.add(Protocol.SSLVP)
            if (l2tpSupported) list.add(Protocol.L2TP)
            if (sstpPort != null && sstpPort > 0) list.add(Protocol.SSTP)
            if ((openvpnTcpPort != null && openvpnTcpPort > 0) || (openvpnUdpPort != null && openvpnUdpPort > 0) || openVpnConfigBase64.isNotEmpty()) {
                list.add(Protocol.OPENVPN)
            }
            return list.sortedBy { Protocol.ORDER.indexOf(it) }
        }

    fun supports(protocol: Protocol): Boolean = supportedProtocols.contains(protocol)

    fun portFor(protocol: Protocol): Int? {
        return when (protocol) {
            Protocol.SSLVP -> sslVpnPort ?: 443
            Protocol.L2TP -> 1701
            Protocol.SSTP -> sstpPort ?: 443
            Protocol.OPENVPN -> openvpnUdpPort ?: openvpnTcpPort ?: 1194
        }
    }

    val speedText: String
        get() = if (speedMbps > 0) String.format(Locale.US, "%.2f Mbps", speedMbps) else "-"

    val pingText: String
        get() = if (pingMs > 0) "$pingMs ms" else "-"

    val realPingText: String
        get() = when {
            realPingMs == null -> "-"
            realPingMs!! < 0 -> "Unreachable"
            else -> "${realPingMs} ms"
        }

    val uptimeText: String
        get() {
            if (uptimeSeconds <= 0) return "-"
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            return if (days > 0) "${days}d ${hours}h" else "${hours}h"
        }

    val trafficText: String
        get() = if (totalTrafficBytes > 0) TrafficStats.formatBytes(totalTrafficBytes) else "-"
}
