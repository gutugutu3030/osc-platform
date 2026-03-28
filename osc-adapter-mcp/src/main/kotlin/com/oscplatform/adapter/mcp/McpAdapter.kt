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
import com.oscplatform.core.util.JsonNodeConverter
import com.oscplatform.transport.udp.UdpOscTransport
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/** streamable HTTP のデフォルト待受ホスト。 */
private const val DEFAULT_LISTEN_HOST = "127.0.0.1"

/** streamable HTTP エンドポイントのパス。 */
private const val STREAMABLE_HTTP_PATH = "/mcp"

/** streamable HTTP で利用するセッションヘッダー名。 */
private const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"

/** streamable HTTP で利用するプロトコルバージョンヘッダー名。 */
private const val MCP_PROTOCOL_VERSION_HEADER = "Mcp-Protocol-Version"

/**
 * MCP（Model Context Protocol）アダプター。
 *
 * OSC スキーマを読み込み、stdio または streamable HTTP 経由で OSC ツールを公開する。
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
  private val schemaMapper: ObjectMapper =
      JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
  private val jsonCodec: Json = Json { ignoreUnknownKeys = true }

  /**
   * MCP サブコマンドの使用方法の概要文字列を返す。
   *
   * @return コマンドライン使用方法の概要
   */
  fun commandSummary(): String =
      "osc mcp [schemaPath] [--schema path] --host <targetHost> --port <targetPort> [--streamable-http-port <port>] [--listen-host <host>] [--webui] [--webui-port 8080]"

  /**
   * 使用方法のテキストを返す。
   *
   * @return 使用方法テキスト
   */
  fun usageText(): String = commandSummary()

  /**
   * コマンドライン引数を解析し、MCP サーバーを起動する。
   *
   * `--streamable-http-port` が指定された場合は streamable HTTP サーバーを起動し、 省略時は stdio サーバーとして起動する。
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
        args = args,
        input = System.`in`,
        output = System.out,
        transport = UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0),
    )
  }

  /**
   * 指定された入出力ストリームとトランスポートで MCP サーバーを起動する。
   *
   * テストでは `httpServerLifecycle` を差し替えることで、streamable HTTP モードでも 起動直後に接続確認や停止制御を行える。
   *
   * @param args コマンドライン引数リスト
   * @param input stdio モードで JSON-RPC メッセージを受信する入力ストリーム
   * @param output stdio モードで JSON-RPC メッセージを送信する出力ストリーム
   * @param transport OSC メッセージ送信に使用するトランスポート
   * @param httpServerLifecycle streamable HTTP モード起動後の待機処理
   * @return 終了コード（0: 正常終了、1: エラー）
   */
  internal suspend fun execute(
      args: List<String>,
      input: InputStream,
      output: OutputStream,
      transport: OscTransport,
      httpServerLifecycle: suspend (McpHttpServerHandle) -> Unit = { handle ->
        handle.stopSignal.await()
      },
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
    val target = OscTarget(parsed.host, parsed.port)
    val webUiEventSink = MutableSharedFlow<WebUiLogEvent>(extraBufferCapacity = 32)
    var webUiServer: WebUiServer? = null
    var httpServer: McpRunningHttpServer? = null
    var server: Server? = null

    try {
      // 補助 UI を先に起動しておくことで、後続の MCP リクエストと OSC 送信結果を即座に確認できるようにする。
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

      server =
          createServer(
              schema = schema,
              runtime = runtime,
              target = target,
              webUiEventSink = webUiEventSink,
          )

      return if (parsed.streamableHttpPort != null) {
        httpServer =
            startStreamableHttpServer(
                server = server,
                host = parsed.listenHost,
                port = parsed.streamableHttpPort,
            )
        err.println(
            "MCP streamable HTTP server started endpoint=http://${httpServer.handle.host}:${httpServer.handle.port}${httpServer.handle.path} schema=$schemaPath target=${parsed.host}:${parsed.port}")
        httpServerLifecycle(httpServer.handle)
        0
      } else {
        err.println(
            "MCP stdio server started schema=$schemaPath target=${parsed.host}:${parsed.port}")
        runStdioServer(server = server, input = input, output = output)
        0
      }
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      err.println("error: ${ex.message ?: "Unexpected error"}")
      return 1
    } finally {
      httpServer?.server?.stop(500, 700, TimeUnit.MILLISECONDS)
      server?.close()
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
    var streamableHttpPort: Int? = null
    var listenHost = DEFAULT_LISTEN_HOST

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
          port = args.valueAfter(index, "--port").toPortOrUsage("--port")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toPortOrUsage("--port")
          index += 1
        }

        token == "--webui" -> {
          webUiEnabled = true
          index += 1
        }

        token == "--webui-port" -> {
          webUiPort = args.valueAfter(index, "--webui-port").toPortOrUsage("--webui-port")
          index += 2
        }

        token.startsWith("--webui-port=") -> {
          webUiPort = token.substringAfter('=').toPortOrUsage("--webui-port")
          index += 1
        }

        token == "--streamable-http-port" -> {
          streamableHttpPort =
              args
                  .valueAfter(index, "--streamable-http-port")
                  .toPortOrUsage("--streamable-http-port")
          index += 2
        }

        token.startsWith("--streamable-http-port=") -> {
          streamableHttpPort = token.substringAfter('=').toPortOrUsage("--streamable-http-port")
          index += 1
        }

        token == "--listen-host" -> {
          listenHost = args.valueAfter(index, "--listen-host")
          index += 2
        }

        token.startsWith("--listen-host=") -> {
          listenHost = token.substringAfter('=')
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
    if (listenHost.isBlank()) {
      mcpUsageError("--listen-host requires a non-blank value")
    }
    if (streamableHttpPort == null && listenHost != DEFAULT_LISTEN_HOST) {
      mcpUsageError("--listen-host requires --streamable-http-port")
    }
    if (streamableHttpPort != null && webUiEnabled && streamableHttpPort == webUiPort) {
      mcpUsageError("--streamable-http-port must differ from --webui-port")
    }

    return McpConfig(
        schemaPath = schemaPath,
        host = host,
        port = port,
        webUiEnabled = webUiEnabled,
        webUiPort = webUiPort,
        streamableHttpPort = streamableHttpPort,
        listenHost = listenHost,
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
          explicitPath,
          warn = { message -> err.println("warning: $message") },
      )

  /**
   * 使用方法テキストを標準出力に表示する。
   *
   * @return この関数は値を返さない
   */
  private fun printUsage() {
    out.println(usageText())
  }

  /**
   * OSC スキーマから MCP サーバーを組み立てる。
   *
   * @param schema 読み込まれた OSC スキーマ
   * @param runtime OSC メッセージ送信に使用するランタイム
   * @param target OSC メッセージの送信先
   * @param webUiEventSink Web UI へイベントを送るフロー
   * @return 構成済みの MCP サーバー
   */
  private fun createServer(
      schema: com.oscplatform.core.schema.OscSchema,
      runtime: OscRuntime,
      target: OscTarget,
      webUiEventSink: MutableSharedFlow<WebUiLogEvent>,
  ): Server {
    val registeredTools =
        buildRegisteredTools(
            schema = schema,
            runtime = runtime,
            target = target,
            webUiEventSink = webUiEventSink,
        )
    val toolByName = registeredTools.associateBy { it.tool.name }

    return Server(
            serverInfo = Implementation(name = "osc-platform", version = loadAdapterVersion()),
            options =
                ServerOptions(
                    capabilities =
                        ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = true),
                        ),
                ),
            instructions =
                "osc-platform MCP server. Use the available tools to send OSC messages to ${target.host}:${target.port}.",
        )
        .apply {
          onConnect {
            val session = sessions.values.lastOrNull() ?: return@onConnect
            session.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { _, _ ->
              webUiEventSink.tryEmit(
                  WebUiLogEvent(
                      type = "mcp_request",
                      message = "tools/list",
                  ),
              )
              ListToolsResult(tools = registeredTools.map { it.tool })
            }
            session.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
              // 受信した tool 呼び出しを先に UI へ流し、その後で実行結果を成功・失敗に分けて反映する。
              val details =
                  linkedMapOf<String, Any?>("method" to "tools/call", "name" to request.name)
              request.arguments?.let { details["arguments"] = toArgumentMap(it) }
              webUiEventSink.tryEmit(
                  WebUiLogEvent(
                      type = "mcp_request",
                      message = "tools/call: ${request.name}",
                      details = details,
                  ),
              )

              val registeredTool = toolByName[request.name]
              if (registeredTool == null) {
                webUiEventSink.tryEmit(
                    WebUiLogEvent(
                        type = "mcp_failure",
                        message = "Unknown tool: ${request.name}",
                    ),
                )
                return@setRequestHandler textToolResult(
                    text = "Tool ${request.name} not found",
                    isError = true,
                )
              }

              registeredTool.handler(request.arguments)
            }
          }
        }
  }

  /**
   * OSC スキーマから公開する MCP ツール一覧を構築する。
   *
   * @param schema 読み込まれた OSC スキーマ
   * @param runtime OSC メッセージ送信に使用するランタイム
   * @param target OSC メッセージの送信先
   * @param webUiEventSink Web UI へイベントを送るフロー
   * @return 登録順を保持した MCP ツール一覧
   */
  private fun buildRegisteredTools(
      schema: com.oscplatform.core.schema.OscSchema,
      runtime: OscRuntime,
      target: OscTarget,
      webUiEventSink: MutableSharedFlow<WebUiLogEvent>,
  ): List<McpRegisteredTool> {
    val messageTools =
        schema.messages.map { spec ->
          buildMessageTool(
              spec = spec,
              runtime = runtime,
              target = target,
              webUiEventSink = webUiEventSink,
          )
        }
    val bundleTools =
        schema.bundles.map { bundleSpec ->
          buildBundleTool(
              schema = schema,
              bundleSpec = bundleSpec,
              runtime = runtime,
              target = target,
              webUiEventSink = webUiEventSink,
          )
        }
    return messageTools + bundleTools
  }

  /**
   * 単一 OSC メッセージ用の MCP ツール定義を構築する。
   *
   * @param spec OSC メッセージ仕様
   * @param runtime OSC メッセージ送信に使用するランタイム
   * @param target OSC メッセージの送信先
   * @param webUiEventSink Web UI へイベントを送るフロー
   * @return 構成済みの MCP ツール定義
   */
  private fun buildMessageTool(
      spec: OscMessageSpec,
      runtime: OscRuntime,
      target: OscTarget,
      webUiEventSink: MutableSharedFlow<WebUiLogEvent>,
  ): McpRegisteredTool {
    val toolName = OscNaming.mcpToolName(spec.path)
    val inputSchema = McpSchemaJsonSupport.toInputSchema(mapper = schemaMapper, spec = spec)

    return McpRegisteredTool(
        tool =
            Tool(
                name = toolName,
                description = spec.description ?: "Send OSC message to ${spec.path}",
                inputSchema = toToolSchema(inputSchema),
            ),
        handler = { arguments ->
          try {
            val argMap = toArgumentMap(arguments)
            runtime.send(messageRef = spec.name, rawArgs = argMap, target = target)
            webUiEventSink.tryEmit(
                WebUiLogEvent(
                    type = "mcp_success",
                    message = "$toolName -> ${target.host}:${target.port}",
                ),
            )
            textToolResult(text = "sent ${spec.path} to ${target.host}:${target.port}")
          } catch (ex: Exception) {
            webUiEventSink.tryEmit(
                WebUiLogEvent(
                    type = "mcp_failure",
                    message = ex.message ?: "Tool call failed",
                ),
            )
            textToolResult(text = ex.message ?: "Tool call failed", isError = true)
          }
        },
    )
  }

  /**
   * OSC バンドル用の MCP ツール定義を構築する。
   *
   * @param schema 読み込まれた OSC スキーマ
   * @param bundleSpec OSC バンドル仕様
   * @param runtime OSC メッセージ送信に使用するランタイム
   * @param target OSC メッセージの送信先
   * @param webUiEventSink Web UI へイベントを送るフロー
   * @return 構成済みの MCP ツール定義
   */
  private fun buildBundleTool(
      schema: com.oscplatform.core.schema.OscSchema,
      bundleSpec: OscBundleSpec,
      runtime: OscRuntime,
      target: OscTarget,
      webUiEventSink: MutableSharedFlow<WebUiLogEvent>,
  ): McpRegisteredTool {
    val resolvedSpecs = bundleSpec.messageRefs.map { ref -> schema.resolveMessage(ref)!! }
    val inputSchema =
        McpSchemaJsonSupport.toBundleInputSchema(
            mapper = schemaMapper,
            bundleSpec = bundleSpec,
            resolvedSpecs = resolvedSpecs,
        )
    val toolName = OscNaming.bundleToolName(bundleSpec.name)
    val description =
        bundleSpec.description
            ?: "Send OSC bundle [${bundleSpec.name}] (${resolvedSpecs.joinToString(", ") { it.path }})"

    return McpRegisteredTool(
        tool =
            Tool(
                name = toolName,
                description = description,
                inputSchema = toToolSchema(inputSchema),
            ),
        handler = { arguments ->
          try {
            val argMap = toArgumentMap(arguments)

            // バンドルの各メッセージに対して、必要な引数だけを切り出して並び順を維持したまま送信する。
            val bundleMessages =
                resolvedSpecs.map { msgSpec ->
                  val perMessageArgs =
                      argMap.filterKeys { argName -> msgSpec.args.any { it.name == argName } }
                  msgSpec.name to perMessageArgs
                }
            runtime.sendBundle(messages = bundleMessages, target = target)

            webUiEventSink.tryEmit(
                WebUiLogEvent(
                    type = "mcp_success",
                    message = "$toolName -> ${target.host}:${target.port}",
                ),
            )
            textToolResult(
                text =
                    "sent bundle [${bundleSpec.name}] (${resolvedSpecs.joinToString(", ") { it.path }}) to ${target.host}:${target.port}")
          } catch (ex: Exception) {
            webUiEventSink.tryEmit(
                WebUiLogEvent(
                    type = "mcp_failure",
                    message = ex.message ?: "Tool call failed",
                ),
            )
            textToolResult(text = ex.message ?: "Tool call failed", isError = true)
          }
        },
    )
  }

  /**
   * Jackson の JSON Schema ノードを MCP SDK の [ToolSchema] に変換する。
   *
   * @param schemaNode JSON Schema 形式のノード
   * @return MCP SDK が要求するツールスキーマ
   */
  private fun toToolSchema(schemaNode: ObjectNode): ToolSchema {
    val propertiesNode = schemaNode.path("properties")
    val properties =
        if (propertiesNode.isObject) {
          jsonCodec.parseToJsonElement(schemaMapper.writeValueAsString(propertiesNode))
              as JsonObject
        } else {
          null
        }
    val requiredNode = schemaNode.path("required")
    val required =
        if (requiredNode.isArray) {
          mutableListOf<String>().apply {
            for (node in requiredNode) {
              node.stringValue()?.let { value -> add(value) }
            }
          }
        } else {
          null
        }
    return ToolSchema(properties = properties, required = required)
  }

  /**
   * stdio transport で MCP サーバーを実行する。
   *
   * @param server 実行する MCP サーバー
   * @param input 標準入力として扱う入力ストリーム
   * @param output 標準出力として扱う出力ストリーム
   * @return この関数は値を返さない
   */
  private suspend fun runStdioServer(server: Server, input: InputStream, output: OutputStream) {
    val transport =
        StdioServerTransport(
            inputStream = input.asSource().buffered(),
            outputStream = output.asSink().buffered(),
        )
    val done = CompletableDeferred<Unit>()
    val session = server.createSession(transport)
    session.onClose { done.complete(Unit) }
    done.await()
  }

  /**
   * streamable HTTP transport で MCP サーバーを起動する。
   *
   * @param server 実行する MCP サーバー
   * @param host 待受ホスト
   * @param port 待受ポート
   * @return 実際の待受情報と Ktor サーバーを束ねた実行ハンドル
   */
  private suspend fun startStreamableHttpServer(
      server: Server,
      host: String,
      port: Int,
  ): McpRunningHttpServer {
    val embeddedServer =
        embeddedServer(CIO, host = host, port = port) {
              installMcpCors(includeStreamableHeaders = true)
              install(ContentNegotiation) { json(McpJson) }
              mcpStreamableHttp(path = STREAMABLE_HTTP_PATH) { server }
            }
            .start(wait = false)
    val resolvedPort = embeddedServer.engine.resolvedConnectors().single().port
    return McpRunningHttpServer(
        handle =
            McpHttpServerHandle(
                host = host,
                port = resolvedPort,
                path = STREAMABLE_HTTP_PATH,
                stopSignal = CompletableDeferred(),
            ),
        server = embeddedServer,
    )
  }

  /**
   * MCP 用の CORS 設定を Ktor アプリケーションへ追加する。
   *
   * @param includeStreamableHeaders streamable HTTP 用ヘッダーを許可・公開するかどうか
   * @return この関数は値を返さない
   */
  private fun Application.installMcpCors(includeStreamableHeaders: Boolean) {
    install(CORS) {
      allowMethod(HttpMethod.Options)
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Delete)
      allowHeader(HttpHeaders.ContentType)
      allowNonSimpleContentTypes = true
      if (includeStreamableHeaders) {
        allowHeader(MCP_SESSION_ID_HEADER)
        allowHeader(MCP_PROTOCOL_VERSION_HEADER)
        exposeHeader(MCP_SESSION_ID_HEADER)
        exposeHeader(MCP_PROTOCOL_VERSION_HEADER)
      }
      anyHost()
    }
  }

  /**
   * アダプターのバージョン文字列をリソースプロパティから読み込む。
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
   * MCP の引数オブジェクトを OSC 実行用の引数マップへ変換する。
   *
   * @param arguments MCP リクエストの引数オブジェクト
   * @return OSC 実行用の名前付き引数マップ
   */
  private fun toArgumentMap(arguments: JsonObject?): Map<String, Any?> {
    if (arguments == null) {
      return emptyMap()
    }
    val tree = schemaMapper.readTree(arguments.toString())
    @Suppress("UNCHECKED_CAST")
    return (McpSchemaJsonSupport.jsonNodeToValue(tree) as? Map<String, Any?>) ?: emptyMap()
  }

  /**
   * テキストだけを返す [CallToolResult] を生成する。
   *
   * @param text クライアントへ返すメッセージ
   * @param isError エラー結果として返すかどうか
   * @return 構築されたツール実行結果
   */
  private fun textToolResult(text: String, isError: Boolean = false): CallToolResult =
      CallToolResult(content = listOf(TextContent(text = text)), isError = isError)
}

