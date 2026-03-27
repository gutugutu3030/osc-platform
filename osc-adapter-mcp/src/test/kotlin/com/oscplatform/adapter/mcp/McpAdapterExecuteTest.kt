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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/** McpAdapter の CLI 分岐と起動モード選択を検証するテスト。 */
class McpAdapterExecuteTest {

  /** help を指定すると usage が出力されて正常終了する。 */
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
    assertTrue(outBuffer.toString().contains("--streamable-http-port"))
    assertTrue(outBuffer.toString().contains("--listen-host"))
    assertEquals("", errBuffer.toString())
  }

  /** stdio モードで Web UI を付け、入力 EOF で即座に終了できる。 */
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

  /** listen-host は streamable HTTP モードなしでは指定できない。 */
  @Test
  fun executeWithListenHostWithoutHttpReturnsOneAndPrintsFormattedError() = runBlocking {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        McpAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode =
        adapter.execute(
            args = listOf("--host", "127.0.0.1", "--port", "9000", "--listen-host", "0.0.0.0"),
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            transport = NoopTransport(),
        )

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("--listen-host requires --streamable-http-port"))
    assertTrue(outBuffer.toString().contains("osc mcp"))
  }

  /** streamable HTTP モードを起動すると待受情報が得られ、ライフサイクル終了後に正常終了する。 */
  @Test
  fun executeWithStreamableHttpPortStartsServerAndReturnsZero() = runBlocking {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val schemaPath = resolveSchemaPathForTest()
    val adapter =
        McpAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )
    val started = CompletableDeferred<McpHttpServerHandle>()

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
                    "--streamable-http-port",
                    "0",
                    "--listen-host",
                    "127.0.0.1",
                ),
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            transport = NoopTransport(),
            httpServerLifecycle = { handle ->
              started.complete(handle)
              handle.stopSignal.complete(Unit)
            },
        )

    val handle = started.await()
    assertEquals(0, exitCode)
    assertEquals("127.0.0.1", handle.host)
    assertTrue(handle.port >= 0)
    assertEquals("/mcp", handle.path)
    assertTrue(errBuffer.toString().contains("MCP streamable HTTP server started"))
  }
}

/** テスト用の schema.yaml を見つけて返す。 */
private fun resolveSchemaPathForTest(): String {
  val candidates = listOf(Path.of("schema.yaml"), Path.of("..", "schema.yaml"))
  return candidates.firstOrNull { Files.exists(it) }?.normalize()?.toString()
      ?: error("schema.yaml not found for test")
}

/** 利用可能なローカルポートを 1 つ確保する。 */
private fun allocatePort(): Int = ServerSocket(0).use { it.localPort }

/** 送信を何もしないテスト用トランスポート。 */
private class NoopTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = emptyFlow()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) = Unit
}
