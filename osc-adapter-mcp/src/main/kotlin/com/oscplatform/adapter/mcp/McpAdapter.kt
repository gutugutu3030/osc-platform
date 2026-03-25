package com.oscplatform.adapter.mcp

import com.oscplatform.adapter.webui.WebUiLogEvent
import com.oscplatform.adapter.webui.WebUiMode
import com.oscplatform.adapter.webui.WebUiServer
import com.oscplatform.adapter.webui.WebUiServerConfig
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscBundleSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.schema.loader.SchemaPathResolver
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/**
 * MCP（Model Context Protocol）アダプター。
 *
 * OSCスキーマを読み込み、MCPサーバーとして標準入出力経由でツール呼び出しを処理する。
 *
 * @param out 通常出力用の [PrintStream]
 * @param err エラー出力用の [PrintStream]
 */
class McpAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  /** ヘルプフラグの集合。 */
  private companion object {
    val helpFlags = setOf("help", "-h", "--help")
  }

  private val schemaLoader: SchemaLoader = SchemaLoader()

  /**
   * MCPサブコマンドの使用方法の概要文字列を返す。
   *
   * @return コマンドライン使用方法の概要
   */
  fun commandSummary(): String =
      "osc mcp [schemaPath] [--schema path] --host <targetHost> --port <targetPort> [--webui] [--webui-port 8080]"

  /**
   * 使用方法のテキストを返す。
   *
   * @return 使用方法テキスト
   */
  fun usageText(): String = commandSummary()

  /**
   * コマンドライン引数を解析し、MCPサーバーを起動する。
   *
   * ヘルプフラグが指定された場合は使用方法を表示して正常終了する。 標準入出力とデフォルトのUDPトランスポートを使用する。
   *
   * @param args コマンドライン引数リスト
   * @return 終了コード（0: 正常終了、1: エラー）
   */
  suspend fun execute(args: List<String>): Int {
    if (args.isHelpRequest(helpFlags)) {
      printUsage()
      return 0
    }

    return execute(
        args,
        System.`in`,
        System.out,
        UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0),
    )
  }

  /**
   * 指定された入出力ストリームとトランスポートでMCPサーバーを起動する。
   *
   * テスト等でストリームやトランスポートを差し替えるために使用する。
   *
   * @param args コマンドライン引数リスト
   * @param input JSON-RPCメッセージを受信する入力ストリーム
   * @param output JSON-RPCメッセージを送信する出力ストリーム
   * @param transport OSCメッセージ送信に使用するトランスポート
   * @return 終了コード（0: 正常終了、1: エラー）
   */
  internal suspend fun execute(
      args: List<String>,
      input: InputStream,
      output: OutputStream,
      transport: OscTransport,
  ): Int {
    if (args.isHelpRequest(helpFlags)) {
      printUsage()
      return 0
    }

    val parsed =
        try {
          parseArgs(args)
        } catch (ex: McpUsageException) {
          err.println("error: ${ex.message}")
          printUsage()
          return 1
        } catch (ex: Exception) {
          err.println("error: ${ex.message ?: "Unexpected error"}")
          return 1
        }

    val schemaPath =
        try {
          resolveSchemaPath(parsed.schemaPath)
        } catch (ex: Exception) {
          err.println("error: ${ex.message ?: "Unexpected error"}")
          return 1
        }

    val schema =
        try {
          schemaLoader.load(schemaPath)
        } catch (ex: Exception) {
          err.println("error: ${ex.message ?: "Unexpected error"}")
          return 1
        }

    val runtime = OscRuntime(schema = schema, transport = transport)
    val webUiEventSink = MutableSharedFlow<WebUiLogEvent>(extraBufferCapacity = 32)
    var webUiServer: WebUiServer? = null

    if (parsed.webUiEnabled) {
      webUiServer =
          WebUiServer(
              schema = schema,
              runtime = runtime,
              config =
                  WebUiServerConfig(
                      mode = WebUiMode.MCP,
                      httpPort = parsed.webUiPort,
                      defaultTargetHost = parsed.host,
                      defaultTargetPort = parsed.port,
                  ),
              additionalEvents = webUiEventSink.asSharedFlow(),
          )
      webUiServer.start()
      err.println("Web UI: http://localhost:${webUiServer.port}")
    }

    val toolByName = schema.messages.associateBy { OscNaming.mcpToolName(it.path) }
    val bundleMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    val bundleToolByName =
        schema.bundles.associate { bundleSpec ->
          val resolvedSpecs = bundleSpec.messageRefs.map { ref -> schema.resolveMessage(ref)!! }
          val inputSchema =
              McpSchemaJsonSupport.toBundleInputSchema(
                  mapper = bundleMapper,
                  bundleSpec = bundleSpec,
                  resolvedSpecs = resolvedSpecs,
              )
          OscNaming.bundleToolName(bundleSpec.name) to
              McpBundleTool(
                  spec = bundleSpec,
                  resolvedSpecs = resolvedSpecs,
                  inputSchema = inputSchema,
              )
        }
    val protocol = McpStdioProtocol(input = input, output = output)
    val server =
        OscMcpServer(
            protocol = protocol,
            runtime = runtime,
            toolByName = toolByName,
            bundleToolByName = bundleToolByName,
            target = OscTarget(parsed.host, parsed.port),
            webUiEventSink = webUiEventSink,
        )

    err.println("MCP server started schema=$schemaPath target=${parsed.host}:${parsed.port}")
    return try {
      server.run()
      0
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      err.println("error: ${ex.message ?: "Unexpected error"}")
      1
    } finally {
      webUiServer?.stop()
      runtime.stop()
    }
  }

  /**
   * コマンドライン引数を解析して [McpConfig] を返す。
   *
   * @param args コマンドライン引数リスト
   * @return 解析された設定
   * @throws McpUsageException 引数の形式が不正な場合
   */
  private fun parseArgs(args: List<String>): McpConfig {
    var schemaPath: String? = null
    var host: String? = null
    var port: Int? = null
    var webUiEnabled = false
    var webUiPort = 8080

    var index = 0
    while (index < args.size) {
      val token = args[index]
      when {
        token == "--schema" -> {
          schemaPath = args.valueAfter(index, "--schema")
          index += 2
        }

        token.startsWith("--schema=") -> {
          schemaPath = token.substringAfter('=')
          index += 1
        }

        token == "--host" -> {
          host = args.valueAfter(index, "--host")
          index += 2
        }

        token.startsWith("--host=") -> {
          host = token.substringAfter('=')
          index += 1
        }

        token == "--port" -> {
          port =
              args.valueAfter(index, "--port").toIntOrNull()
                  ?: mcpUsageError("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: mcpUsageError("Invalid --port value")
          index += 1
        }

        token == "--webui" -> {
          webUiEnabled = true
          index += 1
        }

        token == "--webui-port" -> {
          webUiPort =
              args.valueAfter(index, "--webui-port").toIntOrNull()
                  ?: mcpUsageError("Invalid --webui-port value")
          index += 2
        }

        token.startsWith("--webui-port=") -> {
          webUiPort =
              token.substringAfter('=').toIntOrNull() ?: mcpUsageError("Invalid --webui-port value")
          index += 1
        }

        token.startsWith("--") -> mcpUsageError("Unknown mcp option: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }

        else -> mcpUsageError("Unexpected token in mcp command: $token")
      }
    }

    if (host.isNullOrBlank()) {
      mcpUsageError("mcp requires --host")
    }
    if (port == null) {
      mcpUsageError("mcp requires --port")
    }

    return McpConfig(
        schemaPath = schemaPath,
        host = host,
        port = port,
        webUiEnabled = webUiEnabled,
        webUiPort = webUiPort,
    )
  }

  /**
   * スキーマファイルのパスを解決する。
   *
   * @param explicitPath 明示的に指定されたパス（省略可能）
   * @return 解決されたスキーマファイルのパス
   */
  private fun resolveSchemaPath(explicitPath: String?): Path =
      SchemaPathResolver.resolve(
          explicitPath, warn = { message -> err.println("warning: $message") })

  /** 使用方法テキストを標準出力に表示する。 */
  private fun printUsage() {
    out.println(usageText())
  }
}

/**
 * MCPアダプターの設定を保持するデータクラス。
 *
 * @param schemaPath スキーマファイルのパス（省略可能）
 * @param host OSC送信先ホスト名
 * @param port OSC送信先ポート番号
 * @param webUiEnabled Web UIを有効にするかどうか
 * @param webUiPort Web UIのHTTPポート番号
 */
private data class McpConfig(
    val schemaPath: String?,
    val host: String,
    val port: Int,
    val webUiEnabled: Boolean,
    val webUiPort: Int,
)

/**
 * MCPプロトコルに基づくOSCサーバー。
 *
 * JSON-RPCメッセージを受信し、ツール呼び出しに対してOSCメッセージを送信する。
 *
 * @param protocol 標準入出力によるMCPプロトコル
 * @param runtime OSCメッセージの送信を担当するランタイム
 * @param toolByName ツール名からメッセージスペックへのマッピング
 * @param bundleToolByName バンドルツール名から [McpBundleTool] へのマッピング
 * @param target OSCメッセージの送信先ターゲット
 * @param webUiEventSink Web UIイベントの送信先フロー（省略可能）
 */
private class OscMcpServer(
    private val protocol: McpStdioProtocol,
    private val runtime: OscRuntime,
    private val toolByName: Map<String, OscMessageSpec>,
    private val bundleToolByName: Map<String, McpBundleTool> = emptyMap(),
    private val target: OscTarget,
    private val webUiEventSink: MutableSharedFlow<WebUiLogEvent>? = null,
) {
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /**
   * MCPサーバーのメインループを実行する。
   *
   * 標準入力からJSON-RPCメッセージを読み取り、メソッドに応じた処理を行う。 入力ストリームが終了するか "exit" メソッドを受信するまでループする。
   */
  suspend fun run() {
    while (true) {
      val messageBytes = protocol.readMessage() ?: break
      val root = mapper.readTree(messageBytes)
      val method = root.path("method").stringValue() ?: ""
      val id = root.get("id")
      emitRequestEvent(method = method, id = id, params = root.path("params"))

      when (method) {
        "initialize" -> {
          if (id != null) {
            val clientVersion = root.path("params").path("protocolVersion").stringValue()
            protocol.writeMessage(resultResponse(id, initializeResult(clientVersion)))
          }
        }

        "notifications/initialized" -> {
          // No response needed.
        }

        "tools/list" -> {
          if (id != null) {
            protocol.writeMessage(resultResponse(id, toolsListResult()))
          }
        }

        "tools/call" -> {
          if (id != null) {
            handleToolCall(id, root.path("params"))
          }
        }

        "shutdown" -> {
          if (id != null) {
            protocol.writeMessage(resultResponse(id, mapper.createObjectNode()))
          }
        }

        "exit" -> return

        else -> {
          if (id != null) {
            protocol.writeMessage(errorResponse(id, -32601, "Method not found: $method"))
          }
        }
      }
    }
  }

  /**
   * ツール呼び出しリクエストを処理する。
   *
   * 指定されたツール名に対応するOSCメッセージまたはバンドルを送信し、結果を返す。
   *
   * @param id JSON-RPCリクエストID
   * @param params リクエストパラメータ（ツール名と引数を含む）
   */
  private suspend fun handleToolCall(id: JsonNode, params: JsonNode) {
    try {
      val name = params.path("name").stringValue() ?: ""
      require(name.isNotBlank()) { "Tool name is required" }

      val argMap = linkedMapOf<String, Any?>()
      val argsNode = params.path("arguments")
      if (argsNode.isObject) {
        argsNode.properties().forEach { (argName, node) -> argMap[argName] = jsonNodeToValue(node) }
      }

      val bundleTool = bundleToolByName[name]
      if (bundleTool != null) {
        val bundleMessages =
            bundleTool.resolvedSpecs.map { msgSpec ->
              val perMessageArgs =
                  argMap.filterKeys { argName -> msgSpec.args.any { it.name == argName } }
              msgSpec.name to perMessageArgs
            }
        runtime.sendBundle(messages = bundleMessages, target = target)

        val text = bundleTool.resolvedSpecs.joinToString(", ") { it.path }
        val result =
            mapper.createObjectNode().apply {
              val content = mapper.createArrayNode()
              content.add(
                  mapper.createObjectNode().apply {
                    put("type", "text")
                    put(
                        "text",
                        "sent bundle [${bundleTool.spec.name}] ($text) to ${target.host}:${target.port}")
                  })
              set("content", content)
            }
        webUiEventSink?.tryEmit(
            WebUiLogEvent(
                type = "mcp_success",
                message = "$name -> ${target.host}:${target.port}",
            ),
        )
        protocol.writeMessage(resultResponse(id, result))
        return
      }

      val spec = toolByName[name] ?: error("Unknown tool: $name")

      runtime.send(
          messageRef = spec.name,
          rawArgs = argMap,
          target = target,
      )

      val result =
          mapper.createObjectNode().apply {
            val content = mapper.createArrayNode()
            content.add(
                mapper.createObjectNode().apply {
                  put("type", "text")
                  put("text", "sent ${spec.path} to ${target.host}:${target.port}")
                })
            set("content", content)
          }
      webUiEventSink?.tryEmit(
          WebUiLogEvent(
              type = "mcp_success",
              message = "$name -> ${target.host}:${target.port}",
          ),
      )
      protocol.writeMessage(resultResponse(id, result))
    } catch (ex: Exception) {
      webUiEventSink?.tryEmit(
          WebUiLogEvent(
              type = "mcp_failure",
              message = ex.message ?: "Tool call failed",
          ),
      )
      protocol.writeMessage(errorResponse(id, -32000, ex.message ?: "Tool call failed"))
    }
  }

  /**
   * 受信したMCPリクエストのイベントをWeb UIに送信する。
   *
   * @param method JSON-RPCメソッド名
   * @param id JSON-RPCリクエストID（通知の場合は null）
   * @param params リクエストパラメータ
   */
  private fun emitRequestEvent(method: String, id: JsonNode?, params: JsonNode) {
    val requestId = id?.toString() ?: "notification"
    val detailMap = linkedMapOf<String, Any?>("id" to requestId, "method" to method)
    if (!params.isMissingNode && !params.isNull) {
      detailMap["params"] = jsonNodeToValue(params)
    }
    webUiEventSink?.tryEmit(
        WebUiLogEvent(
            type = "mcp_request",
            message = "$method ($requestId)",
            details = detailMap,
        ),
    )
  }

  /**
   * MCPの initialize レスポンスの結果オブジェクトを生成する。
   *
   * クライアントが提示したバージョンがサポート対象であればそれを返し、 そうでなければ最新のサポートバージョンを返す。
   *
   * @param clientVersion クライアントが要求したプロトコルバージョン（省略可能）
   * @return initialize レスポンスの結果ノード
   */
  private fun initializeResult(clientVersion: String? = null): ObjectNode {
    val supportedVersions = listOf("2025-03-26", "2024-11-05")
    val negotiatedVersion =
        if (clientVersion != null && clientVersion in supportedVersions) clientVersion
        else supportedVersions.first()
    return mapper.createObjectNode().apply {
      put("protocolVersion", negotiatedVersion)
      set(
          "capabilities",
          mapper.createObjectNode().apply { set("tools", mapper.createObjectNode()) })
      set(
          "serverInfo",
          mapper.createObjectNode().apply {
            put("name", "osc-platform")
            put("version", loadAdapterVersion())
          })
    }
  }

  /**
   * アダプターのバージョン文字列をリソースプロパティから読み込む。
   *
   * バージョンプロパティが見つからない場合は "unknown" を返す。
   *
   * @return バージョン文字列
   */
  private fun loadAdapterVersion(): String {
    val props = Properties()
    try {
      McpAdapter::class
          .java
          .getResourceAsStream("/com/oscplatform/adapter/mcp/version.properties")
          ?.use { props.load(it) }
    } catch (_: IOException) {}
    return props.getProperty("version", "unknown")
  }

  /**
   * MCPの tools/list レスポンスの結果オブジェクトを生成する。
   *
   * スキーマに定義されたメッセージおよびバンドルをツール一覧として返す。
   *
   * @return tools/list レスポンスの結果ノード
   */
  private fun toolsListResult(): ObjectNode {
    return mapper.createObjectNode().apply {
      val toolsNode = mapper.createArrayNode()
      toolByName.forEach { (toolName, spec) ->
        toolsNode.add(
            mapper.createObjectNode().apply {
              put("name", toolName)
              put("description", spec.description ?: "Send OSC message to ${spec.path}")
              set("inputSchema", toInputSchema(spec))
            })
      }
      bundleToolByName.forEach { (toolName, bundleTool) ->
        val paths = bundleTool.resolvedSpecs.joinToString(", ") { it.path }
        toolsNode.add(
            mapper.createObjectNode().apply {
              put("name", toolName)
              put(
                  "description",
                  bundleTool.spec.description
                      ?: "Send OSC bundle [${bundleTool.spec.name}] ($paths)")
              set("inputSchema", bundleTool.inputSchema)
            })
      }
      set("tools", toolsNode)
    }
  }

  /**
   * OSCメッセージスペックからMCPツールの入力スキーマを生成する。
   *
   * @param spec OSCメッセージスペック
   * @return JSON Schema形式の入力スキーマノード
   */
  private fun toInputSchema(spec: OscMessageSpec): ObjectNode {
    return McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)
  }

  /**
   * 正常応答のJSON-RPCレスポンスノードを生成する。
   *
   * @param id JSON-RPCリクエストID
   * @param result 結果オブジェクト
   * @return JSON-RPCレスポンスノード
   */
  private fun resultResponse(id: JsonNode, result: ObjectNode): ObjectNode {
    return mapper.createObjectNode().apply {
      put("jsonrpc", "2.0")
      set("id", id)
      set("result", result)
    }
  }

  /**
   * エラー応答のJSON-RPCレスポンスノードを生成する。
   *
   * @param id JSON-RPCリクエストID
   * @param code エラーコード
   * @param message エラーメッセージ
   * @return JSON-RPCエラーレスポンスノード
   */
  private fun errorResponse(id: JsonNode, code: Int, message: String): ObjectNode {
    return mapper.createObjectNode().apply {
      put("jsonrpc", "2.0")
      set("id", id)
      set(
          "error",
          mapper.createObjectNode().apply {
            put("code", code)
            put("message", message)
          })
    }
  }

  /**
   * [JsonNode] をKotlinの値に変換する。
   *
   * [McpSchemaJsonSupport.jsonNodeToValue] に委譲する。
   *
   * @param node 変換対象のJSONノード
   * @return 変換されたKotlinの値（null の場合あり）
   */
  private fun jsonNodeToValue(node: JsonNode): Any? {
    return McpSchemaJsonSupport.jsonNodeToValue(node)
  }
}

