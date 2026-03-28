package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import com.oscplatform.core.transport.TransportError
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [WebUiServer] の /api/send エンドポイントを検証するテスト。
 *
 * SENDER モードでの正常送信、MONITOR モードでの拒否、 および必須フィールド欠落時のエラーレスポンスを確認する。
 */
class WebUiServerSendApiTest {

  /**
   * テスト用のフェイクトランスポート。
   *
   * 送信されたパケットを記録し、受信パケットは空の Flow を返す。
   */
  private class FakeTransport : OscTransport {
    val sentPackets = mutableListOf<OscPacket>()
    private val _flow = MutableSharedFlow<OscPacket>(extraBufferCapacity = 64)
    override val incomingPackets: Flow<OscPacket> = _flow
    override val errors: Flow<TransportError> = emptyFlow()

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
   * テスト用の [WebUiServer] を生成する。
   *
   * @param mode UI の動作モード
   * @param port HTTP サーバーのポート番号
   * @return 設定済みの [WebUiServer] インスタンス
   */
  private fun createTestServer(
      mode: WebUiMode = WebUiMode.SENDER,
      port: Int,
  ): WebUiServer {
    val schema: OscSchema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val transport = FakeTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    return WebUiServer(
        schema = schema,
        runtime = runtime,
        config =
            WebUiServerConfig(
                mode = mode,
                httpPort = port,
            ),
    )
  }

  /**
   * 指定 URL への POST リクエストを送信し、[HttpURLConnection] を返す。
   *
   * @param url リクエスト先の URL 文字列
   * @param jsonBody 送信する JSON ボディ文字列
   * @return 接続済みの [HttpURLConnection]
   */
  private fun httpPost(url: String, jsonBody: String): HttpURLConnection {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.use { it.write(jsonBody.toByteArray()) }
    return conn
  }

  /**
   * エラーストリームからレスポンスボディを読み取る。
   *
   * @param conn HTTP 接続
   * @return レスポンスボディ文字列
   */
  private fun readErrorBody(conn: HttpURLConnection): String {
    return conn.errorStream?.bufferedReader()?.readText() ?: ""
  }

  @Test
  fun sendInSenderModeWithValidBodyReturns200() {
    val server = createTestServer(mode = WebUiMode.SENDER, port = 18090)
    server.start()
    try {
      val json =
          """{"messageRef": "/test/msg", "host": "127.0.0.1", "port": 9000, "args": {"x": 42}}"""
      val conn = httpPost("http://localhost:18090/api/send", json)
      assertEquals(200, conn.responseCode)
      val body = conn.inputStream.bufferedReader().readText()
      assertTrue(body.contains("\"success\""), "Response should contain 'success' key")
      assertTrue(body.contains("true"), "Response should indicate success=true")
    } finally {
      server.stop()
    }
  }

  @Test
  fun sendInMonitorModeReturns403() {
    val server = createTestServer(mode = WebUiMode.MONITOR, port = 18091)
    server.start()
    try {
      val json =
          """{"messageRef": "/test/msg", "host": "127.0.0.1", "port": 9000, "args": {"x": 42}}"""
      val conn = httpPost("http://localhost:18091/api/send", json)
      assertEquals(403, conn.responseCode)
      val body = readErrorBody(conn)
      assertTrue(body.contains("monitor"), "Error should mention monitor mode")
    } finally {
      server.stop()
    }
  }

  @Test
  fun sendWithMissingMessageRefReturns400() {
    val server = createTestServer(mode = WebUiMode.SENDER, port = 18092)
    server.start()
    try {
      // messageRef を省略した JSON ボディ
      val json = """{"host": "127.0.0.1", "port": 9000, "args": {"x": 42}}"""
      val conn = httpPost("http://localhost:18092/api/send", json)
      assertEquals(400, conn.responseCode)
      val body = readErrorBody(conn)
      assertTrue(body.contains("messageRef"), "Error should mention missing messageRef")
    } finally {
      server.stop()
    }
  }

  @Test
  fun sendWithMissingHostAndPortReturns400() {
    val server = createTestServer(mode = WebUiMode.SENDER, port = 18093)
    server.start()
    try {
      // host と port を省略した JSON ボディ
      val json = """{"messageRef": "/test/msg", "args": {"x": 42}}"""
      val conn = httpPost("http://localhost:18093/api/send", json)
      assertEquals(400, conn.responseCode)
      val body = readErrorBody(conn)
      assertTrue(body.contains("host"), "Error should mention missing host/port fields")
    } finally {
      server.stop()
    }
  }
}