/**
 * MCP アダプターの設定を保持するデータクラス。
 *
 * @param schemaPath スキーマファイルのパス（省略可能）
 * @param host OSC 送信先ホスト名
 * @param port OSC 送信先ポート番号
 * @param webUiEnabled Web UI を有効にするかどうか
 * @param webUiPort Web UI の HTTP ポート番号
 * @param streamableHttpPort streamable HTTP の待受ポート番号（省略時は stdio）
 * @param listenHost streamable HTTP の待受ホスト名
 */
private data class McpConfig(
    val schemaPath: String?,
    val host: String,
    val port: Int,
    val webUiEnabled: Boolean,
    val webUiPort: Int,
    val streamableHttpPort: Int?,
    val listenHost: String,
)

/**
 * streamable HTTP サーバーの公開情報を保持するデータクラス。
 *
 * @param host 実際に待ち受けているホスト
 * @param port 実際に待ち受けているポート
 * @param path MCP エンドポイントのパス
 * @param stopSignal テスト用に実行待機を解除するシグナル
 */
internal data class McpHttpServerHandle(
    val host: String,
    val port: Int,
    val path: String,
    val stopSignal: CompletableDeferred<Unit>,
)

/**
 * streamable HTTP サーバーの実行中インスタンスを保持するデータクラス。
 *
 * @param handle 外部へ公開する待受情報
 * @param server 停止制御に使用する Ktor サーバー
 */