/**
 * MCPバンドルツールの定義を保持するデータクラス。
 *
 * @param spec OSCバンドルスペック
 * @param resolvedSpecs バンドルに含まれる解決済みメッセージスペックのリスト
 * @param inputSchema ツールの入力スキーマ（JSON Schema形式）
 */
internal data class McpBundleTool(
    val spec: OscBundleSpec,
    val resolvedSpecs: List<OscMessageSpec>,
    val inputSchema: ObjectNode,
)

/**
 * MCPツールのJSON Schema生成を担当するユーティリティオブジェクト。
 *
 * OSCスキーマの引数定義からJSON Schema形式の入力スキーマを生成する。
 */
internal object McpSchemaJsonSupport {
  /**
   * OSCバンドルスペックからMCPツールの入力スキーマを生成する。
   *
   * バンドル内の全メッセージのプロパティを統合した単一のJSON Schemaを返す。
   *
   * @param mapper JSON生成に使用する [ObjectMapper]
   * @param bundleSpec OSCバンドルスペック
   * @param resolvedSpecs バンドルに含まれる解決済みメッセージスペックのリスト
   * @return JSON Schema形式の入力スキーマノード
   */
  fun toBundleInputSchema(
      mapper: ObjectMapper,
      bundleSpec: OscBundleSpec,
      resolvedSpecs: List<OscMessageSpec>,
  ): ObjectNode {
    val properties = mapper.createObjectNode()
    val required = mapper.createArrayNode()

    resolvedSpecs.forEach { spec ->
      val specSchema = toInputSchema(mapper = mapper, spec = spec)
      specSchema.path("properties").properties().forEach { (name, schema) ->
        properties.set(name, schema as ObjectNode)
      }
      specSchema.path("required").forEach { node -> required.add(node.stringValue() ?: "") }
    }

    return mapper.createObjectNode().apply {
      put("type", "object")
      set("properties", properties)
      set("required", required)
      put("additionalProperties", false)
    }
  }

