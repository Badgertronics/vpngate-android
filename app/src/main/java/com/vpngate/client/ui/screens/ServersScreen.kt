package com.vpngate.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpngate.client.data.CountryData
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.ServerRepository
import com.vpngate.client.data.SortOption
import com.vpngate.client.data.VpnServer
import com.vpngate.client.ui.components.ServerCard
import com.vpngate.client.ui.components.ServerDetailsSheet
import com.vpngate.client.util.EventBus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServersScreen(
    repository: ServerRepository,
    onConnectServer: (VpnServer, Protocol) -> Unit
) {
    val servers by repository.servers.collectAsState()
    val isLoading by repository.isLoading.collectAsState()
    val isPingingAll by repository.isPingingAll.collectAsState()
    val pingProgress by repository.pingProgress.collectAsState()
    val activeServer by EventBus.activeServer.collectAsState()

    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedProtocolFilter by remember { mutableStateOf<Protocol?>(null) }
    var selectedCountryFilter by remember { mutableStateOf<String?>(null) }
    var sortOption by remember { mutableStateOf(SortOption.RECOMMENDED) }

    var showSortMenu by remember { mutableStateOf(false) }
    var showCountryMenu by remember { mutableStateOf(false) }
    var detailSheetServer by remember { mutableStateOf<VpnServer?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Compute unique countries with counts
    val countryCounts = remember(servers) {
        val map = mutableMapOf<String, Int>()
        for (s in servers) {
            val country = s.country.ifEmpty { "Other" }
            map[country] = (map[country] ?: 0) + 1
        }
        map.toList().sortedByDescending { it.second }
    }

    // Filter and sort servers
    val filteredServers = remember(servers, searchQuery, selectedProtocolFilter, selectedCountryFilter, sortOption) {
        var list = servers.asSequence()

        // 1. Text Search
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.country.lowercase().contains(q) ||
                it.hostname.lowercase().contains(q) ||
                it.ip.contains(q) ||
                it.operator.lowercase().contains(q)
            }
        }

        // 2. Protocol Filter
        if (selectedProtocolFilter != null) {
            list = list.filter { it.supports(selectedProtocolFilter!!) }
        }

        // 3. Country Filter
        if (!selectedCountryFilter.isNullOrBlank()) {
            list = list.filter { it.country.equals(selectedCountryFilter, ignoreCase = true) }
        }

        // 4. Sorting
        val sorted = when (sortOption) {
            SortOption.RECOMMENDED -> list.sortedWith(
                compareByDescending<VpnServer> {
                    val pingWeight = if (it.realPingMs != null && it.realPingMs!! > 0) {
                        (1000 - it.realPingMs!!).coerceAtLeast(0) * 1000
                    } else 0
                    it.score + pingWeight + (it.speedMbps * 100).toLong()
                }
            )
            SortOption.REAL_PING -> list.sortedWith { a, b ->
                val pa = a.realPingMs
                val pb = b.realPingMs
                when {
                    pa == pb -> 0
                    pa == null -> 1
                    pb == null -> -1
                    pa < 0 -> 1
                    pb < 0 -> -1
                    else -> pa.compareTo(pb)
                }
            }
            SortOption.SPEED -> list.sortedByDescending { it.speedMbps }
            SortOption.VPNGATE_PING -> list.sortedBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
            SortOption.SESSIONS -> list.sortedByDescending { it.sessions }
            SortOption.UPTIME -> list.sortedByDescending { it.uptimeSeconds }
            SortOption.TOTAL_TRAFFIC -> list.sortedByDescending { it.totalTrafficBytes }
            SortOption.COUNTRY -> list.sortedBy { it.country }
            SortOption.HOSTNAME -> list.sortedBy { it.hostname }
        }

        sorted.toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VPN Relay Servers", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "${filteredServers.size} available (${servers.size} total)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Ping All button
                    if (isPingingAll) {
                        FilledTonalButton(
                            onClick = { repository.stopPingAll() },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${pingProgress.first}/${pingProgress.second}", fontSize = 11.sp)
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch { repository.pingAll(filteredServers) }
                        }) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = "Ping All")
                        }
                    }

                    // Refresh button
                    IconButton(onClick = {
                        scope.launch { repository.refreshServers() }
                    }) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Ping progress bar if active
            AnimatedVisibility(visible = isPingingAll) {
                val progress = if (pingProgress.second > 0) {
                    pingProgress.first.toFloat() / pingProgress.second.toFloat()
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search by country, hostname, IP, operator...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Filter & Sort Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Country Filter Chip
                Box {
                    ElevatedFilterChip(
                        selected = selectedCountryFilter != null,
                        onClick = { showCountryMenu = true },
                        label = { Text(selectedCountryFilter ?: "All Countries", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenu(
                        expanded = showCountryMenu,
                        onDismissRequest = { showCountryMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Countries") },
                            onClick = {
                                selectedCountryFilter = null
                                showCountryMenu = false
                            }
                        )
                        countryCounts.take(30).forEach { (country, count) ->
                            DropdownMenuItem(
                                text = { Text("$country ($count)") },
                                onClick = {
                                    selectedCountryFilter = country
                                    showCountryMenu = false
                                }
                            )
                        }
                    }
                }

                // Sort Chip
                Box {
                    ElevatedFilterChip(
                        selected = sortOption != SortOption.RECOMMENDED,
                        onClick = { showSortMenu = true },
                        label = { Text("Sort: ${sortOption.title}", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.title) },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Protocol Filter Chips
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedProtocolFilter == null,
                    onClick = { selectedProtocolFilter = null },
                    label = { Text("All Protocols", fontSize = 11.sp) }
                )
                Protocol.ORDER.forEach { proto ->
                    FilterChip(
                        selected = selectedProtocolFilter == proto,
                        onClick = {
                            selectedProtocolFilter = if (selectedProtocolFilter == proto) null else proto
                        },
                        label = { Text(proto.shortLabel, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Servers LazyColumn
            if (filteredServers.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No servers match your current filters.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp
                    )
                ) {
                    items(filteredServers, key = { it.ip.ifEmpty { it.hostname } }) { server ->
                        ServerCard(
                            server = server,
                            isSelected = activeServer?.ip == server.ip,
                            onSelect = {
                                EventBus.setActiveServer(server)
                            },
                            onConnect = {
                                EventBus.setActiveServer(server)
                                val proto = server.supportedProtocols.firstOrNull() ?: Protocol.SSLVP
                                onConnectServer(server, proto)
                            },
                            onShowDetails = {
                                detailSheetServer = server
                            }
                        )
                    }
                }
            }
        }

        // Details Bottom Sheet
        detailSheetServer?.let { server ->
            ServerDetailsSheet(
                server = server,
                sheetState = sheetState,
                onDismiss = { detailSheetServer = null },
                onConnectWithProtocol = { proto ->
                    EventBus.setActiveServer(server)
                    onConnectServer(server, proto)
                }
            )
        }
    }
}
