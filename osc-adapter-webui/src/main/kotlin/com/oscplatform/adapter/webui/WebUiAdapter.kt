package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.schema.loader.SchemaPathResolver
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

class WebUiAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  private val schemaLoader = SchemaLoader()

  fun commandSummary(): String =
      "osc webui [schemaPath] [--schema path] [--port 8080] [--osc-host 0.0.0.0] [--osc-port 9000]"

  suspend fun execute(args: List<String>): Int {
    if (args.firstOrNull() in setOf("help", "-h", "--help")) {
      out.println(commandSummary())
      return 0
    }

    return try {
      val config = parseArgs(args)
      val schemaPath =
          SchemaPathResolver.resolve(
              config.schemaPath, warn = { message -> err.println("warning: $message") })
      val schema = schemaLoader.load(schemaPath)

      val transport = UdpOscTransport(bindHost = config.oscHost, bindPort = config.oscPort)
      val runtime = OscRuntime(schema = schema, transport = transport)
      runtime.start()

      val server = WebUiServer(schema = schema, runtime = runtime, port = config.httpPort)
      server.start()

      out.println("OSC Web UI started")
      out.println("schema:     $schemaPath")
      out.println("OSC listen: ${config.oscHost}:${config.oscPort}")
      out.println("Web UI:     http://localhost:${config.httpPort}")

      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                runBlocking {
                  server.stop()
                  runtime.stop()
                }
              })

      try {
        awaitCancellation()
      } finally {
        server.stop()
        runtime.stop()
      }

      0
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: WebUiUsageException) {
      err.println("error: ${ex.message}")
      out.println(commandSummary())
      1
    } catch (ex: Exception) {
      err.println("error: ${ex.message ?: "Unexpected error"}")
      1
    }
  }

  private fun parseArgs(args: List<String>): WebUiConfig {
    var schemaPath: String? = null
    var httpPort = 8080
    var oscHost = "0.0.0.0"
    var oscPort = 9000

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
        token == "--port" -> {
          httpPort =
              args.valueAfter(index, "--port").toIntOrNull()
                  ?: webUiUsageError("Invalid --port value")
          index += 2
        }
        token.startsWith("--port=") -> {
          httpPort =
              token.substringAfter('=').toIntOrNull() ?: webUiUsageError("Invalid --port value")
          index += 1
        }
        token == "--osc-host" -> {
          oscHost = args.valueAfter(index, "--osc-host")
          index += 2
        }
        token.startsWith("--osc-host=") -> {
          oscHost = token.substringAfter('=')
          index += 1
        }
        token == "--osc-port" -> {
          oscPort =
              args.valueAfter(index, "--osc-port").toIntOrNull()
                  ?: webUiUsageError("Invalid --osc-port value")
          index += 2
        }
        token.startsWith("--osc-port=") -> {
          oscPort =
              token.substringAfter('=').toIntOrNull() ?: webUiUsageError("Invalid --osc-port value")
          index += 1
        }
        token.startsWith("--") -> webUiUsageError("Unknown option for webui: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }
        else -> webUiUsageError("Unexpected token in webui command: $token")
      }
    }

    return WebUiConfig(
        schemaPath = schemaPath,
        httpPort = httpPort,
        oscHost = oscHost,
        oscPort = oscPort,
    )
  }

  private fun List<String>.valueAfter(index: Int, option: String): String {
    if (index + 1 >= size) webUiUsageError("$option requires a value")
    return this[index + 1]
  }
}

private data class WebUiConfig(
    val schemaPath: String?,
    val httpPort: Int,
    val oscHost: String,
    val oscPort: Int,
)

private fun webUiUsageError(message: String): Nothing = throw WebUiUsageException(message)

private class WebUiUsageException(message: String) : IllegalArgumentException(message)
