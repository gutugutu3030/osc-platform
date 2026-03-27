package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * stdio transport を通した tools/call の統合テスト。
 *
 * MCP SDK のクライアントとサーバー transport を実際に接続し、OSC 送信とエラー応答を検証する。
 */
class McpToolsCallIntegrationTest {

  private val schemaYaml =
      """
        messages:
          - path: /light/color
            description: set RGB color
            args:
              - name: r
                kind: scalar
                type: int
              - name: g
                kind: scalar
                type: int
              - name: b
                kind: scalar
                type: int
    """
          .trimIndent()

  /** 正しいツール名と引数を渡すと成功結果が返り、OSC パケットが送信される。 */
  @Test
  fun toolsCallWithValidToolReturnsResult() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = RecordingFakeTransport()

      withStdioMcpClient(schemaFile = schemaFile, transport = transport) { client ->
        val toolResult =
            client.callTool(
                name = "set_light_color",
                arguments = mapOf("r" to 255, "g" to 128, "b" to 0),
            )

        assertFalse(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("/light/color"))
      }

      assertEquals(1, transport.sentPackets.size)
      assertEquals("/light/color", transport.sentPackets[0].address)
      assertEquals(listOf(255, 128, 0), transport.sentPackets[0].arguments)
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** スキーマに存在しないツール名を呼ぶと error result が返る。 */
  @Test
  fun toolsCallWithUnknownToolReturnsErrorResult() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(schemaFile = schemaFile, transport = RecordingFakeTransport()) { client ->
        val toolResult =
            client.callTool(name = "nonexistent_tool", arguments = emptyMap<String, Any>())

        assertTrue(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("not found"))
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** トランスポートが例外をスローした場合も error result として返る。 */
  @Test
  fun toolsCallWithTransportFailureReturnsErrorResult() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = FailingFakeTransport("UDP send failed"),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_light_color",
                arguments = mapOf("r" to 10, "g" to 20, "b" to 30),
            )

        assertTrue(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("UDP send failed"))
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** 複数リクエストを順に処理しても、成功した分だけ OSC が送信される。 */
  @Test
  fun multipleRequestsAreProcessedInOrder() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = RecordingFakeTransport()

      withStdioMcpClient(schemaFile = schemaFile, transport = transport) { client ->
        val first =
            client.callTool(
                name = "set_light_color",
                arguments = mapOf("r" to 1, "g" to 2, "b" to 3),
            )
        val second = client.callTool(name = "unknown_x", arguments = emptyMap<String, Any>())
        val third =
            client.callTool(
                name = "set_light_color",
                arguments = mapOf("r" to 4, "g" to 5, "b" to 6),
            )

        assertFalse(first.isError == true)
        assertTrue(second.isError == true)
        assertFalse(third.isError == true)
      }

      assertEquals(2, transport.sentPackets.size)
      assertEquals(listOf(1, 2, 3), transport.sentPackets[0].arguments)
      assertEquals(listOf(4, 5, 6), transport.sentPackets[1].arguments)
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }
}

/** 送信パケットを記録するだけで実際に送信しないフェイクトランスポート。 */
private class RecordingFakeTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
  val sentPackets: MutableList<OscMessagePacket> = mutableListOf()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    sentPackets += packet as OscMessagePacket
  }
}

/** send() を常に例外で失敗するフェイクトランスポート。 */
private class FailingFakeTransport(private val errorMessage: String) : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    throw RuntimeException(errorMessage)
  }
}
