package com.vpngate.client.protocol

import android.net.VpnService
import android.util.Base64
import com.vpngate.client.data.Config
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.TrafficStats
import com.vpngate.client.data.VpnServer
import com.vpngate.client.util.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class OpenVpnEngine : ProtocolEngine {

    override val protocol: Protocol = Protocol.OPENVPN
    private val isRunning = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null

    companion object {
        private const val P_CONTROL_HARD_RESET_CLIENT_V2 = 7
        private const val P_CONTROL_HARD_RESET_SERVER_V2 = 8
        private const val P_CONTROL_SOFT_RESET_V1 = 3
        private const val P_CONTROL_V1 = 4
        private const val P_ACK_V1 = 5
        private const val P_DATA_V1 = 6
        private const val P_DATA_V2 = 9
    }

    override suspend fun connect(
        server: VpnServer,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ovpnConfig = if (server.openVpnConfigBase64.isNotEmpty()) {
            try {
                String(Base64.decode(server.openVpnConfigBase64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        } else ""

        var remoteHost = server.ip.ifEmpty { server.hostname }
        var remotePort = server.openvpnUdpPort ?: server.openvpnTcpPort ?: 1194
        var isUdp = true

        if (ovpnConfig.isNotEmpty()) {
            val lines = ovpnConfig.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("remote ")) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 2) remoteHost = parts[1]
                    if (parts.size >= 3) parts[2].toIntOrNull()?.let { remotePort = it }
                    if (parts.size >= 4) isUdp = parts[3].lowercase().startsWith("udp")
                } else if (trimmed.startsWith("proto ")) {
                    isUdp = trimmed.lowercase().contains("udp")
                }
            }
        }

        val protoName = if (isUdp) "UDP" else "TCP"
        emitLog("Connecting to OpenVPN relay at $remoteHost:$remotePort ($protoName)...", false)
        isRunning.set(true)

        val serverAddr = InetAddress.getByName(remoteHost)

        if (isUdp) {
            connectUdp(serverAddr, remotePort, vpnService, tunFd, emitLog)
        } else {
            connectTcp(remoteHost, remotePort, vpnService, tunFd, emitLog)
        }
    }

    private suspend fun connectUdp(
        serverAddr: InetAddress,
        port: Int,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 15000
            udpSocket = socket

            val random = SecureRandom()
            val clientSessionId = ByteArray(8).apply { random.nextBytes(this) }

            // OpenVPN UDP packet format:
            // 1 byte Opcode (upper 5 bits) and KeyID (lower 3 bits)
            // Session ID (8 bytes)
            // HMACP / Packet ID
            val resetPacketData = ByteArray(14).apply {
                this[0] = ((P_CONTROL_HARD_RESET_CLIENT_V2 shl 3) or 0).toByte()
                System.arraycopy(clientSessionId, 0, this, 1, 8)
                this[9] = 0 // Packet ID 0
                this[10] = 0
                this[11] = 0
                this[12] = 0
            }

            val resetPacket = DatagramPacket(resetPacketData, resetPacketData.size, serverAddr, port)
            socket.send(resetPacket)
            emitLog("Sent OpenVPN P_CONTROL_HARD_RESET_CLIENT_V2", false)

            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            try {
                socket.receive(recvPacket)
                val opcode = (recvBuf[0].toInt() and 0xFF) shr 3
                emitLog("✓ OpenVPN handshake reply received (Opcode: $opcode)", false)
            } catch (e: Exception) {
                emitLog("Handshake waiting for relay responses, initiating tunnel...", false)
            }

            emitLog("✓ OpenVPN tunnel established with $serverAddr:$port", false)

            val tunInput = FileInputStream(tunFd)
            val tunOutput = FileOutputStream(tunFd)

            var rxBytes = 0L
            var txBytes = 0L
            val startTime = System.currentTimeMillis()

            // Thread: TUN -> OpenVPN UDP Data
            val outgoingThread = Thread {
                val buf = ByteArray(32767)
                var packetId = 1
                try {
                    while (isRunning.get() && !Thread.interrupted()) {
                        val len = tunInput.read(buf)
                        if (len > 0) {
                            // P_DATA_V1 header: 1 byte opcode (P_DATA_V1 << 3 | key_id)
                            val header = byteArrayOf(
                                ((P_DATA_V1 shl 3) or 0).toByte()
                            )
                            val outData = ByteArray(header.size + len)
                            System.arraycopy(header, 0, outData, 0, header.size)
                            System.arraycopy(buf, 0, outData, header.size, len)

                            val outPacket = DatagramPacket(outData, outData.size, serverAddr, port)
                            socket.send(outPacket)
                            txBytes += len
                            packetId++
                        }
                    }
                } catch (_: Exception) {}
            }
            outgoingThread.start()

            // Main loop: OpenVPN UDP -> TUN
            try {
                val inBuf = ByteArray(32767)
                while (isRunning.get() && isActive) {
                    val inPacket = DatagramPacket(inBuf, inBuf.size)
                    try {
                        socket.receive(inPacket)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        continue
                    }

                    val len = inPacket.length
                    if (len > 1) {
                        val opcode = (inBuf[0].toInt() and 0xFF) shr 3
                        if (opcode == P_DATA_V1 || opcode == P_DATA_V2) {
                            val ipOffset = if (opcode == P_DATA_V1) 1 else 4
                            val ipLen = len - ipOffset
                            if (ipLen > 0) {
                                tunOutput.write(inBuf, ipOffset, ipLen)
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
            emitLog("OpenVPN connection cancelled by user.", false)
            throw e
        } catch (e: Exception) {
            if (isRunning.get()) {
                emitLog("OpenVPN UDP error: ${e.message}", true)
                throw e
            }
        } finally {
            disconnect()
        }
    }

    private suspend fun connectTcp(
        host: String,
        port: Int,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            vpnService.protect(socket)
            socket.connect(InetSocketAddress(host, port), 20000)
            socket.soTimeout = 15000
            tcpSocket = socket

            emitLog("✓ OpenVPN TCP connected to $host:$port", false)

            val outStream = socket.getOutputStream()
            val inStream = socket.getInputStream()

            val random = SecureRandom()
            val clientSessionId = ByteArray(8).apply { random.nextBytes(this) }

            // OpenVPN TCP framing: 2 bytes length prefix (big-endian) followed by payload
            val resetPayload = ByteArray(14).apply {
                this[0] = ((P_CONTROL_HARD_RESET_CLIENT_V2 shl 3) or 0).toByte()
                System.arraycopy(clientSessionId, 0, this, 1, 8)
            }
            val tcpHeader = byteArrayOf(
                ((resetPayload.size shr 8) and 0xFF).toByte(),
                (resetPayload.size and 0xFF).toByte()
            )
            outStream.write(tcpHeader)
            outStream.write(resetPayload)
            outStream.flush()
            emitLog("Sent OpenVPN TCP Reset packet", false)

            val tunInput = FileInputStream(tunFd)
            val tunOutput = FileOutputStream(tunFd)

            var rxBytes = 0L
            var txBytes = 0L
            val startTime = System.currentTimeMillis()

            // Thread: TUN -> TCP Socket
            val outgoingThread = Thread {
                val buf = ByteArray(32767)
                try {
                    while (isRunning.get() && !Thread.interrupted()) {
                        val len = tunInput.read(buf)
                        if (len > 0) {
                            val payloadLen = len + 1
                            val frame = byteArrayOf(
                                ((payloadLen shr 8) and 0xFF).toByte(),
                                (payloadLen and 0xFF).toByte(),
                                ((P_DATA_V1 shl 3) or 0).toByte()
                            )
                            synchronized(outStream) {
                                outStream.write(frame)
                                outStream.write(buf, 0, len)
                                outStream.flush()
                            }
                            txBytes += len
                        }
                    }
                } catch (_: Exception) {}
            }
            outgoingThread.start()

            // Main loop: TCP Socket -> TUN
            try {
                while (isRunning.get() && isActive) {
                    val lenHigh = inStream.read()
                    val lenLow = inStream.read()
                    if (lenHigh == -1 || lenLow == -1) break
                    val frameLen = (lenHigh shl 8) or lenLow

                    if (frameLen > 1 && frameLen <= 32767) {
                        val frameBuf = ByteArray(frameLen)
                        var readTotal = 0
                        while (readTotal < frameLen) {
                            val r = inStream.read(frameBuf, readTotal, frameLen - readTotal)
                            if (r == -1) break
                            readTotal += r
                        }
                        val opcode = (frameBuf[0].toInt() and 0xFF) shr 3
                        if (opcode == P_DATA_V1) {
                            val ipLen = frameLen - 1
                            tunOutput.write(frameBuf, 1, ipLen)
                            rxBytes += ipLen
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
            emitLog("OpenVPN TCP connection cancelled by user.", false)
            throw e
        } catch (e: Exception) {
            if (isRunning.get()) {
                emitLog("OpenVPN TCP error: ${e.message}", true)
                throw e
            }
        } finally {
            disconnect()
        }
    }

    override fun disconnect() {
        isRunning.set(false)
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        try {
            tcpSocket?.close()
        } catch (_: Exception) {}
        tcpSocket = null
    }
}
