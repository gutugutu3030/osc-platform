package com.oscplatform.adapter.cli

import com.oscplatform.codegen.CodeGenOptions
import com.oscplatform.codegen.KotlinCodeGenerator
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.schema.loader.SchemaPathResolver
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CliAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  private val schemaLoader: SchemaLoader = SchemaLoader()

  suspend fun execute(args: List<String>): Int {
    if (args.isEmpty()) {
      printUsage()
      return 1
    }

    return when (args.first()) {
      "run" -> runServer(args.drop(1))
      "send" -> sendMessage(args.drop(1))
      "doc" -> generateDoc(args.drop(1))
      "gen" -> generateCode(args.drop(1))
      "help",
      "-h",
      "--help" -> {
        printUsage()
        0
      }

      else -> {
        err.println("Unknown command: ${args.first()}")
        printUsage()
        1
      }
    }
  }

  private suspend fun runServer(args: List<String>): Int {
    val parsed = parseRunCommand(args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    val transport = UdpOscTransport(bindHost = parsed.host, bindPort = parsed.port)
    val runtime = OscRuntime(schema = schema, transport = transport)

    runtime.start()
    out.println("OSC runtime started")
    out.println("schema: $schemaPath")
    out.println("bind: ${parsed.host}:${parsed.port}")

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
            }
          }
        }

    Runtime.getRuntime()
        .addShutdownHook(
            Thread { runBlocking { runtime.stop() } },
        )

    try {
      awaitCancellation()
    } finally {
      eventJob.cancelAndJoin()
      runtime.stop()
    }
  }

  private suspend fun sendMessage(args: List<String>): Int {
    val parsed = parseSendCommand(args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    val transport = UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0)
    val runtime = OscRuntime(schema = schema, transport = transport)
    runtime.send(
        messageRef = parsed.messageRef,
        rawArgs = parsed.arguments,
        target = OscTarget(host = parsed.host, port = parsed.port),
    )

    out.println(
        "sent ${parsed.messageRef} -> ${parsed.host}:${parsed.port} args=${parsed.arguments}",
    )
    return 0
  }

  private fun generateCode(args: List<String>): Int {
    val parsed = parseGenCommand(args)
    require(!parsed.packageName.isNullOrBlank()) {
      "gen requires --package." +
          " Example: osc gen --schema schema.yaml --package com.example.generated" +
          " --lang kotlin --out build/generated/sources/osc"
    }
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)
    val options = CodeGenOptions(packageName = parsed.packageName!!, language = parsed.lang)
    val files =
        when (options.language) {
          "kotlin" -> KotlinCodeGenerator().generate(schema, options)
          else -> error("Unsupported --lang: ${options.language}. Supported: kotlin")
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
        token.startsWith("--") -> error("Unknown option for gen: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }
        else -> error("Unexpected token in gen command: $token")
      }
    }
    return GenConfig(
        schemaPath = schemaPath,
        packageName = packageName,
        lang = lang,
        outputPath = outputPath,
    )
  }

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

  private fun parseRunCommand(args: List<String>): RunConfig {
    var schemaPath: String? = null
    var host = "0.0.0.0"
    var port = 9000

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
          port = args.valueAfter(index, "--port").toIntOrNull() ?: error("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: error("Invalid --port value")
          index += 1
        }

        token.startsWith("--") -> error("Unknown option for run: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }

        else -> error("Unexpected token in run command: $token")
      }
    }

    return RunConfig(schemaPath = schemaPath, host = host, port = port)
  }

  private fun parseSendCommand(args: List<String>): SendConfig {
    require(args.isNotEmpty()) {
      "send command needs message ref. Example: osc send light.color --host 127.0.0.1 --port 9000 --r 255"
    }

    val messageRef = args.first()
    var schemaPath: String? = null
    var host: String? = null
    var port: Int? = null
    val dynamicArgs = linkedMapOf<String, Any?>()

    var index = 1
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
          port = args.valueAfter(index, "--port").toIntOrNull() ?: error("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: error("Invalid --port value")
          index += 1
        }

        token.startsWith("--") -> {
          val (key, value, consumed) = parseDynamicArg(args, index)
          dynamicArgs[key] = value
          index += consumed
        }

        else -> error("Unexpected token in send command: $token")
      }
    }

    require(!host.isNullOrBlank()) { "send requires --host" }
    require(port != null) { "send requires --port" }

    return SendConfig(
        messageRef = messageRef,
        schemaPath = schemaPath,
        host = host,
        port = port,
        arguments = dynamicArgs,
    )
  }

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

        token.startsWith("--") -> error("Unknown option for doc: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }

        else -> error("Unexpected token in doc command: $token")
      }
    }

    return DocConfig(
        schemaPath = schemaPath, outputPath = outputPath, title = title, format = format)
  }

  private fun parseDocFormat(raw: String): DocFormat {
    return when (raw.trim().lowercase()) {
      "html" -> DocFormat.HTML
      "markdown",
      "md" -> DocFormat.MARKDOWN
      else -> error("Unsupported --format: $raw (supported: html, markdown)")
    }
  }

  private fun resolveDocFormat(explicit: DocFormat?, outputPath: String?): DocFormat {
    if (explicit != null) {
      return explicit
    }
    return detectFormatFromOutputPath(outputPath) ?: DocFormat.HTML
  }

  private fun resolveDocOutputPath(raw: String?, format: DocFormat): Path {
    val defaultFileName = if (format == DocFormat.HTML) "index.html" else "index.md"

    if (raw.isNullOrBlank()) {
      return Path.of("build", "docs", "osc-schema", defaultFileName).toAbsolutePath().normalize()
    }

    val normalized = Path.of(raw).toAbsolutePath().normalize()
    val inferred = detectFormatFromOutputPath(normalized.toString())
    if (inferred != null && inferred != format) {
      error("Output extension and --format mismatch: $normalized")
    }

    return if (inferred != null) {
      normalized
    } else {
      normalized.resolve(defaultFileName)
    }
  }

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

  private fun parseDynamicArg(args: List<String>, index: Int): Triple<String, Any?, Int> {
    val token = args[index]
    if (token.contains('=')) {
      val key = token.substringAfter("--").substringBefore('=').trim()
      val value = token.substringAfter('=').trim()
      require(key.isNotBlank()) { "Invalid argument flag: $token" }
      return Triple(key, parseDynamicValue(value), 1)
    }

    val key = token.substringAfter("--").trim()
    require(key.isNotBlank()) { "Invalid argument flag: $token" }
    val value = args.valueAfter(index, token)
    return Triple(key, parseDynamicValue(value), 2)
  }

  private fun parseDynamicValue(raw: String): Any? {
    return CliDynamicValueParser.parse(raw)
  }

  private fun resolveSchemaPath(explicitPath: String?): Path =
      SchemaPathResolver.resolve(
          explicitPath, warn = { message -> err.println("warning: $message") })

  private fun printUsage() {
    out.println(
        """
            osc run [schemaPath] [--schema path] [--host 0.0.0.0] [--port 9000]
            osc send <messageRef> [--schema path] --host <targetHost> --port <targetPort> --arg value
            osc doc [schemaPath] [--schema path] [--out build/docs/osc-schema/index.html] [--format html|markdown] [--title "OSC Schema"]
            osc gen [schemaPath] [--schema path] --package <packageName> [--lang kotlin] [--out build/generated/sources/osc]

            messageRef accepts schema message name (e.g. light.color) or OSC path (e.g. /light/color).
        """
            .trimIndent())
  }
}

private data class RunConfig(
    val schemaPath: String?,
    val host: String,
    val port: Int,
)

private data class SendConfig(
    val messageRef: String,
    val schemaPath: String?,
    val host: String,
    val port: Int,
    val arguments: Map<String, Any?>,
)

private data class DocConfig(
    val schemaPath: String?,
    val outputPath: String?,
    val title: String?,
    val format: DocFormat?,
)

private data class GenConfig(
    val schemaPath: String?,
    val packageName: String?,
    val lang: String,
    val outputPath: String?,
)

private enum class DocFormat {
  HTML,
  MARKDOWN,
}

private fun List<String>.valueAfter(index: Int, option: String): String {
  require(index + 1 < size) { "$option requires a value" }
  return this[index + 1]
}
