package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class McpAdapterExecuteTest {

  @Test
  fun executeHelpReturnsZeroAndPrintsUsage() = runBlocking {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        McpAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode = adapter.execute(listOf("--help"))

    assertEquals(0, exitCode)
    assertTrue(outBuffer.toString().contains("osc mcp"))
    assertTrue(outBuffer.toString().contains("--webui"))
    assertEquals("", errBuffer.toString())
  }

  @Test
  fun executeWithWebUiAndImmediateEofReturnsZero() = runBlocking {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val schemaPath = resolveSchemaPathForTest()
    val webUiPort = allocatePort()
    val adapter =
        McpAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode =
        adapter.execute(
            args =
                listOf(
                    "--schema",
                    schemaPath,
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "9000",
                    "--webui",
                    "--webui-port",
                    webUiPort.toString(),
                ),
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            transport = NoopTransport(),
        )

    assertEquals(0, exitCode)
    assertTrue(errBuffer.toString().contains("Web UI: http://localhost:$webUiPort"))
  }

  @Test
  fun executeWithUnknownOptionReturnsOneAndPrintsFormattedError() = runBlocking {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        McpAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode =
        adapter.execute(
            args = listOf("--bogus"),
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            transport = NoopTransport(),
        )

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("error: Unknown mcp option: --bogus"))
    assertTrue(outBuffer.toString().contains("osc mcp"))
  }
}

private fun resolveSchemaPathForTest(): String {
  val candidates = listOf(Path.of("schema.yaml"), Path.of("..", "schema.yaml"))
  return candidates.firstOrNull { Files.exists(it) }?.normalize()?.toString()
      ?: error("schema.yaml not found for test")
}

private fun allocatePort(): Int = ServerSocket(0).use { it.localPort }

private class NoopTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = emptyFlow()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) = Unit
}
