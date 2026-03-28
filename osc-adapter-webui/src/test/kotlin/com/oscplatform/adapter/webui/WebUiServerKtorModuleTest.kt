package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import com.oscplatform.core.transport.TransportError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** [WebUiServer] の Ktor モジュール構成を testApplication で検証するテスト。 */
class WebUiServerKtorModuleTest {

  /** テスト用のフェイクトランスポート。 */
  private class FakeTransport : OscTransport {
    private val incoming = MutableSharedFlow<OscPacket>(extraBufferCapacity = 1)
    override val incomingPackets: Flow<OscPacket> = incoming
    override val errors: Flow<TransportError> = emptyFlow()

    /** トランスポートを開始する。 */
    override suspend fun start() {}

    /** トランスポートを停止する。 */
    override suspend fun stop() {}

    /**
     * 送信を受け付ける。
     *
     * @param packet 送信パケット
     * @param target 送信先
     */
    override suspend fun send(packet: OscPacket, target: OscTarget) {}
  }

  /**
   * テスト対象の [WebUiServer] を生成する。
   *
   * @return 設定済みサーバー
   */
  private fun createServer(): WebUiServer {
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }
    val runtime = OscRuntime(schema = schema, transport = FakeTransport())
    return WebUiServer(
        schema = schema,
        runtime = runtime,
        config =
            WebUiServerConfig(
                mode = WebUiMode.SENDER,
                httpPort = 0,
                defaultTargetHost = "192.168.0.10",
                defaultTargetPort = 9911,
            ),
    )
  }

  /** ルート HTML が外部アセット参照と JSON データ script を含むことを確認する。 */
  @Test
  fun rootHtmlReferencesExternalAssets() = testApplication {
    val server = createServer()
    application { server.configureApplication(this) }

    val response = client.get("/")
    val body = response.bodyAsText()

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(body.contains("/assets/webui/webui.css"))
    assertTrue(body.contains("/assets/webui/webui.js"))
    assertTrue(body.contains("webui-schema-data"))
    assertTrue(body.contains("192.168.0.10"))
    assertFalse(body.contains("function renderMessageList"))
  }

  /** Web UI 用 JavaScript アセットが Ktor から配信されることを確認する。 */
  @Test
  fun webUiJavascriptAssetIsServed() = testApplication {
    val server = createServer()
    application { server.configureApplication(this) }

    val response = client.get("/assets/webui/webui.js")
    val body = response.bodyAsText()

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.headers["Content-Type"].orEmpty().contains("javascript"))
    assertTrue(body.contains("function sendMessage()"))
  }

  /** 不正な JSON を `/api/send` に送ると 400 になることを確認する。 */
  @Test
  fun sendApiReturns400ForInvalidJson() = testApplication {
    val server = createServer()
    application { server.configureApplication(this) }

    val response =
        client.post("/api/send") {
          contentType(ContentType.Application.Json)
          setBody("{bad json}")
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertTrue(response.bodyAsText().contains("Invalid JSON"))
  }
}
