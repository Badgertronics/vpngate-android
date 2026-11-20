package com.vpngate.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vpngate.client.R
import com.vpngate.client.data.ConnectionState
import com.vpngate.client.data.CountryData
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.TrafficStats
import com.vpngate.client.data.VpnServer
import com.vpngate.client.ui.MainActivity

object NotificationHelper {

    const val CHANNEL_ID = "vpngate_vpn_channel"
    const val NOTIFICATION_ID = 1001
    const val ACTION_DISCONNECT = "com.vpngate.client.ACTION_DISCONNECT"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Gate Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN Gate tunnel status and traffic monitoring"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        state: ConnectionState,
        server: VpnServer?,
        protocol: Protocol,
        stats: TrafficStats
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(context, VpnGateVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            context,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state) {
            ConnectionState.CONNECTING -> "Connecting to VPN..."
            ConnectionState.CONNECTED -> "VPN Gate - Connected"
            ConnectionState.DISCONNECTING -> "Disconnecting..."
            ConnectionState.RECONNECTING -> "Reconnecting to VPN..."
            ConnectionState.ERROR -> "VPN Connection Error"
            else -> "VPN Gate"
        }

        val flag = if (server != null) CountryData.getFlagEmoji(server.countryCode) else "🌐"
        val serverDesc = if (server != null) "$flag ${server.country} (${server.hostname})" else "VPN Relay"

        val content = if (state == ConnectionState.CONNECTED) {
            "$serverDesc • ${protocol.shortLabel}\n↓ ${stats.rxFormatted}  ↑ ${stats.txFormatted}  •  ${stats.durationFormatted}"
        } else {
            "$serverDesc • ${protocol.shortLabel}"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_stat)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
            builder.addAction(0, "Disconnect", disconnectPendingIntent)
        }

        return builder.build()
    }
}
