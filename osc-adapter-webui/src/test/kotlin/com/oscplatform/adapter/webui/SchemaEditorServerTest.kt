package com.oscplatform.adapter.webui

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * [SchemaEditorServer] の HTTP エンドポイントと DSL 評価を検証するテスト。
 *
 * 検証内容:
 * - GET / がエディタ HTML を返す
 * - POST /api/evaluate で有効な DSL がスキーマ JSON を返す
 * - POST /api/evaluate で無効な DSL がエラーを返す
 * - POST /api/evaluate で空の DSL がエラーを返す
 * - evaluateDsl で有効な DSL が OscSchema を返す
 * - serializeSchema でスキーマが正しくシリアライズされる
 */
class SchemaEditorServerTest {

  private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /**
   * テスト用のサーバーを起動し、テスト後に停止するヘルパー。
   *
   * @param port テスト用のポート番号
   * @param block テストロジック
   */
  private fun withServer(port: Int = 0, block: (SchemaEditorServer) -> Unit) {
    // ポート 0 を使ってランダムなポートを割り当てる代わりに、テスト専用ポートを使用
    val testPort = if (port == 0) findFreePort() else port
    val server = SchemaEditorServer(SchemaEditorServerConfig(httpPort = testPort))
    server.start()
    // Ktor CIO サーバーの起動をリトライポーリングで待機
    awaitServerReady(testPort)
    try {
      block(server)
    } finally {
      server.stop()
    }
  }

  /**
   * サーバーがリクエストを受け付けられるまでリトライする。
   *
   * @param port 接続先ポート番号
   * @param maxRetries 最大リトライ回数
   * @param intervalMs リトライ間隔（ミリ秒）
   */
  private fun awaitServerReady(port: Int, maxRetries: Int = 20, intervalMs: Long = 100) {
    repeat(maxRetries) {
      try {
        val conn =
            java.net.URI("http://localhost:$port/").toURL().openConnection()
                as java.net.HttpURLConnection
        conn.connectTimeout = 200
        conn.readTimeout = 200
        conn.responseCode
        conn.disconnect()
        return
      } catch (_: Exception) {
        Thread.sleep(intervalMs)
      }
    }
    error("Server on port $port did not become ready within ${maxRetries * intervalMs}ms")
  }

  /**
   * 空きポートを見つける。
   *
   * @return 利用可能なポート番号
   */
  private fun findFreePort(): Int {
    val socket = java.net.ServerSocket(0)
    val port = socket.localPort
    socket.close()
    return port
  }

  /** GET / がステータス 200 でエディタ HTML を返すことを確認する。 */
  @Test
  fun getIndexReturnsEditorHtml() {
    withServer { server ->
      val conn =
          URI("http://localhost:${server.port}/").toURL().openConnection() as HttpURLConnection
      conn.requestMethod = "GET"
      assertEquals(200, conn.responseCode)
      val body = conn.inputStream.bufferedReader().readText()
      assertTrue(body.contains("OSC Schema Editor"))
      assertTrue(body.contains("Kotlin DSL"))
    }
  }

  /** GET / で返る HTML にフォーマットボタンが含まれることを確認する。 */
  @Test
  fun getIndexContainsFormatButton() {
    withServer { server ->
      val conn =
          URI("http://localhost:${server.port}/").toURL().openConnection() as HttpURLConnection
      conn.requestMethod = "GET"
      assertEquals(200, conn.responseCode)
      val body = conn.inputStream.bufferedReader().readText()
      assertTrue(body.contains("id=\"format-btn\""), "フォーマットボタンの id が含まれていること")
      assertTrue(body.contains("フォーマット"), "フォーマットボタンのラベルが含まれていること")
    }
  }

  /** GET / で返る HTML に括弧ペア補完のロジックが含まれることを確認する。 */
  @Test
  fun getIndexContainsBracketPairCompletion() {
    withServer { server ->
      val conn =
          URI("http://localhost:${server.port}/").toURL().openConnection() as HttpURLConnection
      conn.requestMethod = "GET"
      assertEquals(200, conn.responseCode)
      val body = conn.inputStream.bufferedReader().readText()
      assertTrue(body.contains("括弧ペア補完"), "括弧ペア補完のコメントが含まれていること")
      assertTrue(body.contains("閉じ括弧のスキップ"), "閉じ括弧スキップのコメントが含まれていること")
    }
  }

