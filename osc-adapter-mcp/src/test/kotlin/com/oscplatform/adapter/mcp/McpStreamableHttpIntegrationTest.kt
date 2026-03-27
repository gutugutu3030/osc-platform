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

/** streamable HTTP transport を通した MCP 統合テスト。 */
class McpStreamableHttpIntegrationTest {

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

  /** streamable HTTP 経由でも tools/list と tools/call が成功し、OSC が送信される。 */
  @Test
  fun streamableHttpToolsListAndCallWork() = runBlocking {
    val schemaFile = Files.createTempFile("osc-http-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = RecordingHttpTransport()

      withStreamableHttpMcpClient(schemaFile = schemaFile, transport = transport) { client, _ ->
        val tools = client.listTools().tools
        assertTrue(tools.any { it.name == "set_light_color" })

        val toolResult =
            client.callTool(
                name = "set_light_color",
                arguments = mapOf("r" to 64, "g" to 32, "b" to 16),
            )

        assertFalse(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("/light/color"))
      }

      assertEquals(1, transport.sentPackets.size)
      assertEquals("/light/color", transport.sentPackets.single().address)
      assertEquals(listOf(64, 32, 16), transport.sentPackets.single().arguments)
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** streamable HTTP 経由で送信が失敗した場合は error result が返る。 */
  @Test
  fun streamableHttpToolFailureReturnsErrorResult() = runBlocking {
    val schemaFile = Files.createTempFile("osc-http-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStreamableHttpMcpClient(
          schemaFile = schemaFile,
          transport = FailingHttpTransport("HTTP transport send failed"),
      ) { client, _ ->
        val toolResult =
            client.callTool(
                name = "set_light_color",
                arguments = mapOf("r" to 1, "g" to 2, "b" to 3),
            )

        assertTrue(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("HTTP transport send failed"))
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }
}

/** streamable HTTP テスト用の記録トランスポート。 */
private class RecordingHttpTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
  val sentPackets = mutableListOf<OscMessagePacket>()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    sentPackets += packet as OscMessagePacket
  }
}

/** streamable HTTP テスト用の失敗トランスポート。 */
private class FailingHttpTransport(private val errorMessage: String) : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    throw IllegalStateException(errorMessage)
  }
}
