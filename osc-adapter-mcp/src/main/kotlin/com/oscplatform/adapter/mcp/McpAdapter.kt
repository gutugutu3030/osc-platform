package com.oscplatform.adapter.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class McpAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    private val schemaLoader: SchemaLoader = SchemaLoader()

    suspend fun execute(args: List<String>): Int {
        val parsed = parseArgs(args)
        val schemaPath = resolveSchemaPath(parsed.schemaPath)
        val schema = schemaLoader.load(schemaPath)

        val transport = UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0)
        val runtime = OscRuntime(schema = schema, transport = transport)

        val toolByName = schema.messages.associateBy { OscNaming.mcpToolName(it.path) }
        val protocol = McpStdioProtocol(input = System.`in`, output = System.out)
        val server = OscMcpServer(
            protocol = protocol,
            runtime = runtime,
            toolByName = toolByName,
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
                    port = args.valueAfter(index, "--port").toIntOrNull()
                        ?: error("Invalid --port value")
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

    private fun resolveSchemaPath(explicitPath: String?): Path {
        if (!explicitPath.isNullOrBlank()) {
            val resolved = Path.of(explicitPath).toAbsolutePath().normalize()
            require(resolved.exists() && resolved.isRegularFile()) { "Schema not found: $resolved" }
            return resolved
        }

        val cwd = Path.of("").toAbsolutePath().normalize()
        val priority = listOf("schema.kts", "schema.yaml", "schema.yml")
        priority.forEach { fileName ->
            val candidate = cwd.resolve(fileName)
            if (candidate.exists() && candidate.isRegularFile()) {
                return candidate
            }
        }

        Files.list(cwd).use { stream ->
            val fallback = stream
                .filter { Files.isRegularFile(it) }
                .filter { path ->
                    val lower = path.fileName.toString().lowercase()
                    val schemaLike = lower.startsWith("schema")
                    val allowedExt = lower.endsWith(".kts") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                    schemaLike && allowedExt
                }
                .sorted()
                .findFirst()

            if (fallback.isPresent) {
                return fallback.get()
            }
        }

        error("Schema not found in current directory. Use --schema <path> or add schema.kts/schema.yaml")
    }
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
    private val target: OscTarget,
) {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

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

            val spec = toolByName[name] ?: error("Unknown tool: $name")
            val argsNode = params.path("arguments")

            val argMap = linkedMapOf<String, Any?>()
            if (argsNode.isObject) {
                argsNode.fields().forEach { (argName, node) ->
                    argMap[argName] = jsonNodeToValue(node)
                }
            }

            runtime.send(
                messageRef = spec.name,
                rawArgs = argMap,
                target = target,
            )

            val result = mapper.createObjectNode().apply {
                val content = mapper.createArrayNode()
                content.add(mapper.createObjectNode().apply {
                    put("type", "text")
                    put("text", "sent ${spec.path} to ${target.host}:${target.port}")
                })
                set<ArrayNode>("content", content)
            }
            protocol.writeMessage(resultResponse(id, result))
        } catch (ex: Exception) {
            protocol.writeMessage(errorResponse(id, -32000, ex.message ?: "Tool call failed"))
        }
    }

    private fun initializeResult(): ObjectNode {
        return mapper.createObjectNode().apply {
            put("protocolVersion", "2024-11-05")
            set<ObjectNode>("capabilities", mapper.createObjectNode().apply {
                set<ObjectNode>("tools", mapper.createObjectNode())
            })
            set<ObjectNode>("serverInfo", mapper.createObjectNode().apply {
                put("name", "osc-platform")
                put("version", "0.1.0")
            })
        }
    }

    private fun toolsListResult(): ObjectNode {
        return mapper.createObjectNode().apply {
            val toolsNode = mapper.createArrayNode()
            toolByName.forEach { (toolName, spec) ->
                toolsNode.add(mapper.createObjectNode().apply {
                    put("name", toolName)
                    put("description", spec.description ?: "Send OSC message to ${spec.path}")
                    set<ObjectNode>("inputSchema", toInputSchema(spec))
                })
            }
            set<ArrayNode>("tools", toolsNode)
        }
    }

    private fun toInputSchema(spec: OscMessageSpec): ObjectNode {
        val properties = mapper.createObjectNode()
        val required = mapper.createArrayNode()
        val autoDerivableLengthFields = spec.args
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
            properties.set<ObjectNode>(arg.name, toJsonSchemaForArg(arg))

            val isOptionalDerivedLength = arg is ScalarArgNode &&
                arg.role == ScalarRole.LENGTH &&
                autoDerivableLengthFields.contains(arg.name)
            if (!isOptionalDerivedLength) {
                required.add(arg.name)
            }
        }

        return mapper.createObjectNode().apply {
            put("type", "object")
            set<ObjectNode>("properties", properties)
            set<ArrayNode>("required", required)
            put("additionalProperties", false)
        }
    }

    private fun toJsonSchemaForArg(arg: OscArgNode): ObjectNode {
        return when (arg) {
            is ScalarArgNode -> jsonScalarSchema(arg.type).apply {
                if (arg.role == ScalarRole.LENGTH) {
                    put("description", "length field")
                    put("minimum", 0)
                }
            }

            is ArrayArgNode -> mapper.createObjectNode().apply {
                put("type", "array")
                set<ObjectNode>("items", toJsonSchemaForArrayItem(arg.item))
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

    private fun toJsonSchemaForArrayItem(item: ArrayItemSpec): ObjectNode {
        return when (item) {
            is ArrayItemSpec.ScalarItem -> jsonScalarSchema(item.type)
            is ArrayItemSpec.TupleItem -> {
                val properties = mapper.createObjectNode()
                val required = mapper.createArrayNode()
                item.fields.forEach { field ->
                    properties.set<ObjectNode>(field.name, jsonScalarSchema(field.type))
                    required.add(field.name)
                }
                mapper.createObjectNode().apply {
                    put("type", "object")
                    set<ObjectNode>("properties", properties)
                    set<ArrayNode>("required", required)
                    put("additionalProperties", false)
                }
            }
        }
    }

    private fun jsonScalarSchema(type: OscType): ObjectNode {
        val typeString = when (type) {
            OscType.INT -> "integer"
            OscType.FLOAT -> "number"
            OscType.STRING -> "string"
        }
        return mapper.createObjectNode().apply { put("type", typeString) }
    }

    private fun resultResponse(id: JsonNode, result: ObjectNode): ObjectNode {
        return mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            set<JsonNode>("id", id)
            set<ObjectNode>("result", result)
        }
    }

    private fun errorResponse(id: JsonNode, code: Int, message: String): ObjectNode {
        return mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            set<JsonNode>("id", id)
            set<ObjectNode>("error", mapper.createObjectNode().apply {
                put("code", code)
                put("message", message)
            })
        }
    }

    private fun jsonNodeToValue(node: JsonNode): Any? {
        return when {
            node.isTextual -> node.asText()
            node.isInt -> node.intValue()
            node.isLong -> node.longValue()
            node.isFloat || node.isDouble || node.isBigDecimal -> node.doubleValue()
            node.isBoolean -> node.booleanValue()
            node.isArray -> node.map { child -> jsonNodeToValue(child) }
            node.isObject -> linkedMapOf<String, Any?>().also { map ->
                node.fields().forEach { (key, value) ->
                    map[key] = jsonNodeToValue(value)
                }
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
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

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
