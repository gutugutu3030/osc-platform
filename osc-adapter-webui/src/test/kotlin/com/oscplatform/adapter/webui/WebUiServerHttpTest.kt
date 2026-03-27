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
 * [WebUiServer] の HTTP エンドポイントを実際の HTTP リクエストで検証するテスト。
 *
 * 各テストはダイナミックポートで起動した実サーバーに接続し、 レスポンスのステータスコードおよびコンテンツを確認する。
 */
class WebUiServerHttpTest {

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
   * @param initialMessageRef 初期選択メッセージ参照名
   * @param initialArgs 初期引数マップ
   * @return 設定済みの [WebUiServer] インスタンス
   */
  private fun createTestServer(
      mode: WebUiMode = WebUiMode.SENDER,
      port: Int = 18080,
      initialMessageRef: String? = null,
      initialArgs: Map<String, Any?> = emptyMap(),
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
                initialMessageRef = initialMessageRef,
                initialArgs = initialArgs,
            ),
    )
  }

  /**
   * 指定 URL への GET リクエストを送信し、[HttpURLConnection] を返す。
   *
   * @param url リクエスト先の URL 文字列
   * @return 接続済みの [HttpURLConnection]
   */
  private fun httpGet(url: String): HttpURLConnection {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    return conn
  }

  @Test
  fun getRootReturns200WithHtmlContentType() {
    val server = createTestServer(port = 18080)
    server.start()
    try {
      val conn = httpGet("http://localhost:18080/")
      assertEquals(200, conn.responseCode)
      val contentType = conn.getHeaderField("Content-Type") ?: ""
      assertTrue(contentType.contains("text/html"), "Content-Type should contain text/html")
      val body = conn.inputStream.bufferedReader().readText()
      assertTrue(body.contains("<html"), "Response body should contain HTML")
    } finally {
      server.stop()
    }
  }

  @Test
  fun getApiSchemaReturns200WithJson() {
    val server = createTestServer(port = 18081)
    server.start()
    try {
      val conn = httpGet("http://localhost:18081/api/schema")
      assertEquals(200, conn.responseCode)
      val contentType = conn.getHeaderField("Content-Type") ?: ""
      assertTrue(
          contentType.contains("application/json"), "Content-Type should contain application/json")
      val body = conn.inputStream.bufferedReader().readText()
      assertTrue(body.contains("messages"), "Schema JSON should contain 'messages' key")
      assertTrue(body.contains("/test/msg"), "Schema JSON should contain the test message path")
    } finally {
      server.stop()
    }
  }

  @Test
  fun getUnknownPathReturns404() {
    val server = createTestServer(port = 18082)
    server.start()
    try {
      val conn = httpGet("http://localhost:18082/unknown")
      assertEquals(404, conn.responseCode)
    } finally {
      server.stop()
    }
  }
}
