package com.oscplatform.adapter.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class CliAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    private val schemaLoader: SchemaLoader = SchemaLoader()
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    suspend fun execute(args: List<String>): Int {
        if (args.isEmpty()) {
            printUsage()
            return 1
        }

        return when (args.first()) {
            "run" -> runServer(args.drop(1))
            "send" -> sendMessage(args.drop(1))
            "help", "-h", "--help" -> {
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

        val eventJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runtime.events.collect { event ->
                when (event) {
                    is OscRuntimeEvent.Received -> {
                        out.println("recv ${event.spec.path} ${event.namedArgs}")
                    }

                    is OscRuntimeEvent.ValidationError -> {
                        err.println("validation_error ${event.address ?: "-"}: ${event.reason}")
                    }
                }
            }
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    runtime.stop()
                }
            },
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
                    port = args.valueAfter(index, "--port").toIntOrNull()
                        ?: error("Invalid --port value")
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
                    port = args.valueAfter(index, "--port").toIntOrNull()
                        ?: error("Invalid --port value")
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

        require(!host.isNullOrBlank()) {
            "send requires --host"
        }
        require(port != null) {
            "send requires --port"
        }

        return SendConfig(
            messageRef = messageRef,
            schemaPath = schemaPath,
            host = host,
            port = port,
            arguments = dynamicArgs,
        )
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
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val node = try {
                mapper.readTree(trimmed)
            } catch (ex: Exception) {
                throw IllegalArgumentException("Invalid JSON argument value: $raw")
            }
            return jsonNodeToValue(node)
        }
        return raw
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

    private fun printUsage() {
        out.println("""
            osc run [schemaPath] [--schema path] [--host 0.0.0.0] [--port 9000]
            osc send <messageRef> [--schema path] --host <targetHost> --port <targetPort> --arg value

            messageRef accepts schema message name (e.g. light.color) or OSC path (e.g. /light/color).
        """.trimIndent())
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

private fun List<String>.valueAfter(index: Int, option: String): String {
    require(index + 1 < size) { "$option requires a value" }
    return this[index + 1]
}