  /**
   * OSCメッセージスペックからMCPツールの入力スキーマを生成する。
   *
   * 自動導出可能な長さフィールドはrequiredから除外される。
   *
   * @param mapper JSON生成に使用する [ObjectMapper]
   * @param spec OSCメッセージスペック
   * @return JSON Schema形式の入力スキーマノード
   */
  fun toInputSchema(mapper: ObjectMapper, spec: OscMessageSpec): ObjectNode {
    val properties = mapper.createObjectNode()
    val required = mapper.createArrayNode()
    val autoDerivableLengthFields =
        spec.args
            .mapNotNull { node ->
              if (node is ArrayArgNode) {
                when (val length = node.length) {
                  is LengthSpec.FromField -> length.fieldName
                  is LengthSpec.Fixed -> null
                }
              } else {
                null
              }
            }
            .toSet()

    spec.args.forEach { arg ->
      properties.set(arg.name, toJsonSchemaForArg(mapper = mapper, arg = arg))

      val isOptionalDerivedLength =
          arg is ScalarArgNode &&
              arg.role == ScalarRole.LENGTH &&
              autoDerivableLengthFields.contains(arg.name)
      if (!isOptionalDerivedLength) {
        required.add(arg.name)
      }
    }

    return mapper.createObjectNode().apply {
      put("type", "object")
      set("properties", properties)
      set("required", required)
      put("additionalProperties", false)
    }
  }

