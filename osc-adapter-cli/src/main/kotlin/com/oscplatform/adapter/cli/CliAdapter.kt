package com.oscplatform.adapter.cli

import com.oscplatform.adapter.webui.WebUiMode
import com.oscplatform.adapter.webui.WebUiServer
import com.oscplatform.adapter.webui.WebUiServerConfig
import com.oscplatform.codegen.CodeGenOptions
import com.oscplatform.codegen.KotlinCodeGenerator
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.schema.loader.SchemaPathResolver
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * OSCプラットフォームのCLIアダプター。
 *
 * コマンドライン引数を解析し、run・send・doc・list・validate・gen・versionの各コマンドを実行する。
 *
 * @param out 標準出力先のPrintStream
 * @param err エラー出力先のPrintStream
 */
class CliAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  /** ヘルプフラグ定数を保持するコンパニオンオブジェクト。 */
  private companion object {
    val helpFlags = setOf("help", "-h", "--help")
  }

  private val schemaLoader: SchemaLoader = SchemaLoader()

  /**
   * 利用可能なコマンド名の一覧を返す。
   *
   * @return コマンド名のセット
   */
  fun commandNames(): Set<String> =
      setOf("run", "send", "doc", "list", "validate", "gen", "version")

  /**
   * 各コマンドの使い方の概要行リストを返す。
   *
   * @return コマンドごとの使い方文字列のリスト
   */
  fun commandSummaries(): List<String> =
      listOf(
          runUsageLine(),
          sendUsageLine(),
          docUsageLine(),
          listUsageLine(),
          validateUsageLine(),
          genUsageLine(),
          versionUsageLine(),
      )

  /**
   * 全コマンドの使い方テキストを返す。
   *
   * @return 使い方テキスト全体の文字列
   */
  fun usageText(): String = buildString {
    commandSummaries().forEach { appendLine(it) }
    appendLine()
    append(
        "messageRef accepts schema message name (e.g. light.color) or OSC path (e.g. /light/color).",
    )
  }

  /**
   * 指定されたコマンドの使い方テキストを返す。
   *
   * @param command 使い方を取得するコマンド名
   * @return 指定コマンドの使い方テキスト
   */
  fun commandUsageText(command: String): String =
      when (command) {
        "run" -> runUsageLine()
        "send" ->
            buildString {
              appendLine(sendUsageLine())
              append(
                  "messageRef accepts schema message name (e.g. light.color) or OSC path (e.g. /light/color).",
              )
            }
        "doc" -> docUsageLine()
        "list" -> listUsageLine()
        "validate" -> validateUsageLine()
        "gen" -> genUsageLine()
        "version" -> versionUsageLine()
        else -> usageText()
      }

  /**
   * コマンドライン引数を解析し、対応するコマンドを実行する。
   *
   * @param args コマンドライン引数のリスト
   * @return 終了コード（0: 成功、1: エラー）
   */
  suspend fun execute(args: List<String>): Int {
    if (args.isEmpty()) {
      printUsage()
      return 1
    }

    val command = args.first()
    val commandArgs = args.drop(1)

    if (command in helpFlags) {
      printUsage()
      return 0
    }

    return try {
      when (command) {
        "run" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            runServer(commandArgs)
          }
        }
        "send" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            sendMessage(commandArgs)
          }
        }
        "doc" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            generateDoc(commandArgs)
          }
        }
        "list" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            listSchema(commandArgs)
          }
        }
        "validate" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            validateSchema(commandArgs)
          }
        }
        "gen" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            generateCode(commandArgs)
          }
        }
        "version" -> {
          if (commandArgs.isHelpRequest(helpFlags)) {
            printCommandUsage(command)
            0
          } else {
            printVersion(commandArgs)
          }
        }
        else -> {
          err.println("error: Unknown command: $command")
          printUsage()
          1
        }
      }
    } catch (ex: CliUsageException) {
      err.println("error: ${ex.message}")
      printCommandUsage(command)
      1
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      err.println("error: ${ex.message ?: "Unexpected error"}")
      1
    }
  }

  /**
   * OSCランタイムサーバーを起動し、受信イベントを標準出力に表示する。
   *
   * シャットダウンフックにより安全に停止する。キャンセルされるまでブロックする。
   *
   * @param args runコマンドの引数リスト
   * @return 終了コード
   */
  private suspend fun runServer(args: List<String>): Int {
    val parsed = parseRunCommand(args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    val transport = UdpOscTransport(bindHost = parsed.host, bindPort = parsed.port)
    val runtime = OscRuntime(schema = schema, transport = transport)
    var webUiServer: WebUiServer? = null

    runtime.start()
    out.println("OSC runtime started")
    out.println("schema: $schemaPath")
    out.println("bind: ${parsed.host}:${parsed.port}")

    if (parsed.webUiEnabled) {
      webUiServer =
          WebUiServer(
              schema = schema,
              runtime = runtime,
              config =
                  WebUiServerConfig(
                      mode = WebUiMode.MONITOR,
                      httpPort = parsed.webUiPort,
                  ),
          )
      webUiServer.start()
      out.println("Web UI: http://localhost:${webUiServer.port}")
    }

    val eventJob =
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
          runtime.events.collect { event ->
            when (event) {
              is OscRuntimeEvent.Received -> {
                out.println("recv ${event.spec.path} ${event.namedArgs}")
              }

              is OscRuntimeEvent.ValidationError -> {
                err.println("validation_error ${event.address ?: "-"}: ${event.reason}")
              }

              is OscRuntimeEvent.TransportErrorEvent -> {
                val e = event.error
                err.println(
                    "transport_error [consecutive=${e.consecutiveCount}]: ${e.cause.message}",
                )
              }

              is OscRuntimeEvent.SendStarted -> {}
              is OscRuntimeEvent.SendSucceeded -> {}
              is OscRuntimeEvent.SendFailed -> {}
            }
          }
        }

    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              runBlocking {
                webUiServer?.stop()
                runtime.stop()
              }
            },
        )

    try {
      awaitCancellation()
    } finally {
      eventJob.cancelAndJoin()
      webUiServer?.stop()
      runtime.stop()
    }
  }

  /**
   * 指定されたメッセージをOSCターゲットに送信する。
   *
   * WebUIモードが有効な場合は送信用WebUIサーバーを起動する。
   *
   * @param args sendコマンドの引数リスト
   * @return 終了コード
   */
  private suspend fun sendMessage(args: List<String>): Int {
    val parsed = parseSendCommand(args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    val transport = UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0)
    val runtime = OscRuntime(schema = schema, transport = transport)

    if (parsed.webUiEnabled) {
      val server =
          WebUiServer(
              schema = schema,
              runtime = runtime,
              config =
                  WebUiServerConfig(
                      mode = WebUiMode.SENDER,
                      httpPort = parsed.webUiPort,
                      defaultTargetHost = parsed.host,
                      defaultTargetPort = parsed.port,
                      initialMessageRef = parsed.messageRef,
                      initialArgs = parsed.arguments,
                  ),
          )
      server.start()

      out.println("OSC send Web UI started")
      out.println("schema: $schemaPath")
      out.println("target default: ${parsed.host}:${parsed.port}")
      out.println("Web UI: http://localhost:${server.port}")

      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                runBlocking {
                  server.stop()
                  runtime.stop()
                }
              },
          )

      try {
        awaitCancellation()
      } finally {
        server.stop()
        runtime.stop()
      }
    }

    runtime.send(
        messageRef = requireNotNull(parsed.messageRef),
        rawArgs = parsed.arguments,
        target = OscTarget(host = parsed.host, port = parsed.port),
    )

    out.println(
        "sent ${parsed.messageRef} -> ${parsed.host}:${parsed.port} args=${parsed.arguments}",
    )
    return 0
  }

  /**
   * スキーマからKotlinコードを生成する。
   *
   * @param args genコマンドの引数リスト
   * @return 終了コード
   */
  private fun generateCode(args: List<String>): Int {
    val parsed = parseGenCommand(args)
    if (parsed.packageName.isNullOrBlank()) {
      usageError(
          "gen requires --package. Example: osc gen --schema schema.yaml --package com.example.generated --lang kotlin --out build/generated/sources/osc",
      )
    }
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)
    val options =
        CodeGenOptions(packageName = requireNotNull(parsed.packageName), language = parsed.lang)
    val files =
        when (options.language) {
          "kotlin" -> KotlinCodeGenerator().generate(schema, options)
          else -> usageError("Unsupported --lang: ${options.language}. Supported: kotlin")
        }
    val outputRoot = Path.of(parsed.outputPath ?: "build/generated/sources/osc")
    files.forEach { (relativePath, content) ->
      val file = outputRoot.resolve(relativePath)
      Files.createDirectories(file.parent)
      Files.writeString(file, content, StandardCharsets.UTF_8)
      out.println("generated: $file")
    }
    out.println("gen complete: ${files.size} file(s)")
    return 0
  }

  /**
   * スキーマの内容をメッセージ・バンドル一覧として標準出力に表示する。
   *
   * @param args listコマンドの引数リスト
   * @return 終了コード
   */
  private fun listSchema(args: List<String>): Int {
    val parsed = parseSchemaLookupCommand(command = "list", args = args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    out.println("schema: $schemaPath")
    out.println("messages (${schema.messages.size}):")
    schema.messages
        .sortedBy { it.name }
        .forEach { spec ->
          val descriptionSuffix =
              spec.description?.takeIf { it.isNotBlank() }?.let { " :: $it" } ?: ""
          out.println("- ${spec.name} -> ${spec.path}${descriptionSuffix}")
          if (spec.args.isNotEmpty()) {
            out.println("  args: ${spec.args.joinToString(", ") { arg -> formatArgSummary(arg) }}")
          }
        }

    out.println("bundles (${schema.bundles.size}):")
    if (schema.bundles.isEmpty()) {
      out.println("- none")
    } else {
      schema.bundles
          .sortedBy { it.name }
          .forEach { bundle ->
            val refs =
                bundle.messageRefs.mapNotNull { ref -> schema.resolveMessage(ref)?.name ?: ref }
            val descriptionSuffix =
                bundle.description?.takeIf { it.isNotBlank() }?.let { " :: $it" } ?: ""
            out.println("- ${bundle.name} -> ${refs.joinToString(", ")}${descriptionSuffix}")
          }
    }
    return 0
  }

  /**
   * スキーマファイルの妥当性を検証し、結果を標準出力に表示する。
   *
   * @param args validateコマンドの引数リスト
   * @return 終了コード
   */
  private fun validateSchema(args: List<String>): Int {
    val parsed = parseSchemaLookupCommand(command = "validate", args = args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    out.println("schema valid: $schemaPath")
    out.println("messages: ${schema.messages.size}")
    out.println("bundles: ${schema.bundles.size}")
    return 0
  }

  /**
   * CLIのバージョン情報を標準出力に表示する。
   *
   * @param args versionコマンドの引数リスト（空であること）
   * @return 終了コード
   */
  private fun printVersion(args: List<String>): Int {
    if (args.isNotEmpty()) {
      usageError("Unexpected token in version command: ${args.first()}")
    }
    out.println("osc-platform ${loadCliVersion()}")
    return 0
  }

  /**
   * genコマンドの引数を解析し、[GenConfig]を返す。
   *
   * @param args genコマンドの引数リスト
   * @return パースされたコード生成設定
   * @throws CliUsageException 不正なオプションが指定された場合
   */
  private fun parseGenCommand(args: List<String>): GenConfig {
    var schemaPath: String? = null
    var packageName: String? = null
    var lang = "kotlin"
    var outputPath: String? = null

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
        token == "--package" -> {
          packageName = args.valueAfter(index, "--package")
          index += 2
        }
        token.startsWith("--package=") -> {
          packageName = token.substringAfter('=')
          index += 1
        }
        token == "--lang" -> {
          lang = args.valueAfter(index, "--lang")
          index += 2
        }
        token.startsWith("--lang=") -> {
          lang = token.substringAfter('=')
          index += 1
        }
        token == "--out" -> {
          outputPath = args.valueAfter(index, "--out")
          index += 2
        }
        token.startsWith("--out=") -> {
          outputPath = token.substringAfter('=')
          index += 1
        }
        token.startsWith("--") -> usageError("Unknown option for gen: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }
        else -> usageError("Unexpected token in gen command: $token")
      }
    }
    return GenConfig(
        schemaPath = schemaPath,
        packageName = packageName,
        lang = lang,
        outputPath = outputPath,
    )
  }

  /**
   * スキーマからドキュメントファイルを生成する。
   *
   * HTMLまたはMarkdown形式で出力し、出力パスを標準出力に表示する。
   *
   * @param args docコマンドの引数リスト
   * @return 終了コード
   */
  private fun generateDoc(args: List<String>): Int {
    val parsed = parseDocCommand(args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)
    val format = resolveDocFormat(parsed.format, parsed.outputPath)
    val outputPath = resolveDocOutputPath(parsed.outputPath, format)
    val docContent =
        when (format) {
          DocFormat.HTML ->
              SchemaHtmlDocRenderer.render(
                  schema = schema,
                  schemaPath = schemaPath,
                  title = parsed.title,
              )
          DocFormat.MARKDOWN ->
              SchemaMarkdownDocRenderer.render(
                  schema = schema,
                  schemaPath = schemaPath,
                  title = parsed.title,
              )
        }

    val parent = outputPath.parent ?: error("Invalid --out path: $outputPath")
    Files.createDirectories(parent)
    Files.writeString(outputPath, docContent, StandardCharsets.UTF_8)

    out.println("generated schema docs: $outputPath")
    return 0
  }

  /**
   * listまたはvalidateコマンドのスキーマパス引数を解析する。
   *
   * @param command コマンド名（エラーメッセージに使用）
   * @param args コマンドの引数リスト
   * @return パースされたスキーマ検索設定
   * @throws CliUsageException 不正なオプションが指定された場合
   */
  private fun parseSchemaLookupCommand(command: String, args: List<String>): SchemaLookupConfig {
    var schemaPath: String? = null

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
        token.startsWith("--") -> usageError("Unknown option for $command: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }
        else -> usageError("Unexpected token in $command command: $token")
      }
    }

    return SchemaLookupConfig(schemaPath = schemaPath)
  }

  /**
   * runコマンドの引数を解析し、[RunConfig]を返す。
   *
   * @param args runコマンドの引数リスト
   * @return パースされたサーバー起動設定
   * @throws CliUsageException 不正なオプションが指定された場合
   */
  private fun parseRunCommand(args: List<String>): RunConfig {
    var schemaPath: String? = null
    var host = "0.0.0.0"
    var port = 9000
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
              args.valueAfter(index, "--port").toIntOrNull() ?: usageError("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: usageError("Invalid --port value")
          index += 1
        }

        token == "--webui" -> {
          webUiEnabled = true
          index += 1
        }

        token == "--webui-port" -> {
          webUiPort =
              args.valueAfter(index, "--webui-port").toIntOrNull()
                  ?: usageError("Invalid --webui-port value")
          index += 2
        }

        token.startsWith("--webui-port=") -> {
          webUiPort =
              token.substringAfter('=').toIntOrNull() ?: usageError("Invalid --webui-port value")
          index += 1
        }

        token.startsWith("--") -> usageError("Unknown option for run: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }

        else -> usageError("Unexpected token in run command: $token")
      }
    }

    return RunConfig(
        schemaPath = schemaPath,
        host = host,
        port = port,
        webUiEnabled = webUiEnabled,
        webUiPort = webUiPort,
    )
  }

  /**
   * sendコマンドの引数を解析し、[SendConfig]を返す。
   *
   * メッセージ参照や動的引数、WebUI設定などを含む設定を構築する。
   *
   * @param args sendコマンドの引数リスト
   * @return パースされたメッセージ送信設定
   * @throws CliUsageException 必須引数が不足している場合や不正なオプションが指定された場合
   */
  private fun parseSendCommand(args: List<String>): SendConfig {
    if (args.isEmpty()) {
      usageError(
          "send command needs message ref. Example: osc send light.color --host 127.0.0.1 --port 9000 --r 255",
      )
    }

    var messageRef: String? = null
    var schemaPath: String? = null
    var host: String? = null
    var port: Int? = null
    var webUiEnabled = false
    var webUiPort = 8080
    val dynamicArgs = linkedMapOf<String, Any?>()

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
              args.valueAfter(index, "--port").toIntOrNull() ?: usageError("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: usageError("Invalid --port value")
          index += 1
        }

        token == "--webui" -> {
          webUiEnabled = true
          index += 1
        }

        token == "--webui-port" -> {
          webUiPort =
              args.valueAfter(index, "--webui-port").toIntOrNull()
                  ?: usageError("Invalid --webui-port value")
          index += 2
        }

        token.startsWith("--webui-port=") -> {
          webUiPort =
              token.substringAfter('=').toIntOrNull() ?: usageError("Invalid --webui-port value")
          index += 1
        }

        token.startsWith("--") -> {
          val (key, value, consumed) = parseDynamicArg(args, index)
          dynamicArgs[key] = value
          index += consumed
        }

        messageRef == null -> {
          messageRef = token
          index += 1
        }

        else -> usageError("Unexpected token in send command: $token")
      }
    }

    if (webUiEnabled) {
      return SendConfig(
          messageRef = messageRef,
          schemaPath = schemaPath,
          host = host ?: "127.0.0.1",
          port = port ?: 9000,
          arguments = dynamicArgs,
          webUiEnabled = true,
          webUiPort = webUiPort,
      )
    }

    if (messageRef.isNullOrBlank()) {
      usageError(
          "send command needs message ref. Example: osc send light.color --host 127.0.0.1 --port 9000 --r 255",
      )
    }
    if (host.isNullOrBlank()) {
      usageError("send requires --host")
    }
    if (port == null) {
      usageError("send requires --port")
    }

    return SendConfig(
        messageRef = messageRef,
        schemaPath = schemaPath,
        host = host,
        port = port,
        arguments = dynamicArgs,
        webUiEnabled = false,
        webUiPort = webUiPort,
    )
  }

  /**
   * docコマンドの引数を解析し、[DocConfig]を返す。
   *
   * @param args docコマンドの引数リスト
   * @return パースされたドキュメント生成設定
   * @throws CliUsageException 不正なオプションが指定された場合
   */
  private fun parseDocCommand(args: List<String>): DocConfig {
    var schemaPath: String? = null
    var outputPath: String? = null
    var title: String? = null
    var format: DocFormat? = null

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

        token == "--out" -> {
          outputPath = args.valueAfter(index, "--out")
          index += 2
        }

        token.startsWith("--out=") -> {
          outputPath = token.substringAfter('=')
          index += 1
        }

        token == "--title" -> {
          title = args.valueAfter(index, "--title")
          index += 2
        }

        token.startsWith("--title=") -> {
          title = token.substringAfter('=')
          index += 1
        }

        token == "--format" -> {
          format = parseDocFormat(args.valueAfter(index, "--format"))
          index += 2
        }

        token.startsWith("--format=") -> {
          format = parseDocFormat(token.substringAfter('='))
          index += 1
        }

        token.startsWith("--") -> usageError("Unknown option for doc: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }

        else -> usageError("Unexpected token in doc command: $token")
      }
    }

    return DocConfig(
        schemaPath = schemaPath, outputPath = outputPath, title = title, format = format)
  }

  /**
   * ドキュメントフォーマット文字列を[DocFormat]列挙値にパースする。
   *
   * @param raw フォーマットを示す文字列（例: "html", "markdown", "md"）
   * @return 対応するDocFormat値
   * @throws CliUsageException サポートされていないフォーマットの場合
   */
  private fun parseDocFormat(raw: String): DocFormat {
    return when (raw.trim().lowercase()) {
      "html" -> DocFormat.HTML
      "markdown",
      "md" -> DocFormat.MARKDOWN
      else -> usageError("Unsupported --format: $raw (supported: html, markdown)")
    }
  }

  /**
   * 明示的な指定またはファイル拡張子から出力フォーマットを決定する。
   *
   * @param explicit 明示的に指定されたフォーマット（nullの場合は拡張子から推定）
   * @param outputPath 出力パス（拡張子からフォーマットを推定するために使用）
   * @return 決定されたDocFormat
   */
  private fun resolveDocFormat(explicit: DocFormat?, outputPath: String?): DocFormat {
    if (explicit != null) {
      return explicit
    }
    return detectFormatFromOutputPath(outputPath) ?: DocFormat.HTML
  }

  /**
   * ドキュメントの出力パスを解決する。
   *
   * 未指定の場合はフォーマットに応じたデフォルトパスを返す。
   *
   * @param raw 指定された出力パス文字列（nullの場合はデフォルトを使用）
   * @param format 出力フォーマット
   * @return 解決された出力パス
   * @throws CliUsageException 出力拡張子とフォーマット指定が不一致の場合
   */
  private fun resolveDocOutputPath(raw: String?, format: DocFormat): Path {
    val defaultFileName = if (format == DocFormat.HTML) "index.html" else "index.md"

    if (raw.isNullOrBlank()) {
      return Path.of("build", "docs", "osc-schema", defaultFileName).toAbsolutePath().normalize()
    }

    val normalized = Path.of(raw).toAbsolutePath().normalize()
    val inferred = detectFormatFromOutputPath(normalized.toString())
    if (inferred != null && inferred != format) {
      usageError("Output extension and --format mismatch: $normalized")
    }

    return if (inferred != null) {
      normalized
    } else {
      normalized.resolve(defaultFileName)
    }
  }

  /**
   * 出力パスのファイル拡張子からドキュメントフォーマットを推定する。
   *
   * @param raw 出力パス文字列（nullまたは空の場合はnullを返す）
   * @return 推定されたDocFormat、推定できない場合はnull
   */
  private fun detectFormatFromOutputPath(raw: String?): DocFormat? {
    if (raw.isNullOrBlank()) {
      return null
    }

    val lower = Path.of(raw).fileName.toString().lowercase()
    return when {
      lower.endsWith(".html") || lower.endsWith(".htm") -> DocFormat.HTML
      lower.endsWith(".md") || lower.endsWith(".markdown") -> DocFormat.MARKDOWN
      else -> null
    }
  }

  /**
   * コマンドライン引数から動的な名前付き引数をパースする。
   *
   * "--key=value"形式と"--key value"形式の両方をサポートする。
   *
   * @param args 引数リスト
   * @param index 現在の引数インデックス
   * @return キー、値、消費したトークン数のトリプル
   * @throws CliUsageException 不正なフラグ形式の場合
   */
  private fun parseDynamicArg(args: List<String>, index: Int): Triple<String, Any?, Int> {
    val token = args[index]
    if (token.contains('=')) {
      val key = token.substringAfter("--").substringBefore('=').trim()
      val value = token.substringAfter('=').trim()
      if (key.isBlank()) {
        usageError("Invalid argument flag: $token")
      }
      return Triple(key, parseDynamicValue(value), 1)
    }

    val key = token.substringAfter("--").trim()
    if (key.isBlank()) {
      usageError("Invalid argument flag: $token")
    }
    val value = args.valueAfter(index, token)
    return Triple(key, parseDynamicValue(value), 2)
  }

  /**
   * 動的引数の文字列値をパースする。
   *
   * @param raw パースする生の値文字列
   * @return パースされた値
   */
  private fun parseDynamicValue(raw: String): Any? {
    return CliDynamicValueParser.parse(raw)
  }

  /**
   * スキーマファイルパスを解決する。
   *
   * 明示パスが指定されていない場合は自動検出を行う。
   *
   * @param explicitPath 明示的に指定されたスキーマパス（nullの場合は自動検出）
   * @return 解決されたスキーマファイルのPath
   */
  private fun resolveSchemaPath(explicitPath: String?): Path =
      SchemaPathResolver.resolve(
          explicitPath, warn = { message -> err.println("warning: $message") })

  /**
   * CLIのバージョン文字列をリソースファイルから読み込む。
   *
   * @return バージョン文字列。読み込み失敗時は"unknown"
   */
  private fun loadCliVersion(): String {
    val props = Properties()
    try {
      CliAdapter::class
          .java
          .getResourceAsStream("/com/oscplatform/adapter/cli/version.properties")
          ?.use { props.load(it) }
    } catch (_: Exception) {}
    return props.getProperty("version", "unknown")
  }

  /**
   * 引数ノードの概要を表示用文字列にフォーマットする。
   *
   * @param arg フォーマット対象の引数ノード
   * @return 引数の概要文字列（例: "name:int", "items:array&lt;float&gt;[10]"）
   */
  private fun formatArgSummary(arg: com.oscplatform.core.schema.OscArgNode): String {
    return when (arg) {
      is com.oscplatform.core.schema.ScalarArgNode -> {
        val roleSuffix =
            if (arg.role == com.oscplatform.core.schema.ScalarRole.LENGTH) "[length]" else ""
        "${arg.name}:${arg.type.name.lowercase()}$roleSuffix"
      }
      is ArrayArgNode -> {
        val lengthSuffix =
            when (val length = arg.length) {
              is LengthSpec.Fixed -> "[${length.size}]"
              is LengthSpec.FromField -> "[from=${length.fieldName}]"
            }
        val itemSummary =
            when (val item = arg.item) {
              is ArrayItemSpec.ScalarItem -> item.type.name.lowercase()
              is ArrayItemSpec.TupleItem ->
                  "tuple(${item.fields.joinToString(",") { field -> "${field.name}:${field.type.name.lowercase()}" }})"
            }
        "${arg.name}:array<$itemSummary>$lengthSuffix"
      }
    }
  }

  /**
   * runコマンドの使い方行を返す。
   *
   * @return runコマンドの使い方文字列
   */
  private fun runUsageLine(): String =
      "osc run [schemaPath] [--schema path] [--host 0.0.0.0] [--port 9000] [--webui] [--webui-port 8080]"

  /**
   * sendコマンドの使い方行を返す。
   *
   * @return sendコマンドの使い方文字列
   */
  private fun sendUsageLine(): String =
      "osc send [messageRef] [--schema path] [--host <targetHost>] [--port <targetPort>] [--webui] [--webui-port 8080] --arg value"

  /**
   * docコマンドの使い方行を返す。
   *
   * @return docコマンドの使い方文字列
   */
  private fun docUsageLine(): String =
      "osc doc [schemaPath] [--schema path] [--out build/docs/osc-schema/index.html] [--format html|markdown] [--title \"OSC Schema\"]"

  /**
   * listコマンドの使い方行を返す。
   *
   * @return listコマンドの使い方文字列
   */
  private fun listUsageLine(): String = "osc list [schemaPath] [--schema path]"

  /**
   * validateコマンドの使い方行を返す。
   *
   * @return validateコマンドの使い方文字列
   */
  private fun validateUsageLine(): String = "osc validate [schemaPath] [--schema path]"

  /**
   * genコマンドの使い方行を返す。
   *
   * @return genコマンドの使い方文字列
   */
  private fun genUsageLine(): String =
      "osc gen [schemaPath] [--schema path] --package <packageName> [--lang kotlin] [--out build/generated/sources/osc]"

  /**
   * versionコマンドの使い方行を返す。
   *
   * @return versionコマンドの使い方文字列
   */
  private fun versionUsageLine(): String = "osc version"

  /** 全コマンドの使い方を標準出力に表示する。 */
  private fun printUsage() {
    out.println(usageText())
  }

  /**
   * 指定コマンドの使い方を標準出力に表示する。
   *
   * @param command 使い方を表示するコマンド名
   */
  private fun printCommandUsage(command: String) {
    out.println(commandUsageText(command))
  }
}