  /** POST /api/evaluate で有効な DSL がスキーマを含む成功レスポンスを返すことを確認する。 */
  @Test
  fun evaluateValidDslReturnsSchema() {
    withServer { server ->
      val dsl =
          """
        oscSchema {
            message("/test/path") {
                description("テスト用メッセージ")
                scalar("value", INT)
            }
        }
      """
              .trimIndent()

      val result = postEvaluate(server.port, dsl)
      assertEquals(true, result["success"])
      val schema = result["schema"] as Map<*, *>
      val messages = schema["messages"] as List<*>
      assertEquals(1, messages.size)
      val msg = messages[0] as Map<*, *>
      assertEquals("/test/path", msg["path"])
      assertEquals("テスト用メッセージ", msg["description"])
    }
  }

  /** POST /api/evaluate で無効な DSL がエラーレスポンスを返すことを確認する。 */
  @Test
  fun evaluateInvalidDslReturnsError() {
    withServer { server ->
      val result = postEvaluate(server.port, "invalid kotlin code {{{")
      assertEquals(false, result["success"])
      assertNotNull(result["error"])
    }
  }

  /** POST /api/evaluate で空の DSL がエラーレスポンスを返すことを確認する。 */
  @Test
  fun evaluateEmptyDslReturnsError() {
    withServer { server ->
      val conn =
          URI("http://localhost:${server.port}/api/evaluate").toURL().openConnection()
              as HttpURLConnection
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.outputStream.write("""{"dsl":""}""".toByteArray())
      assertEquals(400, conn.responseCode)
    }
  }

  /** evaluateDsl で有効な DSL が OscSchema を正しく返すことを確認する。 */
  @Test
  fun evaluateDslReturnsOscSchema() {
    val server = SchemaEditorServer()
    val schema =
        server.evaluateDsl(
            """
        oscSchema {
            message("/light/color") {
                description("RGB")
                scalar("r", INT)
                scalar("g", INT)
                scalar("b", INT)
            }
        }
    """
                .trimIndent())
    assertEquals(1, schema.messages.size)
    assertEquals("/light/color", schema.messages[0].path)
    assertEquals(3, schema.messages[0].args.size)
  }

  /** serializeSchema でメッセージとバンドルが正しく含まれることを確認する。 */
  @Test
  fun serializeSchemaContainsMessagesAndBundles() {
    val server = SchemaEditorServer()
    val schema =
        server.evaluateDsl(
            """
        oscSchema {
            message("/a") {
                scalar("x", INT)
            }
            message("/b") {
                scalar("y", FLOAT)
            }
            bundle("AB") {
                description("テストバンドル")
                message("/a")
                message("/b")
            }
        }
    """
                .trimIndent())
    val result = server.serializeSchema(schema)
    val messages = result["messages"] as List<*>
    assertEquals(2, messages.size)
    val bundles = result["bundles"] as List<*>
    assertEquals(1, bundles.size)
    val bundle = bundles[0] as Map<*, *>
    assertEquals("AB", bundle["name"])
    assertEquals("テストバンドル", bundle["description"])
  }

  /**
   * DSL テキストを POST /api/evaluate に送信してレスポンスを返すヘルパー。
   *
   * @param port サーバーポート番号
   * @param dsl 評価する DSL テキスト
   * @return レスポンスの JSON マップ
   */
  @Suppress("UNCHECKED_CAST")
  private fun postEvaluate(port: Int, dsl: String): Map<String, Any?> {
    val conn =
        URI("http://localhost:$port/api/evaluate").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    val requestBody = mapper.writeValueAsString(mapOf("dsl" to dsl))
    conn.outputStream.write(requestBody.toByteArray())

    val reader = BufferedReader(InputStreamReader(conn.inputStream))
    val response = reader.readText()
    reader.close()

    return mapper.readValue(response, Map::class.java) as Map<String, Any?>
  }
}
