package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import com.oscplatform.core.transport.TransportError
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * [WebUiServer] の SSE エンドポイント `/api/events` からのイベント配信を検証するテスト。
 *
 * 各テストはダイナミックポートでサーバーを起動し、HTTP 接続で SSE ストリームを読み取り、 期待されるイベントが正しく配信されることを確認する。
 */
class WebUiServerSseTest {

  /**
   * テスト用のフェイクトランスポート。
   *
   * 受信パケットとエラーを外部から注入でき、送信されたパケットを記録する。
   */
  private class TestTransport : OscTransport {
    val _incoming = MutableSharedFlow<OscPacket>(extraBufferCapacity = 64)
    val _errors = MutableSharedFlow<TransportError>(extraBufferCapacity = 64)
    val sentPackets = mutableListOf<OscPacket>()
    override val incomingPackets: Flow<OscPacket> = _incoming
    override val errors: Flow<TransportError> = _errors

    /** トランスポートを開始する（テスト用のため何もしない）。 */
    override suspend fun start() {}

    /** トランスポートを停止する（テスト用のため何もしない）。 */
    override suspend fun stop() {}

    /**
     * パケットを送信記録リストに追加する。
     *
     * @param packet 送信する OSC パケット
     * @param target 送信先ターゲット
     */
    override suspend fun send(packet: OscPacket, target: OscTarget) {
      sentPackets += packet
    }
  }

  /**
   * 送信時に必ず例外をスローするフェイクトランスポート。
   *
   * [send] の失敗による `send_failed` イベント検証に使用する。
   */
  private class FailingSendTransport : OscTransport {
    override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
    override val errors: Flow<TransportError> = emptyFlow()

    /** トランスポートを開始する（テスト用のため何もしない）。 */
    override suspend fun start() {}

    /** トランスポートを停止する（テスト用のため何もしない）。 */
    override suspend fun stop() {}

    /**
     * 常に [RuntimeException] をスローする。
     *
     * @param packet 送信する OSC パケット（使用されない）
     * @param target 送信先ターゲット（使用されない）
     * @throws RuntimeException 常にスロー
     */
    override suspend fun send(packet: OscPacket, target: OscTarget) {
      throw RuntimeException("transport failure")
    }
  }

  /**
   * OS が割り当てる空きポート番号を取得する。
   *
   * @return 使用可能なポート番号
   */
  private fun findFreePort(): Int {
    ServerSocket(0).use {
      return it.localPort
    }
  }

  /**
   * 指定ポートの SSE エンドポイントに接続し、イベントデータを収集する。
   *
   * SSE 形式の `data: <json>` 行を解析し、JSON 文字列のリストとして返す。 指定件数に達するかタイムアウトになると収集を終了する。
   *
   * @param port 接続先の HTTP ポート番号
   * @param maxEvents 収集する最大イベント数
   * @param timeoutMs 収集のタイムアウト（ミリ秒）
   * @return 収集された SSE イベントの JSON 文字列リスト
   */
  private fun collectSseEvents(port: Int, maxEvents: Int, timeoutMs: Long): List<String> {
    val events = mutableListOf<String>()
    val conn =
        URI("http://localhost:$port/api/events").toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = 3000
    conn.readTimeout = timeoutMs.toInt()
    try {
      val reader = conn.inputStream.bufferedReader()
      val deadline = System.currentTimeMillis() + timeoutMs
      while (events.size < maxEvents && System.currentTimeMillis() < deadline) {
        val line = reader.readLine() ?: break
        if (line.startsWith("data: ")) {
          events.add(line.removePrefix("data: ").trim())
        }
      }
    } catch (_: Exception) {}
    return events
  }

  /**
   * SSE 接続直後に `connected` イベントが配信されることを検証する。
   *
   * `/api/events` に接続し、最初に受信するイベントの type が `connected` であることを確認する。
   */
  @Test
  fun connectedEventIsDeliveredOnSseConnection() {
    val port = findFreePort()
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = TestTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val server =
        WebUiServer(
            schema = schema,
            runtime = runtime,
            config = WebUiServerConfig(mode = WebUiMode.SENDER, httpPort = port),
        )
    server.start()
    try {
      val events = collectSseEvents(port, 1, 3000)
      assertTrue(events.isNotEmpty(), "Should receive at least one SSE event")
      assertTrue(
          events[0].contains("\"type\":\"connected\""),
          "First event should be connected but was: ${events[0]}",
      )
    } finally {
      server.stop()
    }
  }

  /**
   * `runtime.send()` 成功時に `send_started` と `send_succeeded` イベントが配信されることを検証する。
   *
   * SSE 接続を開始した後にメッセージを送信し、 両方のイベントがストリームに現れることを確認する。
   */
  @Test
  fun sendStartedAndSendSucceededEventsAreDelivered() {
    val port = findFreePort()
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = TestTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val server =
        WebUiServer(
            schema = schema,
            runtime = runtime,
            config = WebUiServerConfig(mode = WebUiMode.SENDER, httpPort = port),
        )
    // ランタイムを先に起動してイベント収集を開始
    runBlocking { runtime.start() }
    server.start()
    try {
      // SSE イベントをバックグラウンドスレッドで収集開始
      val future = CompletableFuture<List<String>>()
      val thread = Thread { future.complete(collectSseEvents(port, 3, 5000)) }
      thread.start()

      // SSE クライアントが接続完了するまで待機
      Thread.sleep(500)

      // メッセージ送信でイベントを発火
      runBlocking { runtime.send("test.msg", mapOf("x" to 42), OscTarget("127.0.0.1", 9000)) }

      val events = future.get(6, TimeUnit.SECONDS)
      assertTrue(
          events.any { it.contains("\"type\":\"send_started\"") },
          "Should contain send_started event in: $events",
      )
      assertTrue(
          events.any { it.contains("\"type\":\"send_succeeded\"") },
          "Should contain send_succeeded event in: $events",
      )
    } finally {
      server.stop()
      runBlocking { runtime.stop() }
    }
  }