/**
 * runコマンドの設定を保持するデータクラス。
 *
 * @param schemaPath スキーマファイルのパス
 * @param host バインドするホストアドレス
 * @param port バインドするポート番号
 * @param webUiEnabled WebUIを有効にするかどうか
 * @param webUiPort WebUIのHTTPポート番号
 */
private data class RunConfig(
    val schemaPath: String?,
    val host: String,
    val port: Int,
    val webUiEnabled: Boolean,
    val webUiPort: Int,
)

/**
 * sendコマンドの設定を保持するデータクラス。
 *
 * @param messageRef 送信対象のメッセージ参照
 * @param schemaPath スキーマファイルのパス
 * @param host 送信先ホストアドレス
 * @param port 送信先ポート番号
 * @param arguments メッセージに付与する動的引数
 * @param webUiEnabled WebUIを有効にするかどうか
 * @param webUiPort WebUIのHTTPポート番号
 */
private data class SendConfig(
    val messageRef: String?,
    val schemaPath: String?,
    val host: String,
    val port: Int,
    val arguments: Map<String, Any?>,
    val webUiEnabled: Boolean,
    val webUiPort: Int,
)

/**
 * docコマンドの設定を保持するデータクラス。
 *
 * @param schemaPath スキーマファイルのパス
 * @param outputPath 出力先ファイルパス
 * @param title ドキュメントタイトル
 * @param format 出力フォーマット
 */
