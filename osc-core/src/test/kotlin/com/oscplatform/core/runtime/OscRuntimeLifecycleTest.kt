package com.oscplatform.core.runtime

import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * [OscRuntime] のライフサイクル（start / stop）を検証するテスト。
 *
 * フェイクトランスポートで start/stop の呼び出し回数を追跡し、 冪等性と安全性を確認する。
 */
class OscRuntimeLifecycleTest {

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** [OscRuntime.start] がトランスポートの [OscTransport.start] を呼び出すことを検証する。 */
  @Test
  fun startCallsTransportStart(): Unit = runBlocking {
    val transport = FakeLifecycleTransport()
    val runtime = OscRuntime(schema = minimalSchema(), transport = transport)

    runtime.start()
    try {
      assertEquals(1, transport.startCount, "transport.start() が 1 回呼ばれるべき")
    } finally {
      runtime.stop()
    }
  }

  /** [OscRuntime.stop] がトランスポートの [OscTransport.stop] を呼び出すことを検証する。 */
  @Test
  fun stopCallsTransportStop(): Unit = runBlocking {
    val transport = FakeLifecycleTransport()
    val runtime = OscRuntime(schema = minimalSchema(), transport = transport)

    runtime.start()
    runtime.stop()

    assertEquals(1, transport.stopCount, "transport.stop() が 1 回呼ばれるべき")
  }

  // -------------------------------------------------------------------------
  // 境界値
  // -------------------------------------------------------------------------

  /** [OscRuntime.stop] を複数回呼び出しても例外が発生しないことを検証する。 */
  @Test
  fun multipleStopCallsAreSafe(): Unit = runBlocking {
    val transport = FakeLifecycleTransport()
    val runtime = OscRuntime(schema = minimalSchema(), transport = transport)

    runtime.start()
    runtime.stop()
    runtime.stop()
    runtime.stop()

    // 例外が発生しなければ成功
    assertEquals(3, transport.stopCount, "transport.stop() が 3 回呼ばれるべき")
  }

  /** [OscRuntime.start] を 2 回呼んでもトランスポートの start は 1 回しか呼ばれないことを検証する（冪等性）。 */
  @Test
  fun startIsIdempotent(): Unit = runBlocking {
    val transport = FakeLifecycleTransport()
    val runtime = OscRuntime(schema = minimalSchema(), transport = transport)

    runtime.start()
    runtime.start()
    try {
      assertEquals(1, transport.startCount, "start() は冪等であり transport.start() は 1 回のみ呼ばれるべき")
    } finally {
      runtime.stop()
    }
  }

  // -------------------------------------------------------------------------
  // ヘルパー
  // -------------------------------------------------------------------------

  /**
   * テスト用の最小限なスキーマを生成する。
   *
   * @return 1 メッセージを持つ [com.oscplatform.core.schema.OscSchema]
   */
  private fun minimalSchema() = oscSchema { message("/test/msg") { scalar("x", INT) } }
}

/**
 * start / stop の呼び出し回数を追跡するフェイクトランスポート。
 *
 * パケットの送受信は行わず、ライフサイクルメソッドのカウントのみを記録する。
 */
private class FakeLifecycleTransport : OscTransport {
  var startCount = 0
    private set

  var stopCount = 0
    private set

  private val _flow = MutableSharedFlow<OscPacket>(extraBufferCapacity = 64)
  override val incomingPackets: Flow<OscPacket> = _flow

  override suspend fun start() {
    startCount++
  }

  override suspend fun stop() {
    stopCount++
  }

  override suspend fun send(packet: OscPacket, target: OscTarget) {}
}