  /**
   * OSC引数ノードに対応するJSON Schemaを生成する。
   *
   * スカラー引数と配列引数のそれぞれに適切なスキーマを返す。
   *
   * @param mapper JSON生成に使用する [ObjectMapper]
   * @param arg OSC引数ノード
   * @return JSON Schemaノード
   */
  private fun toJsonSchemaForArg(mapper: ObjectMapper, arg: OscArgNode): ObjectNode {
    return when (arg) {
      is ScalarArgNode ->
          jsonScalarSchema(mapper = mapper, type = arg.type).apply {
            if (arg.role == ScalarRole.LENGTH) {
              put("description", "length field")
              put("minimum", 0)
            }
          }

      is ArrayArgNode ->
          mapper.createObjectNode().apply {
            put("type", "array")
            set("items", toJsonSchemaForArrayItem(mapper = mapper, item = arg.item))
            when (val length = arg.length) {
              is LengthSpec.Fixed -> {
                put("minItems", length.size)
                put("maxItems", length.size)
              }

              is LengthSpec.FromField -> {
                put("description", "length is controlled by '${length.fieldName}'")
                put("x-osc-lengthFrom", length.fieldName)
              }
            }
          }
    }
  }

  /**
   * 配列要素の仕様に対応するJSON Schemaを生成する。
   *
   * スカラー要素はスカラースキーマ、タプル要素はオブジェクトスキーマとなる。
   *
   * @param mapper JSON生成に使用する [ObjectMapper]
   * @param item 配列要素の仕様
   * @return JSON Schemaノード
   */
  private fun toJsonSchemaForArrayItem(mapper: ObjectMapper, item: ArrayItemSpec): ObjectNode {
    return when (item) {
      is ArrayItemSpec.ScalarItem -> jsonScalarSchema(mapper = mapper, type = item.type)
      is ArrayItemSpec.TupleItem -> {
        val properties = mapper.createObjectNode()
        val required = mapper.createArrayNode()
        item.fields.forEach { field ->
          properties.set(field.name, jsonScalarSchema(mapper = mapper, type = field.type))
          required.add(field.name)
        }
        mapper.createObjectNode().apply {
          put("type", "object")
          set("properties", properties)
          set("required", required)
          put("additionalProperties", false)
        }
      }
    }
  }

