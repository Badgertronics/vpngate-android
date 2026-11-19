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
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SstpEngine : ProtocolEngine {

    override val protocol: Protocol = Protocol.SSTP
    private val isRunning = AtomicBoolean(false)
    private var sslSocket: SSLSocket? = null

    companion object {
        private const val SSTP_MSG_CALL_CONNECT_REQUEST = 0x0001
        private const val SSTP_MSG_CALL_CONNECT_ACK = 0x0002
        private const val SSTP_MSG_CALL_CONNECTED = 0x0003
        private const val SSTP_MSG_ECHO_REQUEST = 0x0004
        private const val SSTP_MSG_ECHO_RESPONSE = 0x0005
    }

    override suspend fun connect(
        server: VpnServer,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val host = server.sstpHost.ifEmpty { server.ip.ifEmpty { server.hostname } }
        val port = server.sstpPort ?: 443

        emitLog("Connecting to MS-SSTP relay at $host:$port...", false)
        isRunning.set(true)

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())

        var socket: Socket? = null
        try {
            socket = Socket()
            vpnService.protect(socket)
            socket.connect(InetSocketAddress(host, port), 20000)
            socket.soTimeout = 15000

            val ssl = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            sslSocket = ssl
            ssl.startHandshake()
            emitLog("✓ SSL/TLS connected for MS-SSTP with $host", false)

            val outStream = ssl.getOutputStream()
            val inStream = ssl.getInputStream()
            val dataOut = DataOutputStream(outStream)
            val dataIn = DataInputStream(inStream)

            // 1. SSTP HTTP handshake
            val sstpHttp = (
                "SSTP_DUPLEX_POST /siss/ HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "User-Agent: ${Config.USER_AGENT}\r\n" +
                "Content-Length: 18446744073709551615\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
            outStream.write(sstpHttp)
            outStream.flush()

            // Read HTTP response status
            val headerSb = StringBuilder()
            var prev = 0
            while (isRunning.get() && isActive) {
                val b = inStream.read()
                if (b == -1) break
                headerSb.append(b.toChar())
                if (prev == '\r'.code && b == '\n'.code && headerSb.endsWith("\r\n\r\n")) break
                prev = b
            }

            emitLog("Received SSTP HTTP Handshake response", false)

            // 2. SSTP Control Handshake: Send CALL_CONNECT_REQUEST
            // SSTP Header: Major version (0x10), Control packet flag (0x01)
            // Length: 14 bytes (8 header + 6 encapsulated protocol attribute)
            val connectReq = byteArrayOf(
                0x10.toByte(), 0x01.toByte(), // Version & C-bit (Control)
                0x00.toByte(), 0x0E.toByte(), // Length (14)
                0x00.toByte(), SSTP_MSG_CALL_CONNECT_REQUEST.toByte(), // Message Type (0x0001)
                0x00.toByte(), 0x01.toByte(), // Num Attributes (1)
                0x01.toByte(), // Attribute ID: Encapsulated Protocol ID
                0x00.toByte(), 0x06.toByte(), // Length (6)
                0x00.toByte(), 0x01.toByte()  // Protocol ID: PPP (0x0001)
            )
            outStream.write(connectReq)
            outStream.flush()
            emitLog("Sent SSTP_MSG_CALL_CONNECT_REQUEST", false)

            // Send SSTP_MSG_CALL_CONNECTED
            val connectedMsg = byteArrayOf(
                0x10.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x08.toByte(),
                0x00.toByte(), SSTP_MSG_CALL_CONNECTED.toByte(),
                0x00.toByte(), 0x00.toByte()
            )
            outStream.write(connectedMsg)
            outStream.flush()
            emitLog("✓ MS-SSTP control connection established", false)

            // 3. Tunneling Data Loop
            val tunInput = FileInputStream(tunFd)
            val tunOutput = FileOutputStream(tunFd)

            var rxBytes = 0L
            var txBytes = 0L
            val startTime = System.currentTimeMillis()

            // Thread: TUN -> SSTP Data Packet (Outgoing)
            val outgoingThread = Thread {
                val buf = ByteArray(32767)
                try {
                    while (isRunning.get() && !Thread.interrupted()) {
                        val len = tunInput.read(buf)
                        if (len > 0) {
                            // SSTP Data packet header: 0x10, 0x00 (Data), Length (len + 4 + 1 for PPP Protocol 0x21 IPv4)
                            val totalLen = len + 5
                            val sstpHeader = byteArrayOf(
                                0x10.toByte(), 0x00.toByte(),
                                ((totalLen shr 8) and 0x0F).toByte(),
                                (totalLen and 0xFF).toByte(),
                                0x21.toByte() // PPP Protocol: IPv4
                            )
                            synchronized(dataOut) {
                                dataOut.write(sstpHeader)
                                dataOut.write(buf, 0, len)
                                dataOut.flush()
                            }
                            txBytes += len
                        }
                    }
                } catch (_: Exception) {}
            }
            outgoingThread.start()

            // Main loop: SSTP Data -> TUN (Incoming)
            try {
                while (isRunning.get() && isActive) {
                    val headerByte1 = try {
                        dataIn.readUnsignedByte()
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        // Send echo request keepalive on timeout
                        val echo = byteArrayOf(
                            0x10.toByte(), 0x01.toByte(),
                            0x00.toByte(), 0x08.toByte(),
                            0x00.toByte(), SSTP_MSG_ECHO_REQUEST.toByte(),
                            0x00.toByte(), 0x00.toByte()
                        )
                        synchronized(dataOut) {
                            dataOut.write(echo)
                            dataOut.flush()
                        }
                        continue
                    }

                    if (headerByte1 != 0x10) continue
                    val isControl = (dataIn.readUnsignedByte() and 0x01) != 0
                    val lenHigh = dataIn.readUnsignedByte() and 0x0F
                    val lenLow = dataIn.readUnsignedByte()
                    val packetLength = (lenHigh shl 8) or lenLow

                    if (packetLength > 4 && packetLength <= 32767) {
                        val payloadLen = packetLength - 4
                        val payload = ByteArray(payloadLen)
                        dataIn.readFully(payload)

                        if (!isControl && payloadLen > 1) {
                            // PPP Data: check if protocol is IPv4 (0x21 or 0x0021)
                            val ipOffset = if (payload[0] == 0x21.toByte()) {
                                1
                            } else if (payloadLen > 2 && payload[0] == 0x00.toByte() && payload[1] == 0x21.toByte()) {
                                2
                            } else {
                                0
                            }
                            val ipLen = payloadLen - ipOffset
                            if (ipLen > 0) {
                                tunOutput.write(payload, ipOffset, ipLen)
                                rxBytes += ipLen
                            }
                        }
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
            emitLog("MS-SSTP connection cancelled by user.", false)
            throw e
        } catch (e: Exception) {
            if (isRunning.get()) {
                emitLog("MS-SSTP error: ${e.message}", true)
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
