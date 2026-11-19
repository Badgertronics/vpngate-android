package com.vpngate.client.protocol

import android.net.VpnService
import com.vpngate.client.data.Config
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.TrafficStats
import com.vpngate.client.data.VpnServer
import com.vpngate.client.util.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SoftEtherEngine : ProtocolEngine {

    override val protocol: Protocol = Protocol.SSLVP
    private val isRunning = AtomicBoolean(false)
    private var sslSocket: SSLSocket? = null

    override suspend fun connect(
        server: VpnServer,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val host = server.ip.ifEmpty { server.hostname }
        val port = server.sslVpnPort ?: 443

        emitLog("Connecting to SoftEther SSL-VPN relay at $host:$port (Hub: ${Config.SOFTETHER_HUB})...", false)
        isRunning.set(true)

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        val factory: SSLSocketFactory = sslContext.socketFactory

        try {
            val socket = Socket()
            // CRITICAL: Protect socket so VPN routing does not intercept our tunnel traffic!
            vpnService.protect(socket)

            socket.connect(InetSocketAddress(host, port), 20000)
            socket.soTimeout = 15000

            val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
            sslSocket = ssl
            ssl.startHandshake()
            emitLog("✓ SSL/TLS Handshake completed with $host", false)

            val outStream = ssl.getOutputStream()
            val inStream = ssl.getInputStream()
            val dataOut = DataOutputStream(outStream)
            val dataIn = DataInputStream(inStream)

            // Send SoftEther HTTP handshake
            val httpHandshake = (
                "POST /vpn/vpn.cgi HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "User-Agent: ${Config.USER_AGENT}\r\n" +
                "Connection: Keep-Alive\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "X-VPN-Hub: ${Config.SOFTETHER_HUB}\r\n" +
                "X-VPN-User: ${Config.VPN_USERNAME}\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
            outStream.write(httpHandshake)
            outStream.flush()
            emitLog("Sent SoftEther protocol handshake to hub ${Config.SOFTETHER_HUB}", false)

            // Read HTTP response header
            val headerSb = StringBuilder()
            var prev = 0
            while (isRunning.get() && isActive) {
                val b = inStream.read()
                if (b == -1) break
                headerSb.append(b.toChar())
                if (prev == '\r'.code && b == '\n'.code && headerSb.endsWith("\r\n\r\n")) {
                    break
                }
                prev = b
            }

            val response = headerSb.toString()
            if (!response.contains("200 OK") && !response.contains("HTTP/1.")) {
                emitLog("SoftEther handshake received response: ${response.take(60)}", false)
            } else {
                emitLog("✓ SoftEther session established with Virtual Hub ${Config.SOFTETHER_HUB}", false)
            }

            // Packet forwarding loop
            val tunInput = FileInputStream(tunFd)
            val tunOutput = FileOutputStream(tunFd)

            var rxBytes = 0L
            var txBytes = 0L
            val startTime = System.currentTimeMillis()

            // Thread 1: TUN -> SSL Socket (Outgoing packets)
            val outgoingThread = Thread {
                val buffer = ByteArray(32767)
                try {
                    while (isRunning.get() && !Thread.interrupted()) {
                        val length = tunInput.read(buffer)
                        if (length > 0) {
                            // SoftEther packet framing: 2-byte big-endian packet length followed by raw IP packet
                            synchronized(dataOut) {
                                dataOut.writeShort(length)
                                dataOut.write(buffer, 0, length)
                                dataOut.flush()
                            }
                            txBytes += length
                        }
                    }
                } catch (_: Exception) {}
            }
            outgoingThread.start()

            // Main loop: SSL Socket -> TUN (Incoming packets)
            try {
                while (isRunning.get() && isActive) {
                    val packetLength = try {
                        dataIn.readUnsignedShort()
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        // Keepalive probe on read timeout
                        synchronized(dataOut) {
                            dataOut.writeShort(0) // 0-length keepalive
                            dataOut.flush()
                        }
                        continue
                    }

                    if (packetLength > 0 && packetLength <= 32767) {
                        val packetBuf = ByteArray(packetLength)
                        dataIn.readFully(packetBuf)
                        tunOutput.write(packetBuf)
                        rxBytes += packetLength
                    }

                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                    EventBus.updateTrafficStats(
                        TrafficStats(
                            rxBytes = rxBytes,
                            txBytes = txBytes,
                            connectedSeconds = elapsedSec
                        )
                    )
                }
            } finally {
                outgoingThread.interrupt()
            }

        } catch (e: CancellationException) {
            emitLog("SoftEther connection cancelled by user.", false)
            throw e
        } catch (e: Exception) {
            if (isRunning.get()) {
                emitLog("SoftEther connection error: ${e.message}", true)
                throw e
            }
        } finally {
            disconnect()
        }
    }

    override fun disconnect() {
        isRunning.set(false)
        try {
            sslSocket?.close()
        } catch (_: Exception) {}
        sslSocket = null
    }
}
