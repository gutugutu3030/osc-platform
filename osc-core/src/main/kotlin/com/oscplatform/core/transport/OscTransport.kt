package com.oscplatform.core.transport

import kotlinx.coroutines.flow.Flow

sealed interface OscPacket

data class OscMessagePacket(
    val address: String,
    val arguments: List<Any?>,
) : OscPacket

data class OscBundlePacket(
    val timeTag: Long,
    val elements: List<OscPacket>,
) : OscPacket

data class OscTarget(
    val host: String,
    val port: Int,
)

interface OscTransport {
  val incomingPackets: Flow<OscPacket>

  suspend fun start()

  suspend fun stop()

  suspend fun send(packet: OscPacket, target: OscTarget)
}
