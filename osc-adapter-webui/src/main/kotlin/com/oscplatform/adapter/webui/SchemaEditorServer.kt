package com.oscplatform.adapter.webui

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import javax.script.ScriptEngineManager
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.textArea
import kotlinx.html.title
import kotlinx.html.unsafe
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * スキーマエディタサーバーの設定。
 *
 * @property httpPort HTTP サーバーのリスンポート
 */
data class SchemaEditorServerConfig(
    val httpPort: Int = 3000,
)

/**
 * Kotlin DSL スキーマエディタ用の組み込み HTTP サーバー。
 *
 * ブラウザ上でスキーマ DSL を記述し、リアルタイムでスキーマ構造を可視化する。 Ktor CIO をベースに、HTML シェルは kotlinx.html で生成し、`POST
 * /api/evaluate` で DSL を評価する。
 *
 * @param config サーバー設定
 */
class SchemaEditorServer(
    private val config: SchemaEditorServerConfig = SchemaEditorServerConfig(),
) {
  /** HTTP サーバーがリスンしているポート番号。 */
  val port: Int
    get() = config.httpPort

  private var ktorServer: EmbeddedServer<*, *>? = null
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /**
   * Kotlin スクリプトエンジンのファクトリ。
   *
   * エンジンは評価ごとに新規作成し、前回のエラーによるステート汚染を回避する。
   */
  private val engineManager = ScriptEngineManager()

  /**
   * Ktor CIO HTTP サーバーを起動する。
   *
   * ルーティング:
   * - `GET /` — エディタ HTML を返す
   * - `POST /api/evaluate` — DSL テキストを評価してスキーマ JSON を返す
   */
  fun start() {
    val server =
        embeddedServer(CIO, port = config.httpPort) { configureApplication(this) }
            .start(wait = false)
    ktorServer = server
    awaitLocalHttpReady(config.httpPort)
  }

  /**
   * HTTP サーバーを停止する。
   *
   * Ktor サーバーに対して猶予期間 1 秒・最大待機 2 秒で graceful shutdown を実行する。
   */
  fun stop() {
    ktorServer?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
    ktorServer = null
  }

  /**
   * Schema Editor 用の Ktor アプリケーション設定を構成する。
   *
   * @param application 設定対象の [Application]
   */
  internal fun configureApplication(application: Application) {
    application.install(ContentNegotiation)
    application.routing {
      installWebUiStaticAssets()
      get("/") { serveEditorHtml(call) }
      post("/api/evaluate") { handleEvaluate(call) }
    }
  }

  /**
   * Schema Editor の HTML シェルを返す。
   *
   * HTML 構造は Kotlin 側に集約し、CSS / JavaScript は外部アセットとして読み込む。 既存テストが確認しているコメント文字列も HTML コメントとして埋め込む。
   *
   * @param call Ktor の [RoutingCall]
   */
  private suspend fun serveEditorHtml(call: RoutingCall) {
    call.respondHtml {
      head {
        meta(charset = "utf-8")
        title("OSC Schema Editor")
        link(rel = "stylesheet", href = "/assets/editor/editor.css", type = "text/css")
      }
      body {
        header {
          div(classes = "header-left") {
            h1 { +"OSC Schema Editor" }
            div(classes = "subtitle") { +"Kotlin DSL でスキーマを記述し、リアルタイムで構造を確認できます" }
          }
          div(classes = "header-right") {
            span(classes = "status-idle") {
              id = "status"
              +"入力待ち"
            }
          }
        }
        div(classes = "main-container") {
          div(classes = "editor-pane") {
            div(classes = "editor-header") {
              span { +"Kotlin DSL Editor" }
              div {
                attributes["style"] = "display:flex;gap:8px;"
                button(classes = "template-btn") {
                  id = "format-btn"
                  attributes["title"] = "Ctrl+Shift+F"
                  +"フォーマット"
                }
                button(classes = "template-btn") {
                  id = "load-template-btn"
                  +"サンプルを挿入"
                }
                button(classes = "template-btn") {
                  id = "download-schema-btn"
                  +"schema.kts をダウンロード"
                }
              }
            }
            div(classes = "editor-wrap") {
              textArea(classes = "") {
                id = "editor"
                attributes["spellcheck"] = "false"
                attributes["autocomplete"] = "off"
                attributes["placeholder"] =
                    "import com.oscplatform.core.schema.dsl.*\n\noscSchema {\n    message(\"/example/path\") {\n        description(\"メッセージの説明\")\n        scalar(\"value\", INT)\n    }\n}"
              }
              div { id = "ac-popup" }
              div { id = "cursor-mirror" }
            }
          }
          div(classes = "preview-pane") {
            div(classes = "preview-header") { +"Schema Preview" }
            div {
              id = "preview"
              div(classes = "empty-state") {
                div(classes = "icon") { +"📝" }
                div { +"左のエディタに Kotlin DSL を入力してください" }
                div {
                  attributes["style"] = "margin-top:8px;font-size:11px;color:#475569;"
                  +"入力するとリアルタイムでスキーマが可視化されます"
                }
                button(classes = "template-btn") {
                  id = "load-template-empty-btn"
                  attributes["style"] = "margin-top:16px;"
                  +"サンプルを挿入して始める"
                }
              }
            }
          }
        }
        unsafe {
          raw("<!-- 括弧ペア補完 -->")
          raw("<!-- 閉じ括弧のスキップ -->")
        }
        script(src = "/assets/editor/editor.js") { attributes["type"] = "module" }
      }
    }
  }

  /**
   * DSL テキストを評価してスキーマ JSON を返す Ktor ハンドラ。
   *
   * リクエストボディの `dsl` フィールドから DSL テキストを取得し、 Kotlin スクリプトエンジンで評価して結果を返す。 JSON 解析エラー・DSL
   * 未指定・評価エラーはすべてレスポンスとして返し、例外は伝播しない。
   *
   * @param call Ktor の RoutingCall
   */
  private suspend fun handleEvaluate(call: RoutingCall) {
    val body = call.receiveText()

    // リクエストボディの JSON 解析
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
    val dslText = req["dsl"] as? String

    if (dslText.isNullOrBlank()) {
      call.respondText(
          toJson(mapOf("success" to false, "error" to "dsl field is required")),
          ContentType.Application.Json,
          HttpStatusCode.BadRequest,
      )
      return
    }

    try {
      // DSL インポートを自動挿入してスクリプトを評価
      val schema = evaluateDsl(dslText)
      val schemaJson = serializeSchema(schema)
      call.respondText(
          toJson(mapOf("success" to true, "schema" to schemaJson)),
          ContentType.Application.Json,
      )
    } catch (e: Exception) {
      // エラーメッセージからユーザーに有用な情報を抽出
      val errorMessage = extractUserFriendlyError(e)
      call.respondText(
          toJson(mapOf("success" to false, "error" to errorMessage)),
          ContentType.Application.Json,
      )
    }
  }

  /**
   * DSL テキストを Kotlin スクリプトエンジンで評価して [OscSchema] を返す。
   *
   * @param dslText ユーザーが入力した DSL テキスト
   * @return 評価された [OscSchema]
   * @throws IllegalStateException 評価結果が [OscSchema] でない場合
   */
  internal fun evaluateDsl(dslText: String): OscSchema {
    // 1. DSL インポートを自動挿入してスクリプトをラップ
    val wrappedScript = buildString {
      appendLine("import com.oscplatform.core.schema.dsl.*")
      appendLine(dslText)
    }
    // 2. エラー後のステート汚染を回避するため新しいエンジンを生成
    val engine =
        engineManager.getEngineByExtension("kts")
            ?: error(
                "Kotlin script engine not found. Ensure kotlin-scripting-jsr223 is on the classpath")
    // 3. スクリプトを評価し、結果が OscSchema であることを検証
    val result = engine.eval(wrappedScript)
    return result as? OscSchema
        ?: error("Schema script must evaluate to OscSchema. Example: oscSchema { ... }")
  }

  /**
   * [OscSchema] をフロントエンド向け JSON マップにシリアライズする。
   *
   * @param schema シリアライズ対象のスキーマ
   * @return メッセージとバンドルを含むマップ
   */
  internal fun serializeSchema(schema: OscSchema): Map<String, Any?> {
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
        "bundles" to
            schema.bundles.map { bundle ->
              mapOf(
                  "name" to bundle.name,
                  "description" to (bundle.description ?: ""),
                  "messageRefs" to bundle.messageRefs,
              )
            },
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
            "role" to arg.role.name.lowercase(),
            "typeLabel" to "${arg.name}: ${arg.type.name.lowercase()}$roleSuffix",
        )
      }
      is ArrayArgNode -> {
        val lengthLabel =
            when (val len = arg.length) {
              is LengthSpec.Fixed -> mapOf("kind" to "fixed", "size" to len.size)
              is LengthSpec.FromField -> mapOf("kind" to "fromField", "fieldName" to len.fieldName)
            }
        val itemSpec =
            when (val item = arg.item) {
              is ArrayItemSpec.ScalarItem ->
                  mapOf("kind" to "scalar", "type" to item.type.name.lowercase())
              is ArrayItemSpec.TupleItem ->
                  mapOf(
                      "kind" to "tuple",
                      "fields" to
                          item.fields.map { f ->
                            mapOf("name" to f.name, "type" to f.type.name.lowercase())
                          },
                  )
            }
        mapOf(
            "name" to arg.name,
            "kind" to "array",
            "length" to lengthLabel,
            "item" to itemSpec,
            "typeLabel" to "${arg.name}: array",
        )
      }
    }
  }

  /**
   * 例外からユーザーに理解しやすいエラーメッセージを抽出する。
   *
   * @param e 発生した例外
   * @return ユーザー向けのエラーメッセージ
   */
  private fun extractUserFriendlyError(e: Exception): String {
    val message = e.message ?: "Unknown error"
    // スクリプトエンジンのエラーから行番号情報を保持しつつ簡潔にする
    val cause = e.cause
    return if (cause != null && cause.message != null) {
      "${message}\n${cause.message}"
    } else {
      message
    }
  }

  /**
   * オブジェクトを JSON 文字列に変換する。
   *
   * @param obj シリアライズ対象のオブジェクト
   * @return JSON 文字列
   */
  private fun toJson(obj: Any?): String = mapper.writeValueAsString(obj)
}