  /**
   * OSCスカラー型に対応するJSON Schemaを生成する。
   *
   * @param mapper JSON生成に使用する [ObjectMapper]
   * @param type OSCスカラー型
   * @return JSON Schemaノード
   */
  private fun jsonScalarSchema(mapper: ObjectMapper, type: OscType): ObjectNode {
    return when (type) {
      OscType.INT -> mapper.createObjectNode().apply { put("type", "integer") }
      OscType.FLOAT -> mapper.createObjectNode().apply { put("type", "number") }
      OscType.STRING -> mapper.createObjectNode().apply { put("type", "string") }
      OscType.BOOL -> mapper.createObjectNode().apply { put("type", "boolean") }
      OscType.BLOB ->
          mapper.createObjectNode().apply {
            put("type", "string")
            put("contentEncoding", "base64")
            put("description", "base64-encoded binary data")
          }
    }
  }

  /**
   * [JsonNode] をKotlinの値に変換する。
   *
   * 文字列、数値、真偽値、配列、オブジェクト、nullのそれぞれを適切な型に変換する。
   *
   * @param node 変換対象のJSONノード
   * @return 変換されたKotlinの値（null の場合あり）
   */
  fun jsonNodeToValue(node: JsonNode): Any? {
    return when {
      node.isString -> node.stringValue()!!
      node.isInt -> node.intValue()
      node.isLong -> node.longValue()
      node.isFloat || node.isDouble || node.isBigDecimal -> node.doubleValue()
      node.isBoolean -> node.booleanValue()
      node.isArray -> node.toList().map { child -> jsonNodeToValue(child) }
      node.isObject ->
          linkedMapOf<String, Any?>().also { map ->
            node.properties().forEach { (key, value) -> map[key] = jsonNodeToValue(value) }
          }
      node.isNull -> null
      else -> node.toString()
    }
  }
}

