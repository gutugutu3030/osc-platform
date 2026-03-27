package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscBundlePacket
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bundle スキーマの tools/list と tools/call を stdio transport 経由で検証する統合テスト。 */
class McpBundleToolsIntegrationTest {

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
          - path: /device/flag
            description: set device flag
            args:
              - name: enabled
                kind: scalar
                type: bool
        bundles:
          - name: set_scene
            description: ライトとフラグをアトミックに設定
            messages:
              - ref: /light/color
              - ref: /device/flag
    """
          .trimIndent()

  /** tools/list の一覧に bundle_set_scene エントリが存在する。 */
  @Test
  fun toolsListIncludesBundleTool() = runBlocking {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(schemaFile = schemaFile, transport = AllPacketsTransport()) { client ->
        val listToolsResult = client.listTools()
        val toolNames = listToolsResult.tools.map { it.name }.toSet()

        assertTrue(toolNames.contains("bundle_set_scene"))
        assertTrue(toolNames.contains("set_light_color"))
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** inputSchema が全メッセージの引数をフラットマージした内容になっている。 */
  @Test
  fun bundleToolHasCorrectInputSchema() = runBlocking {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(schemaFile = schemaFile, transport = AllPacketsTransport()) { client ->
        val bundleTool = client.listTools().tools.firstOrNull { it.name == "bundle_set_scene" }
        assertNotNull(bundleTool)

        val properties = requireNotNull(bundleTool.inputSchema.properties)
        assertEquals(
            "integer", properties.getValue("r").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "boolean",
            properties.getValue("enabled").jsonObject.getValue("type").jsonPrimitive.content,
        )

        val required = bundleTool.inputSchema.required.orEmpty().toSet()
        assertTrue(required.containsAll(setOf("r", "g", "b", "enabled")))
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** YAML の description が MCP ツール記述に反映される。 */
  @Test
  fun bundleToolHasDescription() = runBlocking {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(schemaFile = schemaFile, transport = AllPacketsTransport()) { client ->
        val bundleTool = client.listTools().tools.first { it.name == "bundle_set_scene" }
        assertEquals("ライトとフラグをアトミックに設定", bundleTool.description)
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** tools/call により OscBundlePacket が送信され、各メッセージの引数が正しく割り振られる。 */
  @Test
  fun toolsCallBundleToolSendsBundlePacket() = runBlocking {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = AllPacketsTransport()

      withStdioMcpClient(schemaFile = schemaFile, transport = transport) { client ->
        val toolResult =
            client.callTool(
                name = "bundle_set_scene",
                arguments = mapOf("r" to 255, "g" to 128, "b" to 0, "enabled" to true),
            )

        assertFalse(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("set_scene"))
      }

      assertEquals(1, transport.sentPackets.size)
      val bundle = assertIs<OscBundlePacket>(transport.sentPackets.single())
      assertEquals(2, bundle.elements.size)

      val colorMsg = assertIs<OscMessagePacket>(bundle.elements[0])
      assertEquals("/light/color", colorMsg.address)
      assertEquals(listOf(255, 128, 0), colorMsg.arguments)

      val flagMsg = assertIs<OscMessagePacket>(bundle.elements[1])
      assertEquals("/device/flag", flagMsg.address)
      assertEquals(listOf(true), flagMsg.arguments)
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** required 引数が欠けていると error result が返る。 */
  @Test
  fun toolsCallBundleToolWithMissingArgReturnsErrorResult() = runBlocking {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(schemaFile = schemaFile, transport = AllPacketsTransport()) { client ->
        val toolResult =
            client.callTool(
                name = "bundle_set_scene",
                arguments = mapOf("r" to 255, "g" to 128, "b" to 0),
            )

        assertTrue(toolResult.isError == true)
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }
}

/** OscMessagePacket と OscBundlePacket の両方を記録するフェイクトランスポート。 */
private class AllPacketsTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
  val sentPackets = mutableListOf<OscPacket>()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    sentPackets += packet
  }
}
