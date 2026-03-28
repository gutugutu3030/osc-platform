package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.transport.OscTarget
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.unsafe
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Web UI の動作モード。
 *
 * サーバーの表示・機能を決定する。
 */
enum class WebUiMode {
  /** OSC トラフィックの受信・監視のみを行うモード。 */
  MONITOR,

  /** OSC メッセージの送信を行うモード。 */
  SENDER,

  /** MCP 経由の OSC 操作コンソールモード。 */
  MCP,
}

/**
 * Web UI サーバーの設定。
 *
 * @property mode UI の動作モード
 * @property httpPort HTTP サーバーのリスンポート
 * @property defaultTargetHost OSC 送信先のデフォルトホスト
 * @property defaultTargetPort OSC 送信先のデフォルトポート
 * @property initialMessageRef 初期選択するメッセージ参照名（null の場合は未選択）
 * @property initialArgs 初期引数のマップ
 */
data class WebUiServerConfig(
    val mode: WebUiMode,
    val httpPort: Int,
    val defaultTargetHost: String = "127.0.0.1",
    val defaultTargetPort: Int = 9000,
    val initialMessageRef: String? = null,
    val initialArgs: Map<String, Any?> = emptyMap(),
)

/**
 * Web UI のイベントログエントリ。
 *
 * @property type イベントの種別文字列
 * @property message イベントの説明メッセージ
 * @property details 追加の詳細情報マップ
 */
