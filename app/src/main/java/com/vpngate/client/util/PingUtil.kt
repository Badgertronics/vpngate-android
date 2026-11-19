package com.vpngate.client.util

import com.vpngate.client.data.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object PingUtil {

    private const val DEFAULT_TIMEOUT_MS = 2500

    /**
     * Measure TCP connection latency to server in milliseconds.
     * Returns elapsed ms, or -1 if unreachable/timed out.
     */
    suspend fun pingServer(server: VpnServer, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int =
        withContext(Dispatchers.IO) {
            val host = server.ip.ifEmpty { server.hostname }
            val port = server.sslVpnPort
                ?: server.sstpPort
                ?: server.openvpnTcpPort
                ?: server.openvpnUdpPort
                ?: 443

            val start = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val elapsed = (System.currentTimeMillis() - start).toInt()
                server.realPingMs = elapsed
                elapsed
            } catch (e: Exception) {
                server.realPingMs = -1
                -1
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        }

    /**
     * Ping a list of servers concurrently with max parallelism.
     */
    suspend fun pingServersConcurrently(
        servers: List<VpnServer>,
        maxConcurrency: Int = 20,
        onProgress: (completed: Int, total: Int, server: VpnServer) -> Unit = { _, _, _ -> }
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        var completed = 0
        val total = servers.size

        val deferreds = servers.map { server ->
            async(Dispatchers.IO) {
                if (!isActive) return@async
                semaphore.withPermit {
                    if (!isActive) return@withPermit
                    pingServer(server)
                    synchronized(servers) {
                        completed++
                        onProgress(completed, total, server)
                    }
                }
            }
        }
        deferreds.awaitAll()
    }
}
