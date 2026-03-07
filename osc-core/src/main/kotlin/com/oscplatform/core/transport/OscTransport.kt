package com.oscplatform.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

data class TransportError(
    val cause: Throwable,
    val consecutiveCount: Int,
)

interface OscTransport {
  val incomingPackets: Flow<OscPacket>

  /** 受信ループ中に発生したエラーを通知するFlow。未実装の実装はemptyFlowを返す。 */
  val errors: Flow<TransportError>
    get() = emptyFlow()

  suspend fun start()

  suspend fun stop()

  suspend fun send(packet: OscPacket, target: OscTarget)
}
