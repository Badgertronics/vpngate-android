package com.vpngate.client.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpngate.client.data.Config
import com.vpngate.client.data.PreferencesManager
import com.vpngate.client.data.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(prefs: PreferencesManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentTheme by prefs.theme.collectAsState()
    val defaultProtocol by prefs.defaultProtocol.collectAsState()
    val connectTimeout by prefs.connectTimeout.collectAsState()
    val autoReconnect by prefs.autoReconnect.collectAsState()
    val autoReconnectRetries by prefs.autoReconnectRetries.collectAsState()
    val autoReconnectDelay by prefs.autoReconnectDelay.collectAsState()
    val watchdogEnabled by prefs.watchdogEnabled.collectAsState()
    val watchdogInterval by prefs.watchdogInterval.collectAsState()
    val watchdogFailures by prefs.watchdogFailures.collectAsState()
    val targets by prefs.connectivityTargets.collectAsState()
    val mirrors by prefs.mirrors.collectAsState()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showAddTargetDialog by remember { mutableStateOf(false) }
    var newTargetText by remember { mutableStateOf("") }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateDialogMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Appearance Section
            SettingsSectionHeader(title = "Appearance", icon = Icons.Default.DarkMode)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showThemeDialog = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Theme", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = when (currentTheme) {
                                    "dark" -> "Dark Mode"
                                    "light" -> "Light Mode"
                                    else -> "System Default"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Connectivity & Auto-Reconnect Section
            SettingsSectionHeader(title = "Connectivity & Auto-Reconnect", icon = Icons.Default.NetworkPing)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Auto-Reconnect Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Reconnect", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Automatically reconnect if the VPN connection drops unexpectedly",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoReconnect,
                            onCheckedChange = { prefs.setAutoReconnect(it) }
                        )
                    }

                    if (autoReconnect) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Auto-reconnect delay: $autoReconnectDelay seconds",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = autoReconnectDelay.toFloat(),
                            onValueChange = { prefs.setAutoReconnectDelay(it.toInt()) },
                            valueRange = 2f..30f,
                            steps = 27
                        )

                        Text(
                            text = "Max reconnect attempts: $autoReconnectRetries",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = autoReconnectRetries.toFloat(),
                            onValueChange = { prefs.setAutoReconnectRetries(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Watchdog Master Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Connectivity Watchdog", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Periodically probe health check targets to verify active internet route",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = watchdogEnabled,
                            onCheckedChange = { prefs.setWatchdogEnabled(it) }
                        )
                    }

                    if (watchdogEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Check interval: $watchdogInterval seconds",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = watchdogInterval.toFloat(),
                            onValueChange = { prefs.setWatchdogInterval(it.toInt()) },
                            valueRange = 5f..120f,
                            steps = 22
                        )

                        Text(
                            text = "Disconnect after $watchdogFailures consecutive failed checks",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = watchdogFailures.toFloat(),
                            onValueChange = { prefs.setWatchdogFailures(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Probe Targets",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )
                            IconButton(
                                onClick = { showAddTargetDialog = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Target")
                            }
                        }

                        // Target check list with enable/disable switches
                        targets.forEach { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = target, style = MaterialTheme.typography.bodyMedium)
                                Checkbox(
                                    checked = true,
                                    onCheckedChange = { enabled ->
                                        prefs.setTargetEnabled(target, enabled)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Timeouts & Retries Section
            SettingsSectionHeader(title = "Timeouts & Retries", icon = Icons.Default.Timer)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connection Timeout: $connectTimeout seconds",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = connectTimeout.toFloat(),
                        onValueChange = { prefs.setConnectTimeout(it.toInt()) },
                        valueRange = 10f..60f,
                        steps = 9
                    )
                }
            }

            // 4. Updates Section
            SettingsSectionHeader(title = "Updates", icon = Icons.Default.SystemUpdate)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("VPN Gate Client for Android", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Installed version: v${Config.VERSION}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            isCheckingUpdates = true
                            scope.launch(Dispatchers.IO) {
                                checkForUpdates(context) { result ->
                                    isCheckingUpdates = false
                                    updateDialogMessage = result
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCheckingUpdates
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Check for Updates Now")
                        }
                    }
                }
            }

            // 5. Buy me a coffee / Support Section
            SettingsSectionHeader(title = "Buy me a coffee", icon = Icons.Default.Coffee)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Developer: ${Config.DEVELOPER_NAME}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "If you appreciate this project, support independent open-source development by donating cryptocurrency:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    CryptoAddressRow(
                        context = context,
                        coinName = "Bitcoin (BTC)",
                        address = Config.DONATE_BITCOIN
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CryptoAddressRow(
                        context = context,
                        coinName = "Ethereum (ETH / ERC20)",
                        address = Config.DONATE_ETHEREUM
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CryptoAddressRow(
                        context = context,
                        coinName = "BNB Smart Chain (BEP20)",
                        address = Config.DONATE_BEP20
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        // Theme Dialog
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text("Choose Theme") },
                text = {
                    Column {
                        listOf("system" to "System Default", "light" to "Light Mode", "dark" to "Dark Mode").forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        prefs.setTheme(code)
                                        showThemeDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentTheme == code,
                                    onClick = {
                                        prefs.setTheme(code)
                                        showThemeDialog = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Add Target Dialog
        if (showAddTargetDialog) {
            AlertDialog(
                onDismissRequest = { showAddTargetDialog = false },
                title = { Text("Add Probe Target") },
                text = {
                    OutlinedTextField(
                        value = newTargetText,
                        onValueChange = { newTargetText = it },
                        placeholder = { Text("e.g. example.com or 1.0.0.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newTargetText.isNotBlank()) {
                            prefs.addCustomTarget(newTargetText.trim())
                            newTargetText = ""
                        }
                        showAddTargetDialog = false
                    }) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddTargetDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Update Dialog
        updateDialogMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { updateDialogMessage = null },
                title = { Text("Update Status") },
                text = { Text(msg) },
                confirmButton = {
                    Button(onClick = { updateDialogMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CryptoAddressRow(context: Context, coinName: String, address: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = coinName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(coinName, address))
                    Toast.makeText(context, "$coinName address copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = address,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

private fun checkForUpdates(context: Context, onResult: (String) -> Unit) {
    val client = OkHttpClient()
    for (url in Config.RELEASE_API_MIRRORS) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", Config.USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful && resp.body != null) {
                    val json = JSONObject(resp.body!!.string())
                    val tagName = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
                    val currentVersion = Config.VERSION.removePrefix("v").removePrefix("V")
                    if (tagName.isNotEmpty() && tagName != currentVersion) {
                        onResult("A new version is available: v$tagName (current: v$currentVersion)!\n\nRelease notes:\n${json.optString("body", "No changelog provided.")}")
                    } else {
                        onResult("You are running the latest version of VPN Gate (v${Config.VERSION}).")
                    }
                    return
                }
            }
        } catch (_: Exception) {}
    }
    onResult("You are running the latest version of VPN Gate (v${Config.VERSION}).")
}
