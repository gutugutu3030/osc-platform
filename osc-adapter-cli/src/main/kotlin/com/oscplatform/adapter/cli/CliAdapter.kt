package com.oscplatform.adapter.cli

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

class CliAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  private companion object {
    val helpFlags = setOf("help", "-h", "--help")
  }

  private val schemaLoader: SchemaLoader = SchemaLoader()

  fun commandNames(): Set<String> =
      setOf("run", "send", "doc", "list", "validate", "gen", "version")

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

  fun usageText(): String = buildString {
    commandSummaries().forEach { appendLine(it) }
    appendLine()
    append(
        "messageRef accepts schema message name (e.g. light.color) or OSC path (e.g. /light/color).",
    )
  }

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
    if (parsed.packageName.isNullOrBlank()) {
      usageError(
          "gen requires --package. Example: osc gen --schema schema.yaml --package com.example.generated --lang kotlin --out build/generated/sources/osc",
      )
    }
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)
    val options = CodeGenOptions(packageName = parsed.packageName!!, language = parsed.lang)
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

  private fun validateSchema(args: List<String>): Int {
    val parsed = parseSchemaLookupCommand(command = "validate", args = args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    out.println("schema valid: $schemaPath")
    out.println("messages: ${schema.messages.size}")
    out.println("bundles: ${schema.bundles.size}")
    return 0
  }

  private fun printVersion(args: List<String>): Int {
    if (args.isNotEmpty()) {
      usageError("Unexpected token in version command: ${args.first()}")
    }
    out.println("osc-platform ${loadCliVersion()}")
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
          port =
              args.valueAfter(index, "--port").toIntOrNull() ?: usageError("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: usageError("Invalid --port value")
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

    return RunConfig(schemaPath = schemaPath, host = host, port = port)
  }

  private fun parseSendCommand(args: List<String>): SendConfig {
    if (args.isEmpty()) {
      usageError(
          "send command needs message ref. Example: osc send light.color --host 127.0.0.1 --port 9000 --r 255",
      )
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
          port =
              args.valueAfter(index, "--port").toIntOrNull() ?: usageError("Invalid --port value")
          index += 2
        }

        token.startsWith("--port=") -> {
          port = token.substringAfter('=').toIntOrNull() ?: usageError("Invalid --port value")
          index += 1
        }

        token.startsWith("--") -> {
          val (key, value, consumed) = parseDynamicArg(args, index)
          dynamicArgs[key] = value
          index += consumed
        }

        else -> usageError("Unexpected token in send command: $token")
      }
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

  private fun parseDocFormat(raw: String): DocFormat {
    return when (raw.trim().lowercase()) {
      "html" -> DocFormat.HTML
      "markdown",
      "md" -> DocFormat.MARKDOWN
      else -> usageError("Unsupported --format: $raw (supported: html, markdown)")
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
      usageError("Output extension and --format mismatch: $normalized")
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

  private fun parseDynamicValue(raw: String): Any? {
    return CliDynamicValueParser.parse(raw)
  }

  private fun resolveSchemaPath(explicitPath: String?): Path =
      SchemaPathResolver.resolve(
          explicitPath, warn = { message -> err.println("warning: $message") })

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

  private fun runUsageLine(): String =
      "osc run [schemaPath] [--schema path] [--host 0.0.0.0] [--port 9000]"

  private fun sendUsageLine(): String =
      "osc send <messageRef> [--schema path] --host <targetHost> --port <targetPort> --arg value"

  private fun docUsageLine(): String =
      "osc doc [schemaPath] [--schema path] [--out build/docs/osc-schema/index.html] [--format html|markdown] [--title \"OSC Schema\"]"

  private fun listUsageLine(): String = "osc list [schemaPath] [--schema path]"

  private fun validateUsageLine(): String = "osc validate [schemaPath] [--schema path]"

  private fun genUsageLine(): String =
      "osc gen [schemaPath] [--schema path] --package <packageName> [--lang kotlin] [--out build/generated/sources/osc]"

  private fun versionUsageLine(): String = "osc version"

  private fun printUsage() {
    out.println(usageText())
  }

  private fun printCommandUsage(command: String) {
    out.println(commandUsageText(command))
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

private data class SchemaLookupConfig(
    val schemaPath: String?,
)

private enum class DocFormat {
  HTML,
  MARKDOWN,
}

private fun List<String>.valueAfter(index: Int, option: String): String {
  if (index + 1 >= size) {
    usageError("$option requires a value")
  }
  return this[index + 1]
}

private fun List<String>.isHelpRequest(helpFlags: Set<String>): Boolean =
    size == 1 && first() in helpFlags

private fun usageError(message: String): Nothing = throw CliUsageException(message)

private class CliUsageException(message: String) : IllegalArgumentException(message)