data class WebUiLogEvent(
    val type: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * OSC スキーマ情報を表示し、メッセージ送信・イベント監視を行う組み込み HTTP サーバー。
 *
 * HTML UI・REST API・SSE エンドポイントを提供する。
 *
 * @param schema 表示・送信対象の OSC スキーマ
 * @param runtime OSC ランタイムインスタンス
 * @param config サーバー設定
 * @param additionalEvents UI に配信する追加イベントの Flow
 * @param scope コルーチンスコープ
 */
class WebUiServer(
    private val schema: OscSchema,
    private val runtime: OscRuntime,
    private val config: WebUiServerConfig,
    private val additionalEvents: Flow<WebUiLogEvent> = emptyFlow(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  /** HTTP サーバーがリスンしているポート番号。 */
  val port: Int
    get() = config.httpPort

  private var ktorServer: EmbeddedServer<*, *>? = null
  private val sseClients = CopyOnWriteArrayList<SseClient>()
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /** HTTP サーバーを起動し、ランタイムイベントおよび追加イベントの配信を開始する。 */
  fun start() {
    val server = embeddedServer(CIO, port = config.httpPort) { configureApplication(this) }
    ktorServer = server.start(wait = false)
    awaitLocalHttpReady(config.httpPort)

    // HTTP サーバー起動後にイベント購読を開始し、SSE クライアントへ逐次配信する。
    scope.launch { runtime.events.collect { event -> broadcastJson(serializeRuntimeEvent(event)) } }
    scope.launch {
      additionalEvents.collect { event ->
        broadcastJson(
            toJson(
                mapOf(
                    "type" to event.type,
                    "message" to event.message,
                    "details" to event.details,
                ),
            ),
        )
      }
    }
  }

  /** HTTP サーバーを停止し、コルーチンスコープをキャンセルする。 */
  fun stop() {
    sseClients.forEach { it.close() }
    sseClients.clear()
    ktorServer?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
    ktorServer = null
    scope.cancel()
  }

  /**
   * Web UI 用の Ktor アプリケーション設定を構成する。
   *
   * @param application 設定対象の [Application]
   */
  internal fun configureApplication(application: Application) {
    application.install(ContentNegotiation)
    application.routing {
      installWebUiStaticAssets()
      get("/") { serveHtml(call) }
      get("/api/schema") { serveSchema(call) }
      post("/api/send") { handleSend(call) }
      get("/api/events") { handleEvents(call) }
    }
  }

  /**
   * メインの HTML ページを返す。
   *
   * @param call Ktor の [RoutingCall]
   */
  private suspend fun serveHtml(call: RoutingCall) {
    val title = pageTitle()
    val subtitle = pageSubtitle()
    val schemaJson = toJsonForHtmlScript(buildSchemaPayload())
    val uiConfigJson = toJsonForHtmlScript(buildUiConfigPayload())

    call.respondHtml {
      lang = "en"
      head {
        meta(charset = "utf-8")
        title(title)
        link(rel = "stylesheet", href = "/assets/webui/webui.css", type = "text/css")
      }
      body {
        header {
          h1 { +title }
          p { +subtitle }
          p {
            id = "schema-label"
            +"Loading schema..."
          }
        }
        div(classes = "main-container") {
          div {
            id = "schema-list"
            h2 { +"Messages" }
            div {
              id = "message-items"
              +"Loading..."
            }
          }
          div {
            id = "form-panel"
            div {
              id = "placeholder"
              +"← Select a message to inspect"
            }
            div {
              id = "send-form"
              attributes["style"] = "display:none"
              h2 { id = "form-name" }
              div(classes = "msg-detail-path") { id = "form-path" }
              div(classes = "msg-detail-desc") { id = "form-desc" }
              div { id = "form-fields" }
              div(classes = "target-row") {
                id = "target-row"
                div(classes = "field-group") {
                  div(classes = "field-label") { +"Target Host" }
                  input(classes = "field-input") {
                    id = "target-host"
                    type = InputType.text
                  }
                }
                div(classes = "field-group") {
                  div(classes = "field-label") { +"Target Port" }
                  input(classes = "field-input") {
                    id = "target-port"
                    type = InputType.number
                  }
                }
              }
              button {
                id = "send-btn"
                +"Send"
              }
              div { id = "send-result" }
            }
          }
        }
        div {
          id = "event-log"
          div {
            id = "log-header"
            span { +"Event Log" }
            button {
              id = "clear-btn"
              +"Clear"
            }
          }
          div { id = "log-entries" }
        }
        script {
          id = "webui-schema-data"
          type = "application/json"
          unsafe { raw(schemaJson) }
        }
        script {
          id = "webui-config-data"
          type = "application/json"
          unsafe { raw(uiConfigJson) }
        }
        script(src = "/assets/webui/webui.js") { attributes["type"] = "module" }
      }
    }
  }

  /**
   * スキーマ情報を JSON 形式で返す。
   *
   * @param call Ktor の [RoutingCall]
   */
  private suspend fun serveSchema(call: RoutingCall) {
    call.respondText(
        toJson(buildSchemaPayload()),
        ContentType.Application.Json,
        HttpStatusCode.OK,
    )
  }

  /**
   * フロントエンド向けのスキーマ JSON ペイロードを構築する。
   *
   * @return メッセージ一覧を含むマップ
   */
  private fun buildSchemaPayload(): Map<String, Any?> {
    return mapOf(
        "messages" to
            schema.messages.map { spec ->
              mapOf(
                  "path" to spec.path,
                  "name" to spec.name,
                  "description" to (spec.description ?: ""),
                  "args" to spec.args.map { arg -> buildArgJson(arg) },
              )
            },
    )
  }

  /**
   * フロントエンド向けの UI 設定 JSON ペイロードを構築する。
   *
   * @return UI 初期状態を含むマップ
   */
  private fun buildUiConfigPayload(): Map<String, Any?> {
    return mapOf(
        "mode" to config.mode.name.lowercase(),
        "allowSend" to sendingEnabled(),
        "defaultTargetHost" to config.defaultTargetHost,
        "defaultTargetPort" to config.defaultTargetPort,
        "initialMessageRef" to config.initialMessageRef,
        "initialArgs" to config.initialArgs,
    )
  }

  /**
   * OSC 引数ノードをフロントエンド向け JSON マップに変換する。
   *
   * @param arg 変換対象の引数ノード
   * @return フロントエンドで使用するフィールド情報のマップ
   */
  private fun buildArgJson(arg: OscArgNode): Map<String, Any?> {
    return when (arg) {
      is ScalarArgNode -> {
        val roleSuffix = if (arg.role == ScalarRole.LENGTH) " [length]" else ""
        mapOf(
            "name" to arg.name,
            "kind" to "scalar",
            "type" to arg.type.name.lowercase(),
            "typeLabel" to "${arg.name}: ${arg.type.name.lowercase()}$roleSuffix",
            "inputType" to scalarInputType(arg.type),
            "placeholder" to scalarPlaceholder(arg.type),
        )
      }

      is ArrayArgNode -> {
        val lengthLabel =
            when (val len = arg.length) {
              is LengthSpec.Fixed -> "[${len.size}]"
              is LengthSpec.FromField -> "[from=${len.fieldName}]"
            }
        val itemLabel =
            when (val item = arg.item) {
              is ArrayItemSpec.ScalarItem -> item.type.name.lowercase()
              is ArrayItemSpec.TupleItem ->
                  "tuple(${item.fields.joinToString(",") { "${it.name}:${it.type.name.lowercase()}" }})"
            }
        mapOf(
            "name" to arg.name,
            "kind" to "array",
            "type" to "array",
            "typeLabel" to "${arg.name}: array<$itemLabel>$lengthLabel",
            "inputType" to "text",
            "placeholder" to "JSON array, e.g. [1,2,3]",
        )
      }
    }
  }

  /**
   * OSC 型に対応する HTML input の type 属性値を返す。
   *
   * @param type OSC 型
   * @return HTML input の type 値
   */
  private fun scalarInputType(type: OscType): String {
    return when (type) {
      OscType.INT -> "number"
      OscType.FLOAT -> "number"
      OscType.BOOL -> "text"
      OscType.STRING -> "text"
      OscType.BLOB -> "text"
    }
  }

  /**
   * OSC 型に対応する HTML input のプレースホルダー文字列を返す。
   *
   * @param type OSC 型
   * @return プレースホルダー文字列
   */
  private fun scalarPlaceholder(type: OscType): String {
    return when (type) {
      OscType.INT -> "0"
      OscType.FLOAT -> "0.0"
      OscType.BOOL -> "true / false"
      OscType.STRING -> ""
      OscType.BLOB -> "base64"
    }
  }

  /**
   * 現在のモードでメッセージ送信が有効かどうかを返す。
   *
   * @return 送信が有効なら true
   */
  private fun sendingEnabled(): Boolean = config.mode != WebUiMode.MONITOR

  /**
   * OSC メッセージ送信リクエストを処理する。
   *
   * リクエストボディから送信先とメッセージ情報を解析し、ランタイム経由で送信する。
   *
   * @param call Ktor の [RoutingCall]
   */
  private suspend fun handleSend(call: RoutingCall) {
    if (!sendingEnabled()) {
      call.respondText(
          toJson(mapOf("success" to false, "error" to "send is disabled in monitor mode")),
          ContentType.Application.Json,
          HttpStatusCode.Forbidden,
      )
      return
    }

    val body = call.receiveText()
    val req =
        try {
          mapper.readValue(body, Map::class.java)
        } catch (e: Exception) {
          call.respondText(
              toJson(mapOf("success" to false, "error" to "Invalid JSON: ${e.message}")),
              ContentType.Application.Json,
              HttpStatusCode.BadRequest,
          )
          return
        }
    val messageRef = req["messageRef"] as? String
    val host = req["host"] as? String
    val port = (req["port"] as? Number)?.toInt()

    if (messageRef == null || host == null || port == null) {
      call.respondText(
          toJson(mapOf("error" to "messageRef, host, and port are required")),
          ContentType.Application.Json,
          HttpStatusCode.BadRequest,
      )
      return
    }

    @Suppress("UNCHECKED_CAST") val args = (req["args"] as? Map<String, Any?>) ?: emptyMap()

    // リクエスト検証後にランタイム送信を実行し、成功・失敗を JSON で返す。
    try {
      runtime.send(
          messageRef = messageRef,
          rawArgs = args,
          target = OscTarget(host = host, port = port),
      )
      call.respondText(
          toJson(mapOf("success" to true)),
          ContentType.Application.Json,
          HttpStatusCode.OK,
      )
    } catch (e: Exception) {
      call.respondText(
          toJson(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))),
          ContentType.Application.Json,
          HttpStatusCode.BadRequest,
      )
    }
  }

  /**
   * SSE イベントストリーム接続を処理する。
   *
   * クライアントを登録し、接続が切れるまでイベントを配信し続ける。
   *
   * @param call Ktor の [RoutingCall]
   */
  private suspend fun handleEvents(call: RoutingCall) {
    val client = SseClient()
    sseClients.add(client)
    client.send("data: ${toJson(mapOf("type" to "connected"))}\n\n")

    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")

    try {
      call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
        // キューからイベントを順に取り出し、無通信時は heartbeat を返して接続を維持する。
        while (coroutineContext.isActive) {
          val event = client.awaitEvent(timeoutMs = 5000)
          if (event == null) {
            if (client.isClosed()) {
              break
            }
            write(":\n\n")
          } else {
            write(event)
          }
          flush()
        }
      }
    } finally {
      sseClients.remove(client)
      client.close()
    }
  }

  /**
   * JSON 文字列を全 SSE クライアントにブロードキャストする。
   *
   * 切断されたクライアントはリストから自動的に除去される。
   *
   * @param json ブロードキャストする JSON 文字列
   */
  private fun broadcastJson(json: String) {
    val sseData = "data: $json\n\n"
    val deadClients = mutableListOf<SseClient>()
    sseClients.forEach { client ->
      if (!client.send(sseData)) {
        deadClients.add(client)
      }
    }
    sseClients.removeAll(deadClients)
  }

  /**
   * ランタイムイベントを SSE 配信用の JSON 文字列にシリアライズする。
   *
   * @param event シリアライズ対象のランタイムイベント
   * @return JSON 文字列
   */
  private fun serializeRuntimeEvent(event: OscRuntimeEvent): String {
    return when (event) {
      is OscRuntimeEvent.Received ->
          toJson(
              mapOf(
                  "type" to "received",
                  "path" to event.spec.path,
                  "args" to event.namedArgs,
              ),
          )

      is OscRuntimeEvent.ValidationError ->
          toJson(
              mapOf(
                  "type" to "validation_error",
                  "address" to event.address,
                  "reason" to event.reason,
              ),
          )

      is OscRuntimeEvent.TransportErrorEvent ->
          toJson(
              mapOf(
                  "type" to "transport_error",
                  "message" to event.error.cause.message,
              ),
          )

      is OscRuntimeEvent.SendStarted ->
          toJson(
              mapOf(
                  "type" to "send_started",
                  "messageRef" to event.messageRef,
                  "args" to event.args,
                  "targetHost" to event.target.host,
                  "targetPort" to event.target.port,
              ),
          )

      is OscRuntimeEvent.SendSucceeded ->
          toJson(
              mapOf(
                  "type" to "send_succeeded",
                  "messageRef" to event.messageRef,
                  "args" to event.args,
                  "targetHost" to event.target.host,
                  "targetPort" to event.target.port,
              ),
          )

      is OscRuntimeEvent.SendFailed ->
          toJson(
              mapOf(
                  "type" to "send_failed",
                  "messageRef" to event.messageRef,
                  "error" to event.cause.message,
                  "args" to event.args,
                  "targetHost" to event.target.host,
                  "targetPort" to event.target.port,
              ),
          )
    }
  }

  /**
   * オブジェクトを JSON 文字列に変換する。
   *
   * @param obj シリアライズ対象のオブジェクト
   * @return JSON 文字列
   */
  private fun toJson(obj: Any?): String {
    return mapper.writeValueAsString(obj)
  }

  /**
   * HTML 内に安全に埋め込める JSON 文字列へ変換する。
   *
   * @param obj シリアライズ対象のオブジェクト
   * @return HTML script タグへ埋め込み可能な JSON 文字列
   */
  private fun toJsonForHtmlScript(obj: Any?): String {
    return toJson(obj).replace("</", "<\\/")
  }

  /**
   * 現在モードに対応するページタイトルを返す。
   *
   * @return ページタイトル文字列
   */
  private fun pageTitle(): String {
    return when (config.mode) {
      WebUiMode.MONITOR -> "OSC Monitor"
      WebUiMode.SENDER -> "OSC Sender"
      WebUiMode.MCP -> "OSC MCP Console"
    }
  }

  /**
   * 現在モードに対応するページサブタイトルを返す。
   *
   * @return ページサブタイトル文字列
   */
  private fun pageSubtitle(): String {
    return when (config.mode) {
      WebUiMode.MONITOR -> "Receive OSC traffic and inspect the schema."
      WebUiMode.SENDER -> "Select a message and send it to an OSC target."
      WebUiMode.MCP -> "Inspect MCP requests while testing OSC sends from the browser."
    }
  }
}

/**
 * SSE クライアント接続を管理するクラス。
 *
 * Ktor のレスポンスライターに対して、イベントキューを介して逐次配信する。
 */
private class SseClient {
  private val queue = Channel<String>(1024)

  /**
   * データをキューに追加して送信予約する。
   *
   * @param data 送信する SSE データ文字列
   * @return キューへの追加に成功した場合 true
   */
  fun send(data: String): Boolean {
    return queue.trySend(data).isSuccess
  }

  /**
   * 指定時間待機して次のイベントを取得する。
   *
   * @param timeoutMs 待機時間（ミリ秒）
   * @return 取得したイベント文字列。タイムアウトまたはクローズ済みの場合は null
   */
  suspend fun awaitEvent(timeoutMs: Long): String? {
    return withTimeoutOrNull(timeoutMs) { queue.receiveCatching().getOrNull() }
  }

  /**
   * クライアントがクローズ済みかどうかを返す。
   *
   * @return クローズ済みなら true
   */
  @OptIn(DelicateCoroutinesApi::class)
  fun isClosed(): Boolean {
    return queue.isClosedForSend
  }

  /** イベントキューをクローズする。 */
  fun close() {
    queue.close()
  }
}
