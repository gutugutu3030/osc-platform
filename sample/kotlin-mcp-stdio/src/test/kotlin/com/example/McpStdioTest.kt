package com.example

import com.oscplatform.adapter.mcp.McpAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/**
 * MCP アダプタの最小 E2E テスト（stdio フレーム方式）。
 *
 * `McpAdapter().execute(args)` という公開 API のみを使用し、 `System.setIn` / `System.setOut` で stdin/stdout
 * を一時差し替えることで Content-Length ヘッダ付き JSON-RPC フレームの入出力をテストする。
 *
 * 検証内容:
 * 1. tools/list に "bundle_set_scene" が含まれること
 * 2. tools/call で bundle_set_scene を呼び出すと成功応答が返ること
 */
class McpStdioTest {

  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /** テスト用スキーマ YAML (/light/color, /device/flag, bundle set_scene) */
  private val schemaYaml =
      """
        messages:
          - path: /light/color
            description: RGB カラーを設定する
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
            description: デバイスのフラグを設定する
            args:
              - name: enabled
                kind: scalar
                type: bool
        bundles:
          - name: set_scene
            description: カラーとフラグをアトミックに設定する
            messages:
              - ref: /light/color
              - ref: /device/flag
        """
          .trimIndent()

  // -------------------------------------------------------------------------
  // テストケース 1: tools/list に bundle_set_scene が含まれること
  // -------------------------------------------------------------------------

  @Test
  fun toolsListContainsBundleSetScene() {
    val schemaFile = Files.createTempFile("mcp-sample", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runMcpServer(
              schemaFile = schemaFile,
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
                  ),
          )

      assertEquals(1, responses.size, "レスポンスが 1 件であること")

      val tools = responses[0].path("result").path("tools")
      assertTrue(tools.isArray, "result.tools は配列であること")

      val toolNames = tools.toList().map { it.path("name").asText() }.toSet()
      assertTrue(
          toolNames.contains("bundle_set_scene"),
          "bundle_set_scene が tools/list に含まれること。実際: $toolNames",
      )
      // 通常メッセージツールも含まれること
      assertTrue(toolNames.contains("set_light_color"), "set_light_color が含まれること")
      assertTrue(toolNames.contains("set_device_flag"), "set_device_flag が含まれること")

      println("[Test 1] tools/list ツール一覧: $toolNames ✓")
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  // -------------------------------------------------------------------------
  // テストケース 2: tools/call で bundle_set_scene を呼び出すと成功応答が返ること
  // -------------------------------------------------------------------------

  @Test
  fun toolsCallBundleSetSceneReturnsSuccess() {
    val schemaFile = Files.createTempFile("mcp-sample", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runMcpServer(
              schemaFile = schemaFile,
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"bundle_set_scene","arguments":{"r":255,"g":0,"b":128,"enabled":true}}}""",
                  ),
          )

      assertEquals(1, responses.size, "レスポンスが 1 件であること")

      val response = responses[0]
      assertEquals("2.0", response.path("jsonrpc").asText())
      assertEquals(2, response.path("id").asInt())
      assertNull(response.get("error"), "エラーがないこと: $response")

      val content = response.path("result").path("content")
      assertTrue(content.isArray, "result.content は配列であること")
      assertEquals("text", content[0].path("type").asText())

      val text = content[0].path("text").asText()
      assertTrue(text.isNotBlank(), "result.content[0].text が空でないこと")

      println("[Test 2] tools/call bundle_set_scene 成功応答: $text ✓")
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  // -------------------------------------------------------------------------
  // ヘルパー: MCP サーバの実行
  // -------------------------------------------------------------------------

  /**
   * System.in / System.out を一時差し替えて McpAdapter.execute(args) を呼び出す。 入力フレームが尽きた時点でサーバが EOF
   * を検知して終了する。 終了後に System.in / System.out を必ず復元する。
   */
  private fun runMcpServer(
      schemaFile: java.nio.file.Path,
      inputJson: List<String>,
  ): List<ObjectNode> {
    // 入力フレームを Content-Length 形式でエンコード
    val inputBytes = inputJson.map { buildFrame(it) }.fold(ByteArray(0)) { acc, b -> acc + b }

    val capturedOutput = ByteArrayOutputStream()

    // System.in / System.out を保存して差し替える
    val savedIn = System.`in`
    val savedOut = System.out
    try {
      System.setIn(ByteArrayInputStream(inputBytes))
      System.setOut(PrintStream(capturedOutput, /* autoFlush= */ true))

      // McpAdapter の公開 API のみを使用
      // execute(args) は内部で System.in から読み取り System.out へ書き込む
      runBlocking {
        McpAdapter()
            .execute(
                listOf(
                    "--schema=${schemaFile.toAbsolutePath()}",
                    "--host=127.0.0.1",
                    "--port=9000",
                ))
      }
    } finally {
      // System.in / System.out を必ず復元する
      System.setIn(savedIn)
      System.setOut(savedOut)
    }

    return parseFrames(capturedOutput.toByteArray())
  }

  // -------------------------------------------------------------------------
  // ヘルパー: フレーム構築 / パース
  // -------------------------------------------------------------------------

  /** JSON 文字列を MCP stdio フレーム（Content-Length ヘッダ + CRLF + JSON ペイロード）に変換する。 */
  private fun buildFrame(json: String): ByteArray {
    val payload = json.toByteArray(StandardCharsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    return header + payload
  }

  /** MCP stdio フレーム列をパースして ObjectNode のリストを返す。 Content-Length ヘッダを読み、指定バイト数の JSON ペイロードを取得する。 */
  private fun parseFrames(bytes: ByteArray): List<ObjectNode> {
    val input = ByteArrayInputStream(bytes)
    val frames = mutableListOf<ObjectNode>()
    while (true) {
      var contentLength = -1
      // ヘッダ行を空行まで読む
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

  /** CRLF 終端の 1 行を読む。ストリーム終端なら null を返す。 */
  private fun readHeaderLine(input: ByteArrayInputStream): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
      val b = input.read()
      if (b < 0)
          return if (bytes.isEmpty()) null
          else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
      if (b == '\r'.code) {
        input.read() // '\n' を消費
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
      }
      bytes += b.toByte()
    }
  }
}
