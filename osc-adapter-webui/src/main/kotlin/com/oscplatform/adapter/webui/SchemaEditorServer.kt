package com.oscplatform.adapter.webui

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.script.ScriptEngineManager
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
 * ブラウザ上でスキーマ DSL を記述し、リアルタイムでスキーマ構造を可視化する。
 *
 * @param config サーバー設定
 */
class SchemaEditorServer(
    private val config: SchemaEditorServerConfig = SchemaEditorServerConfig(),
) {
  /** HTTP サーバーがリスンしているポート番号。 */
  val port: Int
    get() = config.httpPort

  private var httpServer: HttpServer? = null
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  // スクリプトエンジンマネージャ（エンジンは評価ごとに新規作成してステート汚染を回避）
  private val engineManager = ScriptEngineManager()

  /** HTTP サーバーを起動する。 */
  fun start() {
    val server = HttpServer.create(InetSocketAddress(config.httpPort), 0)
    server.createContext("/") { exchange -> dispatch(exchange) }
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.start()
    httpServer = server
  }

  /** HTTP サーバーを停止する。 */
  fun stop() {
    httpServer?.stop(0)
    httpServer = null
  }

  /**
   * HTTP リクエストをパスとメソッドに基づいて適切なハンドラに振り分ける。
   *
   * @param exchange HTTP リクエスト/レスポンスの交換オブジェクト
   */
  private fun dispatch(exchange: HttpExchange) {
    try {
      val path = exchange.requestURI.path
      val method = exchange.requestMethod
      when {
        method == "GET" && path == "/" -> serveHtml(exchange)
        method == "POST" && path == "/api/evaluate" -> handleEvaluate(exchange)
        else -> sendResponse(exchange, 404, "text/plain", "Not Found")
      }
    } catch (e: Exception) {
      try {
        sendResponse(exchange, 500, "application/json", toJson(mapOf("error" to e.message)))
      } catch (_: Exception) {}
    }
  }

  /**
   * メインのエディタ HTML ページを返す。
   *
   * @param exchange HTTP リクエスト/レスポンスの交換オブジェクト
   */
  private fun serveHtml(exchange: HttpExchange) {
    sendResponse(exchange, 200, "text/html; charset=utf-8", buildEditorHtml())
  }

  /**
   * DSL テキストを評価してスキーマ JSON を返す。
   *
   * リクエストボディの `dsl` フィールドから DSL テキストを取得し、 Kotlin スクリプトエンジンで評価して結果を返す。
   *
   * @param exchange HTTP リクエスト/レスポンスの交換オブジェクト
   */
  private fun handleEvaluate(exchange: HttpExchange) {
    val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    // リクエストボディの JSON 解析
    val req =
        try {
          mapper.readValue(body, Map::class.java)
        } catch (e: Exception) {
          sendResponse(
              exchange,
              400,
              "application/json",
              toJson(mapOf("success" to false, "error" to "Invalid JSON: ${e.message}")),
          )
          return
        }
    val dslText = req["dsl"] as? String

    if (dslText.isNullOrBlank()) {
      sendResponse(
          exchange,
          400,
          "application/json",
          toJson(mapOf("success" to false, "error" to "dsl field is required")),
      )
      return
    }

    try {
      // DSL インポートを自動挿入してスクリプトを評価
      val schema = evaluateDsl(dslText)
      val schemaJson = serializeSchema(schema)
      sendResponse(
          exchange,
          200,
          "application/json",
          toJson(mapOf("success" to true, "schema" to schemaJson)),
      )
    } catch (e: Exception) {
      // エラーメッセージからユーザーに有用な情報を抽出
      val errorMessage = extractUserFriendlyError(e)
      sendResponse(
          exchange,
          200,
          "application/json",
          toJson(mapOf("success" to false, "error" to errorMessage)),
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
    val wrappedScript = buildString {
      appendLine("import com.oscplatform.core.schema.dsl.*")
      appendLine(dslText)
    }
    // 評価ごとに新しいエンジンを生成してステート汚染を回避
    val engine =
        engineManager.getEngineByExtension("kts")
            ?: error(
                "Kotlin script engine not found. Ensure kotlin-scripting-jsr223 is on the classpath")
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
   * HTTP レスポンスを送信する。
   *
   * @param exchange HTTP リクエスト/レスポンスの交換オブジェクト
   * @param status HTTP ステータスコード
   * @param contentType Content-Type ヘッダー値
   * @param body レスポンスボディ文字列
   */
  private fun sendResponse(exchange: HttpExchange, status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  /**
   * オブジェクトを JSON 文字列に変換する。
   *
   * @param obj シリアライズ対象のオブジェクト
   * @return JSON 文字列
   */
  private fun toJson(obj: Any?): String = mapper.writeValueAsString(obj)

  /**
   * エディタ HTML ページを構築する。
   *
   * @return 完全な HTML 文字列
   */
  private fun buildEditorHtml(): String {
    return """
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <title>OSC Schema Editor</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: 'Courier New', monospace; background: #0f172a; color: #e2e8f0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }

    header { background: #1e293b; padding: 12px 20px; border-bottom: 1px solid #334155; flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; }
    header h1 { font-size: 16px; color: #60a5fa; }
    header .subtitle { font-size: 11px; color: #94a3b8; margin-top: 2px; }
    .header-left { display: flex; flex-direction: column; }
    .header-right { display: flex; align-items: center; gap: 12px; }
    #status { font-size: 11px; padding: 4px 10px; border-radius: 4px; }
    .status-ok { background: #064e3b; color: #6ee7b7; }
    .status-err { background: #7f1d1d; color: #fca5a5; }
    .status-loading { background: #1e3a5f; color: #93c5fd; }
    .status-idle { background: #334155; color: #94a3b8; }

    .main-container { display: flex; flex: 1; overflow: hidden; }

    .editor-pane { width: 50%; display: flex; flex-direction: column; border-right: 1px solid #334155; }
    .editor-header { background: #1e293b; padding: 8px 14px; font-size: 11px; color: #64748b; text-transform: uppercase; letter-spacing: 1px; border-bottom: 1px solid #334155; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; }
    #editor { flex: 1; width: 100%; background: #0f172a; color: #e2e8f0; border: none; outline: none; resize: none; padding: 14px; font-family: 'Courier New', monospace; font-size: 13px; line-height: 1.6; tab-size: 4; }
    #editor::placeholder { color: #475569; }

    .preview-pane { width: 50%; display: flex; flex-direction: column; overflow: hidden; }
    .preview-header { background: #1e293b; padding: 8px 14px; font-size: 11px; color: #64748b; text-transform: uppercase; letter-spacing: 1px; border-bottom: 1px solid #334155; flex-shrink: 0; }
    #preview { flex: 1; overflow-y: auto; padding: 14px; }

    .msg-card { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 14px; margin-bottom: 12px; }
    .msg-card:hover { border-color: #3b82f6; }
    .msg-path { font-size: 14px; color: #38bdf8; font-weight: bold; }
    .msg-name { font-size: 11px; color: #64748b; margin-top: 2px; }
    .msg-desc { font-size: 12px; color: #94a3b8; margin-top: 6px; font-style: italic; }
    .msg-args { margin-top: 10px; }
    .msg-args-title { font-size: 10px; color: #64748b; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px; }

    .arg-item { display: flex; align-items: center; padding: 5px 8px; background: #0f172a; border-radius: 4px; margin-bottom: 4px; font-size: 12px; }
    .arg-name { color: #93c5fd; margin-right: 8px; font-weight: bold; }
    .arg-type { color: #a78bfa; background: #1e1b4b; padding: 1px 6px; border-radius: 3px; font-size: 10px; }
    .arg-role { color: #fbbf24; background: #422006; padding: 1px 6px; border-radius: 3px; font-size: 10px; margin-left: 6px; }
    .arg-detail { color: #64748b; font-size: 10px; margin-left: 8px; }

    .array-item { padding: 5px 8px; background: #0f172a; border-radius: 4px; margin-bottom: 4px; font-size: 12px; }
    .array-header { display: flex; align-items: center; }
    .array-length { color: #64748b; font-size: 10px; margin-left: 8px; }
    .array-children { margin-left: 16px; margin-top: 4px; border-left: 2px solid #334155; padding-left: 8px; }

    .bundle-card { background: #1e293b; border: 1px solid #334155; border-left: 3px solid #a78bfa; border-radius: 8px; padding: 14px; margin-bottom: 12px; }
    .bundle-name { font-size: 14px; color: #a78bfa; font-weight: bold; }
    .bundle-desc { font-size: 12px; color: #94a3b8; margin-top: 4px; font-style: italic; }
    .bundle-refs { margin-top: 8px; }
    .bundle-ref { font-size: 12px; color: #38bdf8; padding: 3px 0; }
    .bundle-ref::before { content: "↳ "; color: #64748b; }

    .section-title { font-size: 13px; color: #60a5fa; margin-bottom: 10px; padding-bottom: 6px; border-bottom: 1px solid #334155; }

    #error-display { display: none; background: #7f1d1d; border: 1px solid #991b1b; border-radius: 6px; padding: 12px; margin-bottom: 12px; }
    #error-display pre { font-size: 12px; color: #fca5a5; white-space: pre-wrap; word-break: break-word; }

    .empty-state { color: #475569; text-align: center; padding-top: 60px; font-size: 13px; }
    .empty-state .icon { font-size: 40px; margin-bottom: 12px; }
    .template-btn { background: #1e3a5f; color: #93c5fd; border: 1px solid #3b82f6; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-family: 'Courier New', monospace; font-size: 12px; margin-top: 12px; }
    .template-btn:hover { background: #2563eb; color: white; }

    .editor-wrap { position: relative; flex: 1; display: flex; overflow: hidden; }
    #ac-popup { display: none; position: absolute; z-index: 100; background: #1e293b; border: 1px solid #3b82f6; border-radius: 6px; max-height: 200px; overflow-y: auto; min-width: 220px; box-shadow: 0 4px 16px rgba(0,0,0,0.4); }
    .ac-item { padding: 5px 10px; font-size: 12px; cursor: pointer; display: flex; align-items: center; gap: 8px; }
    .ac-item:hover, .ac-item.ac-active { background: #2563eb; color: white; }
    .ac-label { font-family: 'Courier New', monospace; color: #93c5fd; }
    .ac-item.ac-active .ac-label, .ac-item:hover .ac-label { color: white; }
    .ac-kind { font-size: 10px; color: #64748b; margin-left: auto; }
    .ac-item.ac-active .ac-kind, .ac-item:hover .ac-kind { color: #bfdbfe; }
    .ac-hint { padding: 4px 10px; font-size: 10px; color: #475569; border-top: 1px solid #334155; }
    #cursor-mirror { position: absolute; visibility: hidden; white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word; font-family: 'Courier New', monospace; font-size: 13px; line-height: 1.6; padding: 14px; tab-size: 4; pointer-events: none; }
  </style>
</head>
<body>
  <header>
    <div class="header-left">
      <h1>OSC Schema Editor</h1>
      <div class="subtitle">Kotlin DSL でスキーマを記述し、リアルタイムで構造を確認できます</div>
    </div>
    <div class="header-right">
      <span id="status" class="status-idle">入力待ち</span>
    </div>
  </header>
  <div class="main-container">
    <div class="editor-pane">
      <div class="editor-header">
        <span>Kotlin DSL Editor</span>
        <button class="template-btn" onclick="loadTemplate()">サンプルを挿入</button>
      </div>
      <div class="editor-wrap">
        <textarea id="editor" spellcheck="false" autocomplete="off" placeholder="oscSchema {&#10;    message(&quot;/example/path&quot;) {&#10;        description(&quot;メッセージの説明&quot;)&#10;        scalar(&quot;value&quot;, INT)&#10;    }&#10;}"></textarea>
        <div id="ac-popup"></div>
        <div id="cursor-mirror"></div>
      </div>
    </div>
    <div class="preview-pane">
      <div class="preview-header">Schema Preview</div>
      <div id="preview">
        <div class="empty-state">
          <div class="icon">📝</div>
          <div>左のエディタに Kotlin DSL を入力してください</div>
          <div style="margin-top:8px;font-size:11px;color:#475569;">入力するとリアルタイムでスキーマが可視化されます</div>
          <button class="template-btn" onclick="loadTemplate()" style="margin-top:16px;">サンプルを挿入して始める</button>
        </div>
      </div>
    </div>
  </div>
  <script>
    var debounceTimer = null;
    var editor = document.getElementById('editor');
    var preview = document.getElementById('preview');
    var statusEl = document.getElementById('status');
    var acPopup = document.getElementById('ac-popup');
    var cursorMirror = document.getElementById('cursor-mirror');
    var acIndex = -1;
    var acItems = [];
    var acVisible = false;
    var acPrefix = '';

    // コンテキスト別の補完候補定義
    var completions = {
      // トップレベル: oscSchema のみ
      top: [
        { label: 'oscSchema', insert: 'oscSchema {\n    \n}', kind: 'function', detail: 'スキーマ定義のルート' }
      ],
      // oscSchema ブロック内
      schema: [
        { label: 'message', insert: 'message(\x22', kind: 'function', detail: 'メッセージ定義' },
        { label: 'bundle', insert: 'bundle(\x22', kind: 'function', detail: 'バンドル定義' }
      ],
      // message ブロック内
      message: [
        { label: 'description', insert: 'description(\x22', kind: 'function', detail: 'メッセージの説明' },
        { label: 'name', insert: 'name(\x22', kind: 'function', detail: 'メッセージの論理名' },
        { label: 'scalar', insert: 'scalar(\x22', kind: 'function', detail: 'スカラー引数' },
        { label: 'arg', insert: 'arg(\x22', kind: 'function', detail: 'スカラー引数 (別名)' },
        { label: 'array', insert: 'array(\x22', kind: 'function', detail: '配列引数' }
      ],
      // array ブロック内
      array: [
        { label: 'scalar', insert: 'scalar(', kind: 'function', detail: 'スカラー配列要素' },
        { label: 'tuple', insert: 'tuple {\n', kind: 'function', detail: 'タプル配列要素' }
      ],
      // tuple ブロック内
      tuple: [
        { label: 'field', insert: 'field(\x22', kind: 'function', detail: 'タプルフィールド' }
      ],
      // bundle ブロック内
      bundle: [
        { label: 'description', insert: 'description(\x22', kind: 'function', detail: 'バンドルの説明' },
        { label: 'message', insert: 'message(\x22', kind: 'function', detail: 'メッセージ参照' }
      ],
      // 型定数（引数コンテキストで使用）
      types: [
        { label: 'INT', insert: 'INT', kind: 'type', detail: '整数型' },
        { label: 'FLOAT', insert: 'FLOAT', kind: 'type', detail: '浮動小数点型' },
        { label: 'STRING', insert: 'STRING', kind: 'type', detail: '文字列型' },
        { label: 'BOOL', insert: 'BOOL', kind: 'type', detail: '真偽型' },
        { label: 'BLOB', insert: 'BLOB', kind: 'type', detail: 'バイナリ型' }
      ],
      // ロール定数
      roles: [
        { label: 'LENGTH', insert: 'LENGTH', kind: 'role', detail: '長さロール' },
        { label: 'VALUE', insert: 'VALUE', kind: 'role', detail: '値ロール' }
      ],
      // 名前付きパラメータ
      namedParams: [
        { label: 'role', insert: 'role = ', kind: 'param', detail: 'スカラーのロール指定' },
        { label: 'length', insert: 'length = ', kind: 'param', detail: '固定長指定' },
        { label: 'lengthFrom', insert: 'lengthFrom = \x22', kind: 'param', detail: '長さ参照フィールド' }
      ]
    };

    /**
     * カーソル位置のテキストから DSL のスコープコンテキストを解析する。
     * ブレースのネスト・直前のキーワードを追跡して、現在のスコープを判定する。
     */
    function detectContext(text, cursorPos) {
      var beforeCursor = text.substring(0, cursorPos);
      // スコープスタック: ブレースで囲まれたコンテキストを追跡
      var scopeStack = ['top'];
      var i = 0;
      var inString = false;
      var stringChar = '';

      while (i < beforeCursor.length) {
        var ch = beforeCursor[i];

        // 文字列リテラルの追跡
        if (!inString && (ch === '"' || ch === "'")) {
          inString = true;
          stringChar = ch;
          i++;
          continue;
        }
        if (inString) {
          if (ch === '\\') { i += 2; continue; }
          if (ch === stringChar) { inString = false; }
          i++;
          continue;
        }

        // ブレースの追跡
        if (ch === '{') {
          // 直前のキーワードを取得してスコープを判定
          var before = beforeCursor.substring(0, i).trimEnd();
          var keyword = getLastKeyword(before);
          if (keyword === 'oscSchema') scopeStack.push('schema');
          else if (keyword === 'message') scopeStack.push('message');
          else if (keyword === 'array') scopeStack.push('array');
          else if (keyword === 'tuple') scopeStack.push('tuple');
          else if (keyword === 'bundle') scopeStack.push('bundle');
          else scopeStack.push(scopeStack[scopeStack.length - 1]);
        } else if (ch === '}') {
          if (scopeStack.length > 1) scopeStack.pop();
        }
        i++;
      }

      var scope = scopeStack[scopeStack.length - 1];

      // カーソル直前が関数引数の途中（括弧内）かチェック
      var inArgs = isInsideArgs(beforeCursor);

      return { scope: scope, inArgs: inArgs };
    }

    /**
     * テキスト末尾から直前のキーワードを抽出する。
     */
    function getLastKeyword(text) {
      var m = text.match(/(\w+)\s*\([^)]*\)\s*$/);
      if (m) return m[1];
      m = text.match(/(\w+)\s*$/);
      if (m) return m[1];
      return '';
    }

    /**
     * カーソルが関数引数の括弧内にいるかどうかを判定する。
     */
    function isInsideArgs(text) {
      var depth = 0;
      var inStr = false;
      var strCh = '';
      for (var i = text.length - 1; i >= 0; i--) {
        var ch = text[i];
        if (inStr) {
          if (ch === strCh && (i === 0 || text[i-1] !== '\\')) inStr = false;
          continue;
        }
        if (ch === '"' || ch === "'") { inStr = true; strCh = ch; continue; }
        if (ch === ')') depth++;
        if (ch === '(') {
          if (depth === 0) return true;
          depth--;
        }
        if (ch === '{' || ch === '}' || ch === '\n') {
          // ブレースや改行にぶつかったら括弧内ではない
          if (depth <= 0) return false;
        }
      }
      return false;
    }

    /**
     * カーソル位置から現在入力中の単語プレフィクスを取得する。
     */
    function getCurrentWord(text, cursorPos) {
      var before = text.substring(0, cursorPos);
      var m = before.match(/(\w+)$/);
      return m ? m[1] : '';
    }

    /**
     * コンテキストに基づいた補完候補を取得する。
     */
    function getSuggestions(context, prefix) {
      var items = [];

      if (context.inArgs) {
        // 括弧内: 型定数・ロール定数・名前付きパラメータを候補に
        items = items.concat(completions.types);
        items = items.concat(completions.roles);
        items = items.concat(completions.namedParams);
      } else {
        // ブロック内: スコープに応じた関数候補
        var scopeItems = completions[context.scope] || [];
        items = items.concat(scopeItems);
        // 型定数もトップレベル外では常に候補にする
        if (context.scope !== 'top') {
          items = items.concat(completions.types);
        }
      }

      // プレフィクスでフィルタ
      if (prefix) {
        var lowerPrefix = prefix.toLowerCase();
        items = items.filter(function(item) {
          return item.label.toLowerCase().indexOf(lowerPrefix) === 0;
        });
      }

      return items;
    }

    /**
     * テキストエリア内のカーソルのピクセル位置を算出する。
     */
    function getCursorCoords() {
      var text = editor.value.substring(0, editor.selectionStart);
      cursorMirror.style.width = editor.clientWidth + 'px';
      cursorMirror.textContent = text;
      var span = document.createElement('span');
      span.textContent = '|';
      cursorMirror.appendChild(span);

      var editorRect = editor.getBoundingClientRect();
      var wrapRect = editor.parentElement.getBoundingClientRect();
      var spanRect = span.getBoundingClientRect();
      var mirrorRect = cursorMirror.getBoundingClientRect();

      return {
        left: spanRect.left - mirrorRect.left,
        top: spanRect.top - mirrorRect.top - editor.scrollTop + editor.offsetTop
      };
    }

    /**
     * 補完ポップアップを表示する。
     */
    function showAc(items, prefix) {
      if (items.length === 0) { hideAc(); return; }

      acItems = items;
      acPrefix = prefix;
      acIndex = 0;
      acVisible = true;

      var html = '';
      items.forEach(function(item, idx) {
        html += '<div class="ac-item' + (idx === 0 ? ' ac-active' : '') + '" data-idx="' + idx + '">';
        html += '<span class="ac-label">' + esc(item.label) + '</span>';
        html += '<span class="ac-kind">' + esc(item.detail || item.kind) + '</span>';
        html += '</div>';
      });
      html += '<div class="ac-hint">↑↓ 選択 · Tab/Enter 確定 · Esc 閉じる</div>';
      acPopup.innerHTML = html;

      // ポップアップの位置をカーソルに合わせる
      var coords = getCursorCoords();
      acPopup.style.left = coords.left + 'px';
      acPopup.style.top = (coords.top + 22) + 'px';
      acPopup.style.display = 'block';

      // クリックで選択
      acPopup.querySelectorAll('.ac-item').forEach(function(el) {
        el.addEventListener('mousedown', function(e) {
          e.preventDefault();
          acIndex = parseInt(el.dataset.idx);
          acceptAc();
        });
      });
    }

    /**
     * 補完ポップアップを非表示にする。
     */
    function hideAc() {
      acPopup.style.display = 'none';
      acVisible = false;
      acItems = [];
      acIndex = -1;
    }

    /**
     * 選択中の補完候補を適用する。
     */
    function acceptAc() {
      if (acIndex < 0 || acIndex >= acItems.length) { hideAc(); return; }
      var item = acItems[acIndex];
      var pos = editor.selectionStart;
      var before = editor.value.substring(0, pos);
      var after = editor.value.substring(pos);

      // 入力中のプレフィクスを置換
      var newBefore = before.substring(0, before.length - acPrefix.length) + item.insert;
      editor.value = newBefore + after;
      editor.selectionStart = editor.selectionEnd = newBefore.length;
      editor.focus();
      hideAc();
      triggerEvaluate();
    }

    /**
     * 補完候補の選択位置を更新する。
     */
    function updateAcSelection() {
      var items = acPopup.querySelectorAll('.ac-item');
      items.forEach(function(el, idx) {
        if (idx === acIndex) {
          el.classList.add('ac-active');
          el.scrollIntoView({ block: 'nearest' });
        } else {
          el.classList.remove('ac-active');
        }
      });
    }

    /**
     * 補完を起動する。
     */
    function triggerAc() {
      var pos = editor.selectionStart;
      var text = editor.value;
      var prefix = getCurrentWord(text, pos);

      // 文字列リテラル内では補完しない
      var beforeCursor = text.substring(0, pos);
      var quoteCount = (beforeCursor.match(/"/g) || []).length;
      // 最後の行だけチェック
      var lastLine = beforeCursor.split('\n').pop() || '';
      var lineQuotes = (lastLine.match(/"/g) || []).length;
      if (lineQuotes % 2 === 1) { hideAc(); return; }

      var context = detectContext(text, pos);
      var suggestions = getSuggestions(context, prefix);

      // プレフィクスが空で非トリガーの場合は補完しない
      if (!prefix && suggestions.length > 5) { hideAc(); return; }

      showAc(suggestions, prefix);
    }

    // キーボードイベント: Tab/Enter/↑/↓/Esc のハンドリング
    editor.addEventListener('keydown', function(e) {
      if (acVisible) {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          acIndex = (acIndex + 1) % acItems.length;
          updateAcSelection();
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          acIndex = (acIndex - 1 + acItems.length) % acItems.length;
          updateAcSelection();
          return;
        }
        if (e.key === 'Enter' || (e.key === 'Tab' && !e.shiftKey)) {
          e.preventDefault();
          acceptAc();
          return;
        }
        if (e.key === 'Escape') {
          e.preventDefault();
          hideAc();
          return;
        }
      }

      // Tab キーでインデント挿入（補完非表示時）
      if (e.key === 'Tab' && !acVisible) {
        e.preventDefault();
        var start = this.selectionStart;
        var end = this.selectionEnd;
        this.value = this.value.substring(0, start) + '    ' + this.value.substring(end);
        this.selectionStart = this.selectionEnd = start + 4;
        triggerEvaluate();
      }

      // Ctrl+Space で補完を明示的に起動
      if ((e.ctrlKey || e.metaKey) && e.key === ' ') {
        e.preventDefault();
        var pos = editor.selectionStart;
        var text = editor.value;
        var prefix = getCurrentWord(text, pos);
        var context = detectContext(text, pos);
        var suggestions = getSuggestions(context, prefix);
        showAc(suggestions, prefix);
      }
    });

    // 入力時にデバウンスして評価 + 補完を起動
    editor.addEventListener('input', function() {
      triggerEvaluate();
      triggerAc();
    });

    // フォーカスが外れたら補完を閉じる（少し遅延）
    editor.addEventListener('blur', function() {
      setTimeout(hideAc, 200);
    });

    function triggerEvaluate() {
      clearTimeout(debounceTimer);
      var text = editor.value.trim();
      if (!text) {
        showEmpty();
        return;
      }
      setStatus('loading', '評価中...');
      debounceTimer = setTimeout(function() { evaluate(text); }, 600);
    }

    function evaluate(dslText) {
      fetch('/api/evaluate', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({dsl: dslText})
      }).then(function(r) { return r.json(); }).then(function(data) {
        if (data.success) {
          setStatus('ok', 'スキーマ有効 ✓');
          renderSchema(data.schema);
        } else {
          setStatus('err', 'エラー ✗');
          showError(data.error);
        }
      }).catch(function(err) {
        setStatus('err', 'ネットワークエラー');
        showError('サーバーに接続できません: ' + err.message);
      });
    }

    function setStatus(type, text) {
      statusEl.textContent = text;
      statusEl.className = 'status-' + type;
    }

    function showEmpty() {
      setStatus('idle', '入力待ち');
      preview.innerHTML = '<div class="empty-state"><div class="icon">📝</div>'
        + '<div>左のエディタに Kotlin DSL を入力してください</div>'
        + '<div style="margin-top:8px;font-size:11px;color:#475569;">入力するとリアルタイムでスキーマが可視化されます</div>'
        + '<button class="template-btn" onclick="loadTemplate()" style="margin-top:16px;">サンプルを挿入して始める</button>'
        + '</div>';
    }

    function showError(message) {
      preview.innerHTML = '<div id="error-display" style="display:block"><pre>' + esc(message) + '</pre></div>';
    }

    function renderSchema(schema) {
      var html = '';

      // メッセージセクション
      if (schema.messages && schema.messages.length > 0) {
        html += '<div class="section-title">Messages (' + schema.messages.length + ')</div>';
        schema.messages.forEach(function(msg) {
          html += '<div class="msg-card">';
          html += '<div class="msg-path">' + esc(msg.path) + '</div>';
          html += '<div class="msg-name">' + esc(msg.name) + '</div>';
          if (msg.description) {
            html += '<div class="msg-desc">' + esc(msg.description) + '</div>';
          }
          if (msg.args && msg.args.length > 0) {
            html += '<div class="msg-args"><div class="msg-args-title">Arguments</div>';
            msg.args.forEach(function(arg) {
              html += renderArg(arg);
            });
            html += '</div>';
          }
          html += '</div>';
        });
      }

      // バンドルセクション
      if (schema.bundles && schema.bundles.length > 0) {
        html += '<div class="section-title" style="margin-top:16px;">Bundles (' + schema.bundles.length + ')</div>';
        schema.bundles.forEach(function(bundle) {
          html += '<div class="bundle-card">';
          html += '<div class="bundle-name">' + esc(bundle.name) + '</div>';
          if (bundle.description) {
            html += '<div class="bundle-desc">' + esc(bundle.description) + '</div>';
          }
          if (bundle.messageRefs && bundle.messageRefs.length > 0) {
            html += '<div class="bundle-refs">';
            bundle.messageRefs.forEach(function(ref) {
              html += '<div class="bundle-ref">' + esc(ref) + '</div>';
            });
            html += '</div>';
          }
          html += '</div>';
        });
      }

      if (!html) {
        html = '<div class="empty-state"><div>スキーマにメッセージが定義されていません</div></div>';
      }

      preview.innerHTML = html;
    }

    function renderArg(arg) {
      if (arg.kind === 'scalar') {
        var html = '<div class="arg-item">';
        html += '<span class="arg-name">' + esc(arg.name) + '</span>';
        html += '<span class="arg-type">' + esc(arg.type) + '</span>';
        if (arg.role && arg.role !== 'value') {
          html += '<span class="arg-role">' + esc(arg.role) + '</span>';
        }
        html += '</div>';
        return html;
      } else if (arg.kind === 'array') {
        var html = '<div class="array-item">';
        html += '<div class="array-header">';
        html += '<span class="arg-name">' + esc(arg.name) + '</span>';
        html += '<span class="arg-type">array</span>';
        if (arg.length) {
          if (arg.length.kind === 'fixed') {
            html += '<span class="array-length">length: ' + arg.length.size + '</span>';
          } else if (arg.length.kind === 'fromField') {
            html += '<span class="array-length">length from: ' + esc(arg.length.fieldName) + '</span>';
          }
        }
        html += '</div>';
        if (arg.item) {
          html += '<div class="array-children">';
          if (arg.item.kind === 'scalar') {
            html += '<div class="arg-item"><span class="arg-type">' + esc(arg.item.type) + '</span></div>';
          } else if (arg.item.kind === 'tuple' && arg.item.fields) {
            arg.item.fields.forEach(function(f) {
              html += '<div class="arg-item"><span class="arg-name">' + esc(f.name) + '</span><span class="arg-type">' + esc(f.type) + '</span></div>';
            });
          }
          html += '</div>';
        }
        html += '</div>';
        return html;
      }
      return '';
    }

    function loadTemplate() {
      editor.value = 'oscSchema {\n'
        + '    message("/light/color") {\n'
        + '        description("RGB カラーを設定する")\n'
        + '        scalar("r", INT)\n'
        + '        scalar("g", INT)\n'
        + '        scalar("b", INT)\n'
        + '    }\n'
        + '\n'
        + '    message("/synth/volume") {\n'
        + '        description("シンセサイザーの音量を設定する")\n'
        + '        scalar("level", FLOAT)\n'
        + '    }\n'
        + '\n'
        + '    message("/mesh/points") {\n'
        + '        description("XYZ 座標点群を設定する")\n'
        + '        scalar("pointCount", INT, role = LENGTH)\n'
        + '        array("points", lengthFrom = "pointCount") {\n'
        + '            tuple {\n'
        + '                field("x", INT)\n'
        + '                field("y", INT)\n'
        + '                field("z", FLOAT)\n'
        + '            }\n'
        + '        }\n'
        + '    }\n'
        + '\n'
        + '    bundle("SceneBundle") {\n'
        + '        description("シーン管理バンドル")\n'
        + '        message("/light/color")\n'
        + '        message("/synth/volume")\n'
        + '    }\n'
        + '}';
      triggerEvaluate();
    }

    function esc(s) {
      return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }
  </script>
</body>
</html>
"""
        .trimIndent()
  }
}