  /**
   * トランスポート送信失敗時に `send_failed` イベントが配信されることを検証する。
   *
   * 常に例外をスローするトランスポートを使用し、 `runtime.send()` 後に `send_failed` イベントが SSE ストリームに現れることを確認する。
   */
  @Test
  fun sendFailedEventIsDeliveredOnTransportFailure() {
    val port = findFreePort()
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = FailingSendTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val server =
        WebUiServer(
            schema = schema,
            runtime = runtime,
            config = WebUiServerConfig(mode = WebUiMode.SENDER, httpPort = port),
        )
    runBlocking { runtime.start() }
    server.start()
    try {
      val future = CompletableFuture<List<String>>()
      val thread = Thread { future.complete(collectSseEvents(port, 3, 5000)) }
      thread.start()
      Thread.sleep(500)

      // 送信は失敗するが、send_failed イベントが発行される
      runBlocking {
        try {
          runtime.send("test.msg", mapOf("x" to 42), OscTarget("127.0.0.1", 9000))
        } catch (_: Exception) {
          // トランスポート例外は無視（send_failed イベント発行済み）
        }
      }

      val events = future.get(6, TimeUnit.SECONDS)
      assertTrue(
          events.any { it.contains("\"type\":\"send_started\"") },
          "Should contain send_started event in: $events",
      )
      assertTrue(
          events.any { it.contains("\"type\":\"send_failed\"") },
          "Should contain send_failed event in: $events",
      )
    } finally {
      server.stop()
      runBlocking { runtime.stop() }
    }
  }

  /**
   * `additionalEvents` フローから発行されたイベントが SSE で配信されることを検証する。
   *
   * [WebUiLogEvent] をフローに発行し、SSE ストリームに該当イベントが現れることを確認する。
   */
  @Test
  fun additionalEventsAreDeliveredViaSse() {
    val port = findFreePort()
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = TestTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val additionalEvents = MutableSharedFlow<WebUiLogEvent>(extraBufferCapacity = 64)
    val server =
        WebUiServer(
            schema = schema,
            runtime = runtime,
            config = WebUiServerConfig(mode = WebUiMode.SENDER, httpPort = port),
            additionalEvents = additionalEvents,
        )
    server.start()
    try {
      val future = CompletableFuture<List<String>>()
      val thread = Thread { future.complete(collectSseEvents(port, 2, 5000)) }
      thread.start()
      Thread.sleep(500)

      // カスタムログイベントを発行
      additionalEvents.tryEmit(
          WebUiLogEvent(type = "custom_log", message = "hello from test"),
      )

      val events = future.get(6, TimeUnit.SECONDS)
      assertTrue(
          events.any { it.contains("\"type\":\"custom_log\"") },
          "Should contain custom_log event in: $events",
      )
      assertTrue(
          events.any { it.contains("hello from test") },
          "Should contain event message in: $events",
      )
    } finally {
      server.stop()
    }
  }

  /**
   * スキーマに一致しない受信パケットで `validation_error` イベントが配信されることを検証する。
   *
   * 未知のアドレスを持つパケットを受信フローに注入し、 `validation_error` イベントが SSE ストリームに現れることを確認する。
   */
  @Test
  fun validationErrorEventIsDeliveredForUnknownPacket() {
    val port = findFreePort()
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = TestTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val server =
        WebUiServer(
            schema = schema,
            runtime = runtime,
            config = WebUiServerConfig(mode = WebUiMode.SENDER, httpPort = port),
        )
    // ランタイムを起動して受信パケットの収集を開始
    runBlocking { runtime.start() }
    server.start()
    try {
      val future = CompletableFuture<List<String>>()
      val thread = Thread { future.complete(collectSseEvents(port, 2, 5000)) }
      thread.start()
      Thread.sleep(500)

      // スキーマに存在しないアドレスのパケットを注入
      transport._incoming.tryEmit(OscMessagePacket("/unknown/path", listOf(42)))

      val events = future.get(6, TimeUnit.SECONDS)
      assertTrue(
          events.any { it.contains("\"type\":\"validation_error\"") },
          "Should contain validation_error event in: $events",
      )
    } finally {
      server.stop()
      runBlocking { runtime.stop() }
    }
  }

  /**
   * トランスポートエラーが `transport_error` イベントとして SSE で配信されることを検証する。
   *
   * [TransportError] をエラーフローに注入し、 `transport_error` イベントが SSE ストリームに現れることを確認する。
   */
  @Test
  fun transportErrorEventIsDelivered() {
    val port = findFreePort()
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = TestTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val server =
        WebUiServer(
            schema = schema,
            runtime = runtime,
            config = WebUiServerConfig(mode = WebUiMode.SENDER, httpPort = port),
        )
    runBlocking { runtime.start() }
    server.start()
    try {
      val future = CompletableFuture<List<String>>()
      val thread = Thread { future.complete(collectSseEvents(port, 2, 5000)) }
      thread.start()
      Thread.sleep(500)

      // トランスポートエラーを注入
      transport._errors.tryEmit(
          TransportError(cause = RuntimeException("network down"), consecutiveCount = 1),
      )

      val events = future.get(6, TimeUnit.SECONDS)
      assertTrue(
          events.any { it.contains("\"type\":\"transport_error\"") },
          "Should contain transport_error event in: $events",
      )
      assertTrue(
          events.any { it.contains("network down") },
          "Should contain error message in: $events",
      )
    } finally {
      server.stop()
      runBlocking { runtime.stop() }
    }
  }
}
