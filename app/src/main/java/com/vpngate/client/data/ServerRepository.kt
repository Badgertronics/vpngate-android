package com.vpngate.client.data

import android.content.Context
import com.vpngate.client.util.EventBus
import com.vpngate.client.util.PingUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ServerRepository(private val context: Context, private val prefs: PreferencesManager) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isPingingAll = MutableStateFlow(false)
    val isPingingAll: StateFlow<Boolean> = _isPingingAll.asStateFlow()

    private val _pingProgress = MutableStateFlow(Pair(0, 0))
    val pingProgress: StateFlow<Pair<Int, Int>> = _pingProgress.asStateFlow()

    suspend fun loadInitialData() = withContext(Dispatchers.IO) {
        if (_servers.value.isNotEmpty()) return@withContext
        try {
            // Load seeded asset cache
            context.assets.open("servers_cache.csv").use { stream ->
                val list = parseCsv(stream)
                if (list.isNotEmpty()) {
                    _servers.value = list
                    EventBus.log("Repository", "Loaded ${list.size} servers from initial seed cache.")
                }
            }
        } catch (e: Exception) {
            EventBus.log("Repository", "Initial seed load failed: ${e.message}", isError = true)
        }
    }

    /**
     * Multi-tier fetch:
     * 1. Priority 1: GitHub Raw / CDN servers.csv
     * 2. Priority 2: VPNGate live mirrors (/api/iphone/)
     * 3. Priority 3: Bundled seed assets
     */
    suspend fun refreshServers(): Boolean = withContext(Dispatchers.IO) {
        _isLoading.value = true
        EventBus.log("Repository", "Refreshing server list...")

        val previousPingMap = _servers.value.associate { (it.ip.ifEmpty { it.hostname }) to it.realPingMs }

        var fetchedList: List<VpnServer>? = null

        // 1. Try Priority 1 (CDN / Raw)
        for (url in Config.REPO_CSV_URLS) {
            try {
                EventBus.log("Repository", "Attempting fetch from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Config.USER_AGENT)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val bodyStream = response.body!!.byteStream()
                        val list = parseCsv(bodyStream)
                        if (list.size >= 10) {
                            fetchedList = list
                            EventBus.log("Repository", "✓ Successfully loaded ${list.size} servers from CDN.")
                            return@use
                        }
                    }
                }
                if (fetchedList != null) break
            } catch (e: Exception) {
                EventBus.log("Repository", "CDN fetch failed: ${e.message}")
            }
        }

        // 2. Try Priority 2 (Live Mirrors)
        if (fetchedList == null) {
            val mirrors = prefs.mirrors.value
            for (mirror in mirrors) {
                try {
                    val mirrorUrl = if (mirror.endsWith("/")) "${mirror}api/iphone/" else "$mirror/api/iphone/"
                    EventBus.log("Repository", "Attempting mirror: $mirrorUrl")
                    val request = Request.Builder()
                        .url(mirrorUrl)
                        .header("User-Agent", Config.USER_AGENT)
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful && response.body != null) {
                            val list = parseCsv(response.body!!.byteStream())
                            if (list.size >= 10) {
                                fetchedList = list
                                EventBus.log("Repository", "✓ Successfully loaded ${list.size} servers from mirror.")
                                return@use
                            }
                        }
                    }
                    if (fetchedList != null) break
                } catch (e: Exception) {
                    EventBus.log("Repository", "Mirror $mirror failed: ${e.message}")
                }
            }
        }

        if (fetchedList != null) {
            // Preserve measured pings across refreshes!
            for (server in fetchedList) {
                val key = server.ip.ifEmpty { server.hostname }
                if (previousPingMap.containsKey(key)) {
                    server.realPingMs = previousPingMap[key]
                }
            }
            _servers.value = fetchedList
            _isLoading.value = false
            return@withContext true
        }

        _isLoading.value = false
        EventBus.log("Repository", "Failed to refresh server list from online sources.", isError = true)
        return@withContext false
    }

    suspend fun pingAll(serversToPing: List<VpnServer> = _servers.value) = withContext(Dispatchers.IO) {
        if (_isPingingAll.value) return@withContext
        _isPingingAll.value = true
        _pingProgress.value = Pair(0, serversToPing.size)
        EventBus.log("Repository", "Starting ping test for ${serversToPing.size} servers...")

        try {
            PingUtil.pingServersConcurrently(
                servers = serversToPing,
                maxConcurrency = 20,
                onProgress = { completed, total, _ ->
                    _pingProgress.value = Pair(completed, total)
                }
            )
            // Trigger state flow update so UI reflects updated ping values
            _servers.value = _servers.value.toList()
            EventBus.log("Repository", "Ping test completed.")
        } catch (e: Exception) {
            EventBus.log("Repository", "Ping test cancelled or interrupted: ${e.message}")
        } finally {
            _isPingingAll.value = false
        }
    }

    fun stopPingAll() {
        _isPingingAll.value = false
    }

    private fun parseCsv(stream: InputStream): List<VpnServer> {
        val result = mutableListOf<VpnServer>()
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var headerIndexMap: Map<String, Int>? = null

        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("*")) return@forEachLine

            if (trimmed.startsWith("#")) {
                val headerTokens = trimmed.substring(1).split(",").map { it.trim().lowercase() }
                headerIndexMap = headerTokens.mapIndexed { index, name -> name to index }.toMap()
                return@forEachLine
            }

            val map = headerIndexMap ?: return@forEachLine
            val tokens = splitCsvLine(trimmed)

            fun getStr(name: String): String =
                map[name]?.let { idx -> if (idx < tokens.size) tokens[idx] else "" } ?: ""
            fun getLong(name: String): Long = getStr(name).toLongOrNull() ?: 0L
            fun getInt(name: String): Int = getStr(name).toIntOrNull() ?: 0
            fun getDouble(name: String): Double = getStr(name).toDoubleOrNull() ?: 0.0

            val hostname = getStr("hostname").ifEmpty { getStr("host_name") }
            val ip = getStr("ip")
            if (hostname.isEmpty() && ip.isEmpty()) return@forEachLine

            val rawCountry = getStr("countrylong").ifEmpty { getStr("country") }
            val rawCode = getStr("countryshort").ifEmpty { getStr("country_code") }
            val (country, countryCode) = CountryData.normalizeCountry(rawCountry, rawCode)

            val rawSpeed = getDouble("speed")
            val speedMbps = if (rawSpeed > 0) rawSpeed / 1_000_000.0 else 0.0

            val rawTraffic = getLong("totaltraffic").takeIf { it > 0 } ?: getLong("total_traffic")
            val rawConfigB64 = getStr("openvpn_configdata_base64").ifEmpty { getStr("openvpn_config") }

            // Extract protocol ports if available
            val sslPort = 443 // VPN Gate servers always expose SoftEther SSL-VPN on 443 / custom
            val sstpHost = hostname.ifEmpty { ip }
            val sstpPort = 443

            val server = VpnServer(
                hostname = hostname,
                ip = ip,
                score = getLong("score"),
                pingMs = getInt("ping"),
                speedMbps = speedMbps,
                country = country,
                countryCode = countryCode,
                sessions = getInt("numvpnsessions").takeIf { it > 0 } ?: getInt("sessions"),
                uptimeSeconds = getLong("uptime"),
                totalUsers = getLong("totalusers"),
                totalTrafficBytes = rawTraffic,
                logPolicy = getStr("logtype").ifEmpty { getStr("log_policy") },
                operator = getStr("operator"),
                openVpnConfigBase64 = rawConfigB64,
                sslVpnPort = sslPort,
                l2tpSupported = true,
                openvpnTcpPort = 443,
                openvpnUdpPort = 1194,
                sstpHost = sstpHost,
                sstpPort = sstpPort
            )
            result.add(server)
        }

        return result
    }

    private fun splitCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    sb.append('\"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        tokens.add(sb.toString().trim())
        return tokens
    }
}