/**
 * MCP標準入出力プロトコルの実装。
 *
 * JSON-RPCメッセージをContent-Lengthヘッダ付きのフレーム形式で送受信する。
 *
 * @param input メッセージを受信する入力ストリーム
 * @param output メッセージを送信する出力ストリーム
 */
private class McpStdioProtocol(
    input: InputStream,
    private val output: OutputStream,
) {
  private val input = BufferedInputStream(input)
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /**
   * 入力ストリームからMCPメッセージを1件読み取る。
   *
   * Content-Lengthヘッダを解析し、指定されたバイト数のペイロードを読み取る。 ストリームが終了した場合やContent-Lengthが不正な場合は null を返す。
   *
   * @return 読み取ったメッセージのバイト配列、またはストリーム終了時に null
   */
  fun readMessage(): ByteArray? {
    var contentLength = -1

    while (true) {
      val line = readHeaderLine() ?: return null
      if (line.isEmpty()) {
        break
      }

      val separator = line.indexOf(':')
      if (separator < 0) {
        continue
      }

      val key = line.substring(0, separator).trim()
      val value = line.substring(separator + 1).trim()
      if (key.equals("Content-Length", ignoreCase = true)) {
        contentLength = value.toIntOrNull() ?: -1
      }
    }

    if (contentLength <= 0) {
      return null
    }

    val payload = ByteArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
      val read = input.read(payload, offset, contentLength - offset)
      if (read < 0) {
        return null
      }
      offset += read
    }
    return payload
  }

  /**
   * JSON-RPCレスポンスノードをMCPフレーム形式で出力ストリームに書き込む。
   *
   * Content-Lengthヘッダ付きでペイロードを送信し、ストリームをフラッシュする。
   *
   * @param node 送信するJSON-RPCレスポンスノード
   */
  fun writeMessage(node: ObjectNode) {
    val payload = mapper.writeValueAsBytes(node)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    output.write(header)
    output.write(payload)
    output.flush()
  }

  /**
   * 入力ストリームからヘッダ行を1行読み取る。
   *
   * CRLFおよびLFの両方に対応する。ストリーム終了時に null を返す。
   *
   * @return 読み取ったヘッダ行の文字列、またはストリーム終了時に null
   */
  private fun readHeaderLine(): String? {
    val bytes = ArrayList<Byte>()
    while (true) {
      val current = input.read()
      if (current < 0) {
        return if (bytes.isEmpty()) null else bytes.toAsciiString()
      }

      if (current == '\n'.code) {
        // 行末の CR があれば取り除く（CRLF でも LF のみでも両対応）
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
          bytes.removeAt(bytes.lastIndex)
        }
        return bytes.toAsciiString()
      }

      bytes.add(current.toByte())
    }
  }
}

