package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

class UdpOscTransport(
    private val bindHost: String,
    private val bindPort: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : OscTransport {
    private val _incomingPackets = MutableSharedFlow<OscPacket>(extraBufferCapacity = 256)
    override val incomingPackets: Flow<OscPacket> = _incomingPackets.asSharedFlow()

    @Volatile
    private var receiveSocket: DatagramSocket? = null
    private var receiveJob: Job? = null

    override suspend fun start() {
        if (receiveSocket != null) {
            return
        }

        val hostAddress = InetAddress.getByName(bindHost)
        val socket = DatagramSocket(bindPort, hostAddress)
        receiveSocket = socket
        receiveJob = scope.launch {
            receiveLoop(socket)
        }
    }

    override suspend fun stop() {
        receiveSocket?.close()
        receiveSocket = null
        receiveJob?.cancel()
        receiveJob = null
    }

    override suspend fun send(packet: OscPacket, target: OscTarget) {
        val bytes = OscCodec.encode(packet)
        withContext(Dispatchers.IO) {
            val socket = receiveSocket
            if (socket != null && !socket.isClosed) {
                sendDatagram(socket, bytes, target)
            } else {
                DatagramSocket().use { ephemeral ->
                    sendDatagram(ephemeral, bytes, target)
                }
            }
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(65535)
        while (scope.isActive && !socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
                val payload = datagram.data.copyOf(datagram.length)
                val packet = OscCodec.decode(payload)
                _incomingPackets.emit(packet)
            } catch (socketClosed: SocketException) {
                if (!socket.isClosed) {
                    throw socketClosed
                }
            } catch (_: Exception) {
                // Keep receive loop alive for malformed packets.
                delay(2)
            }
        }
    }

    private fun sendDatagram(
        socket: DatagramSocket,
        payload: ByteArray,
        target: OscTarget,
    ) {
        val targetAddress = InetAddress.getByName(target.host)
        val datagram = DatagramPacket(payload, payload.size, targetAddress, target.port)
        socket.send(datagram)
    }
}
