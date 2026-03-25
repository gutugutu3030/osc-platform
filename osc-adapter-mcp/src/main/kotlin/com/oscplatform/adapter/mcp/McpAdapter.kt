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

class McpAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  private companion object {
    val helpFlags = setOf("help", "-h", "--help")
  }

  private val schemaLoader: SchemaLoader = SchemaLoader()

  fun commandSummary(): String =
      "osc mcp [schemaPath] [--schema path] --host <targetHost> --port <targetPort> [--webui] [--webui-port 8080]"

  fun usageText(): String = commandSummary()

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
              token.substringAfter('=').toIntOrNull()
                  ?: mcpUsageError("Invalid --webui-port value")
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

  private fun resolveSchemaPath(explicitPath: String?): Path =
      SchemaPathResolver.resolve(
          explicitPath, warn = { message -> err.println("warning: $message") })

  private fun printUsage() {
    out.println(usageText())
  }
}

private data class McpConfig(
    val schemaPath: String?,
    val host: String,
    val port: Int,
  val webUiEnabled: Boolean,
  val webUiPort: Int,
)

private class OscMcpServer(
    private val protocol: McpStdioProtocol,
    private val runtime: OscRuntime,
    private val toolByName: Map<String, OscMessageSpec>,
    private val bundleToolByName: Map<String, McpBundleTool> = emptyMap(),
    private val target: OscTarget,
  private val webUiEventSink: MutableSharedFlow<WebUiLogEvent>? = null,
) {
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

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

  private fun toInputSchema(spec: OscMessageSpec): ObjectNode {
    return McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)
  }

  private fun resultResponse(id: JsonNode, result: ObjectNode): ObjectNode {
    return mapper.createObjectNode().apply {
      put("jsonrpc", "2.0")
      set("id", id)
      set("result", result)
    }
  }

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

  private fun jsonNodeToValue(node: JsonNode): Any? {
    return McpSchemaJsonSupport.jsonNodeToValue(node)
  }
}

internal data class McpBundleTool(
    val spec: OscBundleSpec,
    val resolvedSpecs: List<OscMessageSpec>,
    val inputSchema: ObjectNode,
)

internal object McpSchemaJsonSupport {
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

private class McpStdioProtocol(
    input: InputStream,
    private val output: OutputStream,
) {
  private val input = BufferedInputStream(input)
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

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

  fun writeMessage(node: ObjectNode) {
    val payload = mapper.writeValueAsBytes(node)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    output.write(header)
    output.write(payload)
    output.flush()
  }

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

private fun List<Byte>.toAsciiString(): String {
  val array = ByteArray(size)
  for (index in indices) {
    array[index] = this[index]
  }
  return array.toString(StandardCharsets.US_ASCII)
}

private fun List<String>.valueAfter(index: Int, option: String): String {
  if (index + 1 >= size) {
    mcpUsageError("$option requires a value")
  }
  return this[index + 1]
}

private fun List<String>.isHelpRequest(helpFlags: Set<String>): Boolean =
    size == 1 && first() in helpFlags

private fun mcpUsageError(message: String): Nothing = throw McpUsageException(message)

private class McpUsageException(message: String) : IllegalArgumentException(message)