private data class McpRunningHttpServer(
    val handle: McpHttpServerHandle,
    val server: EmbeddedServer<*, *>,
)

/**
 * MCP ツール定義とその実行ハンドラーを束ねるデータクラス。
 *
 * @param tool 公開する MCP ツール定義
 * @param handler ツール呼び出しを処理するハンドラー
 */
private data class McpRegisteredTool(
    val tool: Tool,
    val handler: suspend (JsonObject?) -> CallToolResult,
)

/**
 * MCP バンドルツールの定義を保持するデータクラス。
 *
 * @param spec OSC バンドルスペック
 * @param resolvedSpecs バンドルに含まれる解決済みメッセージスペックのリスト
 * @param inputSchema ツールの入力スキーマ（JSON Schema 形式）
 */
internal data class McpBundleTool(
    val spec: OscBundleSpec,
    val resolvedSpecs: List<OscMessageSpec>,
    val inputSchema: ObjectNode,
)

/**
 * MCP ツールの JSON Schema 生成を担当するユーティリティオブジェクト。
 *
 * OSC スキーマの引数定義から JSON Schema 形式の入力スキーマを生成する。
 */
internal object McpSchemaJsonSupport {
  /**
   * OSC バンドルスペックから MCP ツールの入力スキーマを生成する。
   *
   * バンドル内の全メッセージのプロパティを統合した単一の JSON Schema を返す。
   *
   * @param mapper JSON 生成に使用する [ObjectMapper]
   * @param bundleSpec OSC バンドルスペック
   * @param resolvedSpecs バンドルに含まれる解決済みメッセージスペックのリスト
   * @return JSON Schema 形式の入力スキーマノード
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
   * OSC メッセージスペックから MCP ツールの入力スキーマを生成する。
   *
   * 自動導出可能な長さフィールドは required から除外される。
   *
   * @param mapper JSON 生成に使用する [ObjectMapper]
   * @param spec OSC メッセージスペック
   * @return JSON Schema 形式の入力スキーマノード
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
   * OSC 引数ノードに対応する JSON Schema を生成する。
   *
   * @param mapper JSON 生成に使用する [ObjectMapper]
   * @param arg OSC 引数ノード
   * @return JSON Schema ノード
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
   * 配列要素の仕様に対応する JSON Schema を生成する。
   *
   * @param mapper JSON 生成に使用する [ObjectMapper]
   * @param item 配列要素の仕様
   * @return JSON Schema ノード
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
   * OSC スカラー型に対応する JSON Schema を生成する。
   *
   * @param mapper JSON 生成に使用する [ObjectMapper]
   * @param type OSC スカラー型
   * @return JSON Schema ノード
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
   * [JsonNode] を Kotlin の値に変換する。
   *
   * @param node 変換対象の JSON ノード
   * @return 変換された Kotlin の値（null の場合あり）
   */
  fun jsonNodeToValue(node: JsonNode): Any? = JsonNodeConverter.jsonNodeToValue(node)
}

/**
 * 指定インデックスの次の要素を取得する。
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
 * @param helpFlags ヘルプフラグの集合
 * @return ヘルプリクエストであれば true
 */
private fun List<String>.isHelpRequest(helpFlags: Set<String>): Boolean =
    size == 1 && first() in helpFlags

/**
 * 文字列をポート番号へ変換する。
 *
 * @param option オプション名（エラーメッセージに使用）
 * @return 変換されたポート番号
 * @throws McpUsageException ポート番号として不正な場合
 */
private fun String.toPortOrUsage(option: String): Int {
  val parsed = toIntOrNull() ?: mcpUsageError("Invalid $option value")
  if (parsed !in 0..65535) {
    mcpUsageError("Invalid $option value")
  }
  return parsed
}

/**
 * MCP 使用方法エラーを投げるユーティリティ関数。
 *
 * @param message エラーメッセージ
 * @return この関数は常に例外を投げるため、戻り値は存在しない
 * @throws McpUsageException 常に投げられる
 */
private fun mcpUsageError(message: String): Nothing = throw McpUsageException(message)

/**
 * MCP 引数解析時の使用方法エラーを表す例外。
 *
 * @param message エラーメッセージ
 */
private class McpUsageException(message: String) : IllegalArgumentException(message)
