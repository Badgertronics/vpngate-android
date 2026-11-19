package com.vpngate.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpngate.client.data.ConnectionState
import com.vpngate.client.data.CountryData
import com.vpngate.client.data.PreferencesManager
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.VpnServer
import com.vpngate.client.ui.components.PingBadge
import com.vpngate.client.ui.theme.ConnectedGreen
import com.vpngate.client.ui.theme.ConnectingBlue
import com.vpngate.client.ui.theme.ErrorRed
import com.vpngate.client.ui.theme.YellowPing
import com.vpngate.client.util.EventBus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    prefs: PreferencesManager,
    onNavigateToServers: () -> Unit,
    onConnect: (VpnServer, Protocol) -> Unit,
    onDisconnect: () -> Unit
) {
    val connectionState by EventBus.connectionState.collectAsState()
    val activeServer by EventBus.activeServer.collectAsState()
    val trafficStats by EventBus.trafficStats.collectAsState()
    val defaultProtocol by prefs.defaultProtocol.collectAsState()

    var selectedProtocol by remember(defaultProtocol, activeServer) {
        mutableStateOf(
            activeServer?.supportedProtocols?.firstOrNull() ?: defaultProtocol
        )
    }

    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING

    // Pulsing animation for connecting/connected state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnecting) 1.12f else if (isConnected) 1.05f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> ConnectedGreen
            ConnectionState.CONNECTING -> ConnectingBlue
            ConnectionState.RECONNECTING -> YellowPing
            ConnectionState.ERROR -> ErrorRed
            else -> MaterialTheme.colorScheme.primary
        },
        label = "btnColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPN Gate", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Connection Status Text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (connectionState) {
                        ConnectionState.CONNECTED -> "SECURE & CONNECTED"
                        ConnectionState.CONNECTING -> "ESTABLISHING TUNNEL..."
                        ConnectionState.RECONNECTING -> "AUTO-RECONNECTING..."
                        ConnectionState.DISCONNECTING -> "DISCONNECTING..."
                        ConnectionState.ERROR -> "CONNECTION FAILED"
                        else -> "DISCONNECTED"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = buttonColor,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isConnected) "Traffic encrypted via ${selectedProtocol.label}"
                           else "Tap the button below to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 2. Hero Animated Power Button
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                // Outer ring
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(CircleShape)
                        .background(buttonColor.copy(alpha = 0.12f))
                        .border(3.dp, buttonColor.copy(alpha = 0.4f), CircleShape)
                )

                // Inner circle button
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(buttonColor)
                        .clickable {
                            if (isConnected || isConnecting) {
                                onDisconnect()
                            } else {
                                activeServer?.let { server ->
                                    onConnect(server, selectedProtocol)
                                } ?: onNavigateToServers()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Power",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 3. Live Stats Card (when connected)
            AnimatedVisibility(visible = isConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Duration
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = trafficStats.durationFormatted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = "Duration", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Download
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = ConnectedGreen)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = trafficStats.rxFormatted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = "Download", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Upload
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = ConnectingBlue)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = trafficStats.txFormatted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = "Upload", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 4. Selected Server Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onNavigateToServers() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (activeServer != null) {
                        val server = activeServer!!
                        val flag = CountryData.getFlagEmoji(server.countryCode)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(text = flag, fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = server.country.ifEmpty { "Relay Server" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "${server.ip} • ${server.speedText}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        PingBadge(pingMs = server.realPingMs)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("No Server Selected", fontWeight = FontWeight.Bold)
                                Text("Tap to choose a VPN server", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Protocol Selector Chips
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Connection Protocol",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Protocol.ORDER.forEach { proto ->
                        val isSupported = activeServer?.supports(proto) ?: true
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                prefs.setDefaultProtocol(proto)
                            },
                            enabled = !isConnected && isSupported,
                            label = { Text(proto.shortLabel, fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
