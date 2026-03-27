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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [WebUiServer] が生成する HTML の設定埋め込みを検証するテスト。
 *
 * 各モードのタイトル表示およびデフォルトターゲット情報の埋め込みを確認する。
 */
class WebUiServerHtmlConfigTest {

  /** テスト用のフェイクトランスポート。 */
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
   * @param defaultTargetHost デフォルト送信先ホスト
   * @param defaultTargetPort デフォルト送信先ポート
   * @return 設定済みの [WebUiServer] インスタンス
   */
  private fun createTestServer(
      mode: WebUiMode = WebUiMode.SENDER,
      port: Int,
      defaultTargetHost: String = "127.0.0.1",
      defaultTargetPort: Int = 9000,
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
                defaultTargetHost = defaultTargetHost,
                defaultTargetPort = defaultTargetPort,
            ),
    )
  }

  /**
   * 指定 URL から HTML ボディを取得する。
   *
   * @param url リクエスト先の URL 文字列
   * @return レスポンスの HTML 文字列
   */
  private fun fetchHtml(url: String): String {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    return conn.inputStream.bufferedReader().readText()
  }

  @Test
  fun senderModeTitleContainsOscSender() {
    val server = createTestServer(mode = WebUiMode.SENDER, port = 18100)
    server.start()
    try {
      val html = fetchHtml("http://localhost:18100/")
      assertTrue(html.contains("OSC Sender"), "SENDER mode HTML should contain 'OSC Sender'")
    } finally {
      server.stop()
    }
  }

  @Test
  fun monitorModeTitleContainsOscMonitor() {
    val server = createTestServer(mode = WebUiMode.MONITOR, port = 18101)
    server.start()
    try {
      val html = fetchHtml("http://localhost:18101/")
      assertTrue(html.contains("OSC Monitor"), "MONITOR mode HTML should contain 'OSC Monitor'")
    } finally {
      server.stop()
    }
  }

  @Test
  fun mcpModeTitleContainsOscMcpConsole() {
    val server = createTestServer(mode = WebUiMode.MCP, port = 18102)
    server.start()
    try {
      val html = fetchHtml("http://localhost:18102/")
      assertTrue(html.contains("OSC MCP Console"), "MCP mode HTML should contain 'OSC MCP Console'")
    } finally {
      server.stop()
    }
  }

  @Test
  fun defaultTargetHostAndPortEmbeddedInHtml() {
    val server =
        createTestServer(
            port = 18103, defaultTargetHost = "192.168.1.100", defaultTargetPort = 7777)
    server.start()
    try {
      val html = fetchHtml("http://localhost:18103/")
      assertTrue(
          html.contains("192.168.1.100"), "HTML should contain the configured default target host")
      assertTrue(html.contains("7777"), "HTML should contain the configured default target port")
    } finally {
      server.stop()
    }
  }
}
