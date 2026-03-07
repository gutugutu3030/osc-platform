package com.oscplatform.adapter.mcp

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
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

class McpAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  private val schemaLoader: SchemaLoader = SchemaLoader()

  suspend fun execute(args: List<String>): Int =
      execute(args, System.`in`, System.out, UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0))

  internal suspend fun execute(
      args: List<String>,
      input: InputStream,
      output: OutputStream,
      transport: OscTransport,
  ): Int {
    val parsed = parseArgs(args)
    val schemaPath = resolveSchemaPath(parsed.schemaPath)
    val schema = schemaLoader.load(schemaPath)

    val runtime = OscRuntime(schema = schema, transport = transport)

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
        )

    err.println("MCP server started schema=$schemaPath target=${parsed.host}:${parsed.port}")
    server.run()
    return 0
  }

  private fun parseArgs(args: List<String>): McpConfig {
    var schemaPath: String? = null
    var host: String? = null
    var port: Int? = null

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

        token.startsWith("--") -> error("Unknown mcp option: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }

        else -> error("Unexpected token in mcp command: $token")
      }
    }

    require(!host.isNullOrBlank()) { "mcp requires --host" }
    require(port != null) { "mcp requires --port" }

    return McpConfig(schemaPath = schemaPath, host = host, port = port)
  }

  private fun resolveSchemaPath(explicitPath: String?): Path =
      SchemaPathResolver.resolve(
          explicitPath, warn = { message -> err.println("warning: $message") })
}

private data class McpConfig(
    val schemaPath: String?,
    val host: String,
    val port: Int,
)

private class OscMcpServer(
    private val protocol: McpStdioProtocol,
    private val runtime: OscRuntime,
    private val toolByName: Map<String, OscMessageSpec>,
    private val bundleToolByName: Map<String, McpBundleTool> = emptyMap(),
    private val target: OscTarget,
) {
  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  suspend fun run() {
    while (true) {
      val messageBytes = protocol.readMessage() ?: break
      val root = mapper.readTree(messageBytes)
      val method = root.path("method").asText("")
      val id = root.get("id")

      when (method) {
        "initialize" -> {
          if (id != null) {
            protocol.writeMessage(resultResponse(id, initializeResult()))
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
      val name = params.path("name").asText("")
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
      protocol.writeMessage(resultResponse(id, result))
    } catch (ex: Exception) {
      protocol.writeMessage(errorResponse(id, -32000, ex.message ?: "Tool call failed"))
    }
  }

  private fun initializeResult(): ObjectNode {
    return mapper.createObjectNode().apply {
      put("protocolVersion", "2024-11-05")
      set(
          "capabilities",
          mapper.createObjectNode().apply { set("tools", mapper.createObjectNode()) })
      set(
          "serverInfo",
          mapper.createObjectNode().apply {
            put("name", "osc-platform")
            put("version", "0.2.0")
          })
    }
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
      specSchema.path("required").forEach { node -> required.add(node.asText()) }
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
      node.isTextual -> node.asText()
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

      if (current == '\r'.code) {
        val next = input.read()
        require(next == '\n'.code) { "Invalid stdio frame header line ending" }
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
  require(index + 1 < size) { "$option requires a value" }
  return this[index + 1]
}
