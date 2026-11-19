package com.vpngate.client.service

import com.vpngate.client.data.PreferencesManager
import com.vpngate.client.util.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class WatchdogService(
    private val prefs: PreferencesManager,
    private val onConnectivityLost: () -> Unit
) {

    private var watchdogJob: Job? = null
    private var consecutiveFailures = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun start(scope: CoroutineScope) {
        stop()
        consecutiveFailures = 0
        watchdogJob = scope.launch(Dispatchers.IO) {
            EventBus.log("Watchdog", "Connectivity watchdog started.")
            while (isActive) {
                val intervalSec = prefs.watchdogInterval.value.coerceAtLeast(5)
                delay(intervalSec * 1000L)
                if (!isActive) break

                if (!prefs.watchdogEnabled.value) {
                    continue
                }

                val targets = prefs.connectivityTargets.value
                if (targets.isEmpty()) {
                    continue
                }

                val isHealthy = checkConnectivity(targets)
                if (isHealthy) {
                    if (consecutiveFailures > 0) {
                        EventBus.log("Watchdog", "✓ Connectivity restored after $consecutiveFailures failed attempts.")
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    val maxFailures = prefs.watchdogFailures.value.coerceAtLeast(1)
                    EventBus.log(
                        "Watchdog",
                        "⚠ Health check failed ($consecutiveFailures/$maxFailures consecutive failures).",
                        isError = true
                    )

                    if (consecutiveFailures >= maxFailures) {
                        EventBus.log(
                            "Watchdog",
                            "✗ Max failure threshold reached ($maxFailures). Triggering loss of connectivity action!",
                            isError = true
                        )
                        onConnectivityLost()
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        consecutiveFailures = 0
    }

    private suspend fun checkConnectivity(targets: Set<String>): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false

        for (target in targets) {
            val success = probeTarget(target)
            if (success) {
                anySuccess = true
                break // If at least one active target responds, internet route is working!
            }
        }

        anySuccess
    }

    private fun probeTarget(target: String): Boolean {
        val trimmed = target.trim()
        return try {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val req = Request.Builder().url(trimmed).head().build()
                httpClient.newCall(req).execute().use { it.isSuccessful || it.code in 200..399 }
            } else if (trimmed.contains(".")) {
                // Check if IP address or hostname
                val isIp = trimmed.all { it.isDigit() || it == '.' }
                if (isIp) {
                    // Probe DNS port 53 or HTTPS 443
                    val s = Socket()
                    s.connect(InetSocketAddress(trimmed, 53), 3000)
                    s.close()
                    true
                } else {
                    val url = "https://$trimmed"
                    val req = Request.Builder().url(url).head().build()
                    httpClient.newCall(req).execute().use { it.isSuccessful || it.code in 200..399 }
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
