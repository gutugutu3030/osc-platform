package com.oscplatform.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * OSCパケットを表すシールドインターフェース。
 *
 * [OscMessagePacket] または [OscBundlePacket] のいずれかとして具体化される。
 */
sealed interface OscPacket

/**
 * 単一のOSCメッセージパケット。
 *
 * @property address OSCアドレスパターン
 * @property arguments メッセージの引数リスト
 */
data class OscMessagePacket(
    val address: String,
    val arguments: List<Any?>,
) : OscPacket

/**
 * 複数のOSCパケットをまとめるバンドルパケット。
 *
 * @property timeTag OSCタイムタグ
 * @property elements バンドルに含まれるパケットのリスト
 */
data class OscBundlePacket(
    val timeTag: Long,
    val elements: List<OscPacket>,
) : OscPacket

/**
 * OSCパケットの送信先を表すデータクラス。
 *
 * @property host 送信先ホスト名またはIPアドレス
 * @property port 送信先ポート番号
 */
data class OscTarget(
    val host: String,
    val port: Int,
)

/**
 * トランスポート層で発生したエラーを表すデータクラス。
 *
 * @property cause 発生した例外
 * @property consecutiveCount 連続して発生した回数
 */
data class TransportError(
    val cause: Throwable,
    val consecutiveCount: Int,
)

/** OSCパケットの送受信を行うトランスポートインターフェース。 */
interface OscTransport {
  /** 受信したOSCパケットのFlow。 */
  val incomingPackets: Flow<OscPacket>

  /** 受信ループ中に発生したエラーを通知するFlow。未実装の実装はemptyFlowを返す。 */
  val errors: Flow<TransportError>
    get() = emptyFlow()

  /** トランスポートを開始し、受信ループを起動する。 */
  suspend fun start()

  /** トランスポートを停止し、リソースを解放する。呼び出しが戻った時点で再開に必要な受信リソースも解放済みであることを想定する。 */
  suspend fun stop()

  /**
   * 指定されたターゲットにOSCパケットを送信する。
   *
   * @param packet 送信するOSCパケット
   * @param target 送信先のホストとポート
   */
  suspend fun send(packet: OscPacket, target: OscTarget)
}
