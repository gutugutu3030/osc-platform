package com.oscplatform.transport.udp

import java.net.BindException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * [UdpOscTransport] のライフサイクル操作（start / stop）に関するテスト。
 *
 * ソケットバインド、停止後の状態、ポート競合、冪等性を検証する。
 */
class UdpOscTransportLifecycleTest {

  // -------------------------------------------------------------------------
  // 正常系: start / stop 基本動作
  // -------------------------------------------------------------------------

  /** start() 呼び出し後にトランスポートが例外なくリスン状態になること。 */
  @Test
  fun startBindsSuccessfully(): Unit = runBlocking {
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19710)
    try {
      transport.start()
      // start が例外なく完了すればバインド成功
    } finally {
      transport.stop()
    }
  }

  /** stop() 呼び出しが例外なく完了すること。 */
  @Test
  fun stopAfterStartDoesNotThrow(): Unit = runBlocking {
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19711)
    try {
      transport.start()
    } finally {
      transport.stop()
    }
  }

  // -------------------------------------------------------------------------
  // 境界値: stop の冪等性
  // -------------------------------------------------------------------------

  /** stop() を複数回呼び出しても例外が発生しないこと。 */
  @Test
  fun multipleStopCallsDoNotThrow(): Unit = runBlocking {
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19712)
    try {
      transport.start()
    } finally {
      transport.stop()
      transport.stop()
      transport.stop()
    }
  }

  /** start() を呼び出さずに stop() を呼んでも例外が発生しないこと。 */
  @Test
  fun stopWithoutStartDoesNotThrow(): Unit = runBlocking {
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19713)
    transport.stop()
  }

  // -------------------------------------------------------------------------
  // エラー系: ポート競合
  // -------------------------------------------------------------------------

  /** 同一ポートに 2 つのトランスポートをバインドすると 2 番目の start() で [BindException] が発生すること。 */
  @Test
  fun duplicatePortBindThrows(): Unit = runBlocking {
    val port = 19714
    val first = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    val second = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    try {
      first.start()
      assertFailsWith<BindException> { second.start() }
    } finally {
      first.stop()
      second.stop()
    }
  }

  // -------------------------------------------------------------------------
  // 境界値: start の冪等性
  // -------------------------------------------------------------------------

  /** start() を 2 回呼び出しても例外が発生しないこと（冪等性の検証）。 */
  @Test
  fun idempotentStartDoesNotThrow(): Unit = runBlocking {
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19715)
    try {
      transport.start()
      transport.start() // 2回目の呼び出しは無視される
    } finally {
      transport.stop()
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: stop 後の再 start
  // -------------------------------------------------------------------------

  /** stop() 後に再度 start() を呼び出しても例外なくバインドできること。 */
  @Test
  fun restartAfterStopSucceeds(): Unit = runBlocking {
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19716)
    try {
      transport.start()
      transport.stop()
      transport.start()
    } finally {
      transport.stop()
    }
  }
}
