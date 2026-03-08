package com.oscplatform.adapter.mcp

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/**
 * tools/call の E2E 統合テスト。 stdioフレーム（Content-Length ヘッダ + JSON ペイロード）を入力として、 OscMcpServer の run()
 * ループを通過し result / error レスポンスが返ることを検証する。
 */
class McpToolsCallIntegrationTest {

  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  // テスト用の最小スキーマ。/light/color → MCP ツール名 set_light_color
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

  // -------------------------------------------------------------------------
  // テストケース
  // -------------------------------------------------------------------------

  /** 正しいツール名と引数を渡すと result が返り、OSC パケットが送信される */
  @Test
  fun toolsCallWithValidToolReturnsResult() {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = RecordingFakeTransport()

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = transport,
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"set_light_color","arguments":{"r":255,"g":128,"b":0}}}""",
                  ),
          )

      assertEquals(1, responses.size)
      val response = responses[0]

      // JSON-RPC フィールド
      assertEquals("2.0", response.path("jsonrpc").stringValue())
      assertEquals(1, response.path("id").asInt())
      assertNull(response.get("error"), "エラーフィールドがないこと")

      // result.content
      val content = response.path("result").path("content")
      assertTrue(content.isArray, "content は配列であること")
      assertEquals(1, content.size())
      assertEquals("text", content[0].path("type").stringValue())
      assertTrue(
          (content[0].path("text").stringValue() ?: "").contains("/light/color"),
          "テキストに OSC パスが含まれること",
      )

      // トランスポートに OSC パケットが渡されたこと
      assertEquals(1, transport.sentPackets.size, "OSC パケットが1件送信されること")
      assertEquals("/light/color", transport.sentPackets[0].address)
      assertEquals(listOf(255, 128, 0), transport.sentPackets[0].arguments)
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** スキーマに存在しないツール名を呼ぶと -32000 エラーが返る */
  @Test
  fun toolsCallWithUnknownToolReturnsError() {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = RecordingFakeTransport(),
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"nonexistent_tool","arguments":{}}}""",
                  ),
          )

      assertEquals(1, responses.size)
      val response = responses[0]

      assertEquals("2.0", response.path("jsonrpc").stringValue())
      assertEquals(2, response.path("id").asInt())
      assertNull(response.get("result"), "result フィールドがないこと")

      val error = response.path("error")
      assertNotNull(error, "error フィールドがあること")
      assertEquals(-32000, error.path("code").asInt())
      assertTrue(
          (error.path("message").stringValue() ?: "").contains("nonexistent_tool"),
          "エラーメッセージに不明なツール名が含まれること",
      )
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** トランスポートが例外をスローした場合も -32000 エラーとして返る */
  @Test
  fun toolsCallWithTransportFailureReturnsError() {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = FailingFakeTransport("UDP send failed")

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = transport,
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"set_light_color","arguments":{"r":10,"g":20,"b":30}}}""",
                  ),
          )

      assertEquals(1, responses.size)
      val response = responses[0]

      assertEquals(3, response.path("id").asInt())
      assertNull(response.get("result"), "result フィールドがないこと")

      val error = response.path("error")
      assertEquals(-32000, error.path("code").asInt())
      assertEquals("UDP send failed", error.path("message").stringValue())
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** 複数リクエストが入力順に処理され、失敗したツール呼び出しは OSC を送信しない */
  @Test
  fun multipleRequestsAreProcessedInOrder() {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = RecordingFakeTransport()

      val responses =
          runServer(
              schemaFile = schemaFile,
              transport = transport,
              inputJson =
                  listOf(
                      """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"set_light_color","arguments":{"r":1,"g":2,"b":3}}}""",
                      """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"unknown_x","arguments":{}}}""",
                      """{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"set_light_color","arguments":{"r":4,"g":5,"b":6}}}""",
                  ),
          )

      assertEquals(3, responses.size)

      // id:10 → result
      assertNull(responses[0].get("error"))
      assertEquals(10, responses[0].path("id").asInt())

      // id:11 → error (unknown tool)
      assertNull(responses[1].get("result"))
      assertEquals(11, responses[1].path("id").asInt())
      assertEquals(-32000, responses[1].path("error").path("code").asInt())

      // id:12 → result
      assertNull(responses[2].get("error"))
      assertEquals(12, responses[2].path("id").asInt())

      assertEquals(2, transport.sentPackets.size, "成功した呼び出しだけ OSC が送信されること")
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  // -------------------------------------------------------------------------
  // テスト固有のヘルパー
  // -------------------------------------------------------------------------

  /** MCP stdio フレーム（Content-Length ヘッダ + CRLF 区切り + JSON ペイロード）を組み立てる。 */
  private fun buildFrame(json: String): ByteArray {
    val payload = json.toByteArray(StandardCharsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    return header + payload
  }

  /** MCP stdio フレームの列をパースして ObjectNode のリストを返す。 Content-Length ヘッダを読み、指定バイト数のペイロードを取得する。 */
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

  /** CRLF 終端の1行を読む。ストリーム終端なら null を返す。 */
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

  /** McpAdapter の internal overload を呼びサーバを同期実行する。 入力 JSON フレームを全て書き込み EOF にすることでサーバループを終了させる。 */
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

// -------------------------------------------------------------------------
// テスト用フェイクトランスポート
// -------------------------------------------------------------------------

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
