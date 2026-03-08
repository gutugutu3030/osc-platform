package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscBundlePacket
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/** Bundle スキーマ → MCP ツール自動生成・呼び出しの E2E テスト。 */
class McpBundleToolsIntegrationTest {

  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  // バンドルを含む最小スキーマ
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

  // -------------------------------------------------------------------------
  // tools/list
  // -------------------------------------------------------------------------

  /** tools/list のツール一覧に "bundle_set_scene" エントリが存在すること */
  @Test
  fun toolsListIncludesBundleTool() {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = AllPacketsTransport(),
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
                  ),
          )

      assertEquals(1, responses.size)
      val result = responses[0].path("result")
      val tools = result.path("tools")
      assertTrue(tools.isArray)

      val toolNames = tools.toList().map { it.path("name").stringValue() ?: "" }.toSet()
      assertTrue(
          toolNames.contains("bundle_set_scene"),
          "bundle_set_scene が tools/list に含まれること: $toolNames")
      // 通常のメッセージツールも含まれること
      assertTrue(toolNames.contains("set_light_color"))
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** inputSchema が全メッセージの引数をフラットマージした内容になっていること */
  @Test
  fun bundleToolHasCorrectInputSchema() {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = AllPacketsTransport(),
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
                  ),
          )

      val tools = responses[0].path("result").path("tools")
      val bundleTool = tools.firstOrNull { it.path("name").stringValue() == "bundle_set_scene" }
      assertNotNull(bundleTool, "bundle_set_scene が存在すること")

      // inputSchema は flat merge: r, g, b, enabled が全てトップレベルにある
      val props = bundleTool.path("inputSchema").path("properties")
      assertTrue(props.has("r"), "r が含まれること")
      assertTrue(props.has("g"), "g が含まれること")
      assertTrue(props.has("b"), "b が含まれること")
      assertTrue(props.has("enabled"), "enabled が含まれること")

      assertEquals("integer", props.path("r").path("type").stringValue())
      assertEquals("boolean", props.path("enabled").path("type").stringValue())

      val required =
          bundleTool
              .path("inputSchema")
              .path("required")
              .toList()
              .map { it.stringValue() ?: "" }
              .toSet()
      assertTrue(required.containsAll(setOf("r", "g", "b", "enabled")))
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** YAML の description 字段が MCP ツール記述にそのまま反映されること */
  @Test
  fun bundleToolHasDescription() {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = AllPacketsTransport(),
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
                  ),
          )

      val tools = responses[0].path("result").path("tools")
      val bundleTool = tools.first { it.path("name").stringValue() == "bundle_set_scene" }
      assertEquals("ライトとフラグをアトミックに設定", bundleTool.path("description").stringValue())
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  // -------------------------------------------------------------------------
  // tools/call (bundle)
  // -------------------------------------------------------------------------

  /** tools/call により OscBundlePacket が送信され、各メッセージの引数が正しく指定メッセージに割り振られること */
  @Test
  fun toolsCallBundleToolSendsBundlePacket() {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = AllPacketsTransport()

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = transport,
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"bundle_set_scene","arguments":{"r":255,"g":128,"b":0,"enabled":true}}}""",
                  ),
          )

      assertEquals(1, responses.size)
      val response = responses[0]
      assertEquals("2.0", response.path("jsonrpc").stringValue())
      assertEquals(1, response.path("id").asInt())
      assertNull(response.get("error"), "エラーなし")

      // OscBundlePacket が送信されること
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

  /** result.content[0].text にバンドル名が含まれるテキストプロンプトが返ること */
  @Test
  fun toolsCallBundleToolReturnsTextResult() {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = AllPacketsTransport(),
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"bundle_set_scene","arguments":{"r":0,"g":0,"b":0,"enabled":false}}}""",
                  ),
          )

      val content = responses[0].path("result").path("content")
      assertTrue(content.isArray)
      assertEquals("text", content[0].path("type").stringValue())
      val text = content[0].path("text").stringValue() ?: ""
      assertTrue(text.contains("set_scene"), "テキストにバンドル名が含まれること: $text")
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** required 引数が欠けているとバンドル内メッセージの flattenArgs で例外が発生し -32000 エラーが返る */
  @Test
  fun toolsCallBundleToolWithMissingArgReturnsError() {
    val schemaFile = Files.createTempFile("osc-bundle-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = AllPacketsTransport(),
              inputJson =
                  listOf(
                      // enabled が欠けている
                      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"bundle_set_scene","arguments":{"r":255,"g":128,"b":0}}}""",
                  ),
          )

      val response = responses[0]
      assertNull(response.get("result"))
      assertEquals(-32000, response.path("error").path("code").asInt())
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  // -------------------------------------------------------------------------
  // ヘルパー
  // -------------------------------------------------------------------------

  private fun buildFrame(json: String): ByteArray {
    val payload = json.toByteArray(StandardCharsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    return header + payload
  }

  private fun parseFrames(bytes: ByteArray): List<ObjectNode> {
    val input = ByteArrayInputStream(bytes)
    val frames = mutableListOf<ObjectNode>()
    while (true) {
      var contentLength = -1
      while (true) {
        val line = readHeaderLine(input) ?: return frames
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon >= 0) {
          val key = line.substring(0, colon).trim()
          val value = line.substring(colon + 1).trim()
          if (key.equals("Content-Length", ignoreCase = true)) {
            contentLength = value.toIntOrNull() ?: -1
          }
        }
      }
      if (contentLength <= 0) return frames
      val payload = ByteArray(contentLength)
      var offset = 0
      while (offset < contentLength) {
        val n = input.read(payload, offset, contentLength - offset)
        if (n < 0) return frames
        offset += n
      }
      frames += mapper.readTree(payload) as ObjectNode
    }
  }

  private fun readHeaderLine(input: ByteArrayInputStream): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
      val b = input.read()
      if (b < 0)
          return if (bytes.isEmpty()) null
          else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
      if (b == '\r'.code) {
        input.read()
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
      }
      bytes += b.toByte()
    }
  }

  private fun runServer(
      schemaFile: java.nio.file.Path,
      transport: OscTransport,
      inputJson: List<String>,
  ): List<ObjectNode> {
    val inputBytes = inputJson.map { buildFrame(it) }.fold(ByteArray(0)) { acc, b -> acc + b }
    val input = ByteArrayInputStream(inputBytes)
    val output = ByteArrayOutputStream()

    runBlocking {
      McpAdapter()
          .execute(
              args =
                  listOf(
                      "--schema=${schemaFile.toAbsolutePath()}",
                      "--host=127.0.0.1",
                      "--port=9000",
                  ),
              input = input,
              output = output,
              transport = transport,
          )
    }

    return parseFrames(output.toByteArray())
  }
}

/** OscMessagePacket と OscBundlePacket 両方を記録するフェイクトランスポート。 */
private class AllPacketsTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
  val sentPackets = mutableListOf<OscPacket>()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    sentPackets += packet
  }
}
