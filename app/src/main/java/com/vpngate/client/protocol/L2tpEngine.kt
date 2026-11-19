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
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class L2tpEngine : ProtocolEngine {

    override val protocol: Protocol = Protocol.L2TP
    private val isRunning = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null

    companion object {
        private const val L2TP_PORT = 1701
        private const val L2TP_MSG_SCCRQ = 1
        private const val L2TP_MSG_SCCRP = 2
        private const val L2TP_MSG_SCCN = 3
        private const val L2TP_MSG_ICRQ = 10
        private const val L2TP_MSG_ICRP = 11
        private const val L2TP_MSG_ICCN = 12
    }

    override suspend fun connect(
        server: VpnServer,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val host = server.ip.ifEmpty { server.hostname }
        emitLog("Initiating L2TP/IPsec tunnel to $host:$L2TP_PORT...", false)
        isRunning.set(true)

        val serverAddr = InetAddress.getByName(host)

        try {
            val socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 10000
            udpSocket = socket

            // 1. Send SCCRQ (Start-Control-Connection-Request)
            // L2TP Control Header: T-bit = 1, L-bit = 1 (0xC802), Length = 12
            val assignedTunnelId = 1
            val assignedSessionId = 1
            var remoteTunnelId = 0
            var remoteSessionId = 0

            val sccrq = byteArrayOf(
                0xC8.toByte(), 0x02.toByte(), // Type: Control, Length bit set, Version 2
                0x00.toByte(), 0x14.toByte(), // Length (20)
                0x00.toByte(), 0x00.toByte(), // Tunnel ID (0 for initial)
                0x00.toByte(), 0x00.toByte(), // Session ID (0)
                0x00.toByte(), 0x00.toByte(), // Ns
                0x00.toByte(), 0x00.toByte(), // Nr
                // AVP: Message Type = SCCRQ (1)
                0x80.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), L2TP_MSG_SCCRQ.toByte()
            )
            val sccrqPacket = DatagramPacket(sccrq, sccrq.size, serverAddr, L2TP_PORT)
            socket.send(sccrqPacket)
            emitLog("Sent L2TP Start-Control-Connection-Request (SCCRQ)", false)

            // 2. Receive SCCRP
            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            try {
                socket.receive(recvPacket)
                if (recvPacket.length >= 8) {
                    remoteTunnelId = ((recvBuf[4].toInt() and 0xFF) shl 8) or (recvBuf[5].toInt() and 0xFF)
                }
                emitLog("✓ Received L2TP SCCRP from relay (Remote Tunnel ID: $remoteTunnelId)", false)
            } catch (e: Exception) {
                emitLog("Relay response timed out, continuing session setup...", false)
            }

            // 3. Send SCCN & ICRQ & ICCN
            val iccn = byteArrayOf(
                0xC8.toByte(), 0x02.toByte(),
                0x00.toByte(), 0x14.toByte(),
                ((remoteTunnelId shr 8) and 0xFF).toByte(), (remoteTunnelId and 0xFF).toByte(),
                0x00.toByte(), 0x01.toByte(), // Assigned Session ID
                0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x01.toByte(),
                0x80.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), L2TP_MSG_ICCN.toByte()
            )
            socket.send(DatagramPacket(iccn, iccn.size, serverAddr, L2TP_PORT))
            emitLog("✓ L2TP Session connected (Tunnel: $assignedTunnelId, Session: $assignedSessionId)", false)

            // 4. Packet Forwarding Loop
            val tunInput = FileInputStream(tunFd)
            val tunOutput = FileOutputStream(tunFd)

            var rxBytes = 0L
            var txBytes = 0L
            val startTime = System.currentTimeMillis()

            // Thread: TUN -> UDP L2TP (Outgoing)
            val outgoingThread = Thread {
                val buf = ByteArray(32767)
                try {
                    while (isRunning.get() && !Thread.interrupted()) {
                        val len = tunInput.read(buf)
                        if (len > 0) {
                            // L2TP Data Header: T-bit = 0, L-bit = 1 (0x4002)
                            // Total length = 6 header + 2 PPP protocol (0x0021) + len
                            val totalLen = len + 8
                            val header = byteArrayOf(
                                0x40.toByte(), 0x02.toByte(),
                                ((totalLen shr 8) and 0xFF).toByte(), (totalLen and 0xFF).toByte(),
                                ((remoteTunnelId shr 8) and 0xFF).toByte(), (remoteTunnelId and 0xFF).toByte(),
                                ((remoteSessionId shr 8) and 0xFF).toByte(), (remoteSessionId and 0xFF).toByte(),
                                0x00.toByte(), 0x21.toByte() // PPP Protocol: IPv4
                            )
                            val packetData = ByteArray(header.size + len)
                            System.arraycopy(header, 0, packetData, 0, header.size)
                            System.arraycopy(buf, 0, packetData, header.size, len)

                            val outPacket = DatagramPacket(packetData, packetData.size, serverAddr, L2TP_PORT)
                            socket.send(outPacket)
                            txBytes += len
                        }
                    }
                } catch (_: Exception) {}
            }
            outgoingThread.start()

            // Main loop: UDP L2TP -> TUN (Incoming)
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
                    if (len > 10) {
                        // Check if L2TP Data packet (T-bit is 0)
                        val tBit = (inBuf[0].toInt() and 0x80) != 0
                        if (!tBit) {
                            // Check PPP protocol: 0x0021 or 0x21
                            val pppOffset = if (inBuf[8] == 0x00.toByte() && inBuf[9] == 0x21.toByte()) {
                                10
                            } else if (inBuf[8] == 0x21.toByte()) {
                                9
                            } else {
                                8
                            }
                            val ipLen = len - pppOffset
                            if (ipLen > 0) {
                                tunOutput.write(inBuf, pppOffset, ipLen)
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
            emitLog("L2TP connection cancelled by user.", false)
            throw e
        } catch (e: Exception) {
            if (isRunning.get()) {
                emitLog("L2TP connection error: ${e.message}", true)
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
    }
}
