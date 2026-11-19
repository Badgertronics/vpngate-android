package com.vpngate.client.protocol

import android.net.VpnService
import com.vpngate.client.data.Protocol
import com.vpngate.client.data.VpnServer
import java.io.FileDescriptor

interface ProtocolEngine {
    val protocol: Protocol

    /**
     * Connect to the remote server and start forwarding IP packets through the TUN interface.
     * Blocks or runs a coroutine until disconnected or an error occurs.
     */
    suspend fun connect(
        server: VpnServer,
        vpnService: VpnService,
        tunFd: FileDescriptor,
        emitLog: (String, Boolean) -> Unit
    )

    /**
     * Stop and terminate any active tunnel connections immediately.
     */
    fun disconnect()
}
