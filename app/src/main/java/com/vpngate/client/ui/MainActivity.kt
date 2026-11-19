package com.vpngate.client.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vpngate.client.VpnGateApplication
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.VpnServer
import com.vpngate.client.service.VpnGateVpnService
import com.vpngate.client.ui.navigation.Screen
import com.vpngate.client.ui.screens.ConnectScreen
import com.vpngate.client.ui.screens.LogsScreen
import com.vpngate.client.ui.screens.ServersScreen
import com.vpngate.client.ui.screens.SettingsScreen
import com.vpngate.client.ui.theme.VpnGateTheme
import com.vpngate.client.util.EventBus

class MainActivity : ComponentActivity() {

    private var pendingConnectAction: Pair<VpnServer, Protocol>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as VpnGateApplication
        val repository = app.serverRepository
        val prefs = app.preferencesManager

        setContent {
            val currentTheme by prefs.theme.collectAsState()

            VpnGateTheme(themePreference = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    // VPN Permission Launcher
                    val vpnPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            pendingConnectAction?.let { (server, proto) ->
                                startVpnService(server, proto)
                            }
                        } else {
                            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
                            EventBus.log("App", "VPN permission was denied by user.", isError = true)
                        }
                        pendingConnectAction = null
                    }

                    fun triggerConnect(server: VpnServer, protocol: Protocol) {
                        EventBus.setActiveServer(server)
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            pendingConnectAction = Pair(server, protocol)
                            vpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            startVpnService(server, protocol)
                        }
                    }

                    fun triggerDisconnect() {
                        val intent = Intent(this, VpnGateVpnService::class.java).apply {
                            action = VpnGateVpnService.ACTION_DISCONNECT
                        }
                        startService(intent)
                    }

                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                Screen.items.forEach { screen ->
                                    val isSelected = currentDestination?.route == screen.route
                                    NavigationBarItem(
                                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                                        label = { Text(screen.title) },
                                        selected = isSelected,
                                        onClick = {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    ) { paddingValues ->
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Servers.route,
                            modifier = Modifier.padding(paddingValues)
                        ) {
                            composable(Screen.Servers.route) {
                                ServersScreen(
                                    repository = repository,
                                    onConnectServer = { server, proto ->
                                        triggerConnect(server, proto)
                                        navController.navigate(Screen.Connect.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
                            composable(Screen.Connect.route) {
                                ConnectScreen(
                                    prefs = prefs,
                                    onNavigateToServers = {
                                        navController.navigate(Screen.Servers.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onConnect = { server, proto ->
                                        triggerConnect(server, proto)
                                    },
                                    onDisconnect = {
                                        triggerDisconnect()
                                    }
                                )
                            }
                            composable(Screen.Logs.route) {
                                LogsScreen()
                            }
                            composable(Screen.Settings.route) {
                                SettingsScreen(prefs = prefs)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startVpnService(server: VpnServer, protocol: Protocol) {
        val intent = Intent(this, VpnGateVpnService::class.java).apply {
            action = VpnGateVpnService.ACTION_CONNECT
            putExtra(VpnGateVpnService.EXTRA_PROTOCOL, protocol.code)
        }
        startService(intent)
    }
}