/**
 * バイトリストをASCII文字列に変換する。
 *
 * @return ASCII文字列
 */
private fun List<Byte>.toAsciiString(): String {
  val array = ByteArray(size)
  for (index in indices) {
    array[index] = this[index]
  }
  return array.toString(StandardCharsets.US_ASCII)
}

/**
 * 指定インデックスの次の要素を取得する。
 *
 * 次の要素が存在しない場合はエラーを投げる。
 *
 * @param index 現在のインデックス
 * @param option オプション名（エラーメッセージに使用）
 * @return 次の要素の文字列
 * @throws McpUsageException 次の要素が存在しない場合
 */
private fun List<String>.valueAfter(index: Int, option: String): String {
  if (index + 1 >= size) {
    mcpUsageError("$option requires a value")
  }
  return this[index + 1]
}

/**
 * リストがヘルプリクエストかどうかを判定する。
 *
 * 要素が1つだけで、その要素がヘルプフラグに含まれる場合に true を返す。
 *
 * @param helpFlags ヘルプフラグの集合
 * @return ヘルプリクエストであれば true
 */
private fun List<String>.isHelpRequest(helpFlags: Set<String>): Boolean =
    size == 1 && first() in helpFlags

/**
 * MCP使用方法エラーを投げるユーティリティ関数。
 *
 * @param message エラーメッセージ
 * @return この関数は常に例外を投げるため、戻り値は存在しない
 * @throws McpUsageException 常に投げられる
 */
private fun mcpUsageError(message: String): Nothing = throw McpUsageException(message)

/**
 * MCP引数解析時の使用方法エラーを表す例外。
 *
 * @param message エラーメッセージ
 */
private class McpUsageException(message: String) : IllegalArgumentException(message)