private data class DocConfig(
    val schemaPath: String?,
    val outputPath: String?,
    val title: String?,
    val format: DocFormat?,
)

/**
 * genコマンドの設定を保持するデータクラス。
 *
 * @param schemaPath スキーマファイルのパス
 * @param packageName 生成コードのパッケージ名
 * @param lang 生成対象の言語
 * @param outputPath 出力先ディレクトリパス
 */
private data class GenConfig(
    val schemaPath: String?,
    val packageName: String?,
    val lang: String,
    val outputPath: String?,
)

/**
 * listおよびvalidateコマンドの設定を保持するデータクラス。
 *
 * @param schemaPath スキーマファイルのパス
 */
private data class SchemaLookupConfig(
    val schemaPath: String?,
)

/** ドキュメント出力フォーマットを表す列挙型。 */
private enum class DocFormat {
  /** HTML形式 */
  HTML,

  /** Markdown形式 */
  MARKDOWN,
}

/**
 * 指定インデックスの次の要素を取得する拡張関数。
 *
 * @param index 現在のインデックス
 * @param option オプション名（エラーメッセージに使用）
 * @return 次のインデックスの要素
 * @throws CliUsageException 次の要素が存在しない場合
 */
private fun List<String>.valueAfter(index: Int, option: String): String {
  if (index + 1 >= size) {
    usageError("$option requires a value")
  }
  return this[index + 1]
}

/**
 * リストがヘルプリクエストであるかを判定する拡張関数。
 *
 * 要素が1つだけで、かつヘルプフラグに含まれる場合にtrueを返す。
 *
 * @param helpFlags ヘルプフラグのセット
 * @return ヘルプリクエストの場合true
 */
private fun List<String>.isHelpRequest(helpFlags: Set<String>): Boolean =
    size == 1 && first() in helpFlags

/**
 * CLI使用方法エラーをスローするヘルパー関数。
 *
 * @param message エラーメッセージ
 * @return この関数は常にNothingを返す（例外をスローする）
 * @throws CliUsageException 常にスローされる
 */
private fun usageError(message: String): Nothing = throw CliUsageException(message)

/**
 * CLIコマンドの使い方に関するエラーを表す例外。
 *
 * @param message エラーメッセージ
 */
private class CliUsageException(message: String) : IllegalArgumentException(message)
