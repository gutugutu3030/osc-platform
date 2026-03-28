package com.oscplatform.adapter.webui

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * [SchemaEditorServer] の POST /api/evaluate エンドポイントに対する 包括的なエラーケースを検証するテスト。
 *
 * 検証内容:
 * - 不正な JSON → 400 + "Invalid JSON"
 * - `dsl` キーが存在しない JSON → 400 + "dsl field is required"
 * - 空ボディ → 400（JSON パース失敗）
 * - 空文字列の DSL → 400 + "dsl field is required"
 * - OscSchema を返さない DSL → 200 + success=false + "OscSchema" を含むエラー
 * - エラーレスポンスのメッセージが空でないことの確認
 */
class SchemaEditorEvaluateErrorTest {

  private val mapper: JsonMapper =
      JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /**
   * テスト用のサーバーを起動し、テスト後に確実に停止するヘルパー。
   *
   * @param port テスト用のポート番号。0 の場合は空きポートを自動検出する。
   * @param block サーバーを受け取るテストロジック
   */
  private fun withServer(port: Int = 0, block: (SchemaEditorServer) -> Unit) {
    val testPort = if (port == 0) findFreePort() else port
    val server = SchemaEditorServer(SchemaEditorServerConfig(httpPort = testPort))
    server.start()
    // Ktor CIO サーバーがリクエストを受け付けられるようになるまでポーリング
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
        val conn = URI("http://localhost:$port/").toURL().openConnection() as HttpURLConnection
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
    val socket = ServerSocket(0)
    val port = socket.localPort
    socket.close()
    return port
  }

  /**
   * 任意のボディ文字列を POST /api/evaluate に送信し、 HTTP ステータスコードとレスポンス JSON マップのペアを返すヘルパー。
   *
   * 4xx レスポンスの場合は [HttpURLConnection.getErrorStream] から、 それ以外は [HttpURLConnection.getInputStream]
   * からボディを読み取る。
   *
   * @param port サーバーポート番号
   * @param body リクエストボディ文字列
   * @return ステータスコードとレスポンス JSON マップのペア
   */
  @Suppress("UNCHECKED_CAST")
  private fun postEvaluateRaw(port: Int, body: String): Pair<Int, Map<String, Any?>> {
    val conn =
        URI("http://localhost:$port/api/evaluate").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.write(body.toByteArray())
    val statusCode = conn.responseCode
    // 4xx 系はエラーストリーム、それ以外は入力ストリームから読み取り
    val reader = if (statusCode < 400) conn.inputStream else conn.errorStream
    val responseBody = reader?.bufferedReader()?.readText() ?: ""
    return statusCode to mapper.readValue(responseBody, Map::class.java) as Map<String, Any?>
  }

  /** 不正な JSON を送信した場合に 400 が返り、 エラーメッセージに "Invalid JSON" が含まれることを確認する。 */
  @Test
  fun invalidJsonReturns400WithInvalidJsonError() {
    withServer { server ->
      val (status, body) = postEvaluateRaw(server.port, "this is not json")
      assertEquals(400, status)
      assertEquals(false, body["success"])
      val error = body["error"] as String
      assertTrue(error.contains("Invalid JSON"), "error should contain 'Invalid JSON': $error")
    }
  }

  /** JSON としては有効だが `dsl` キーが存在しない場合に 400 が返り、 エラーメッセージが "dsl field is required" であることを確認する。 */
  @Test
  fun missingDslKeyReturns400WithDslFieldRequired() {
    withServer { server ->
      val (status, body) = postEvaluateRaw(server.port, """{"code":"oscSchema {}"}""")
      assertEquals(400, status)
      assertEquals(false, body["success"])
      assertEquals("dsl field is required", body["error"])
    }
  }

  /**
   * 空のリクエストボディを送信した場合に 400 が返ることを確認する。
   *
   * 空文字列は有効な JSON ではないため、JSON パースエラーとなる。
   */
  @Test
  fun emptyBodyReturns400() {
    withServer { server ->
      val (status, body) = postEvaluateRaw(server.port, "")
      assertEquals(400, status)
      assertEquals(false, body["success"])
      assertNotNull(body["error"], "error field should be present")
    }
  }

  /** DSL フィールドが空文字列の場合に 400 が返り、 エラーメッセージが "dsl field is required" であることを確認する。 */
  @Test
  fun emptyDslStringReturns400WithDslFieldRequired() {
    withServer { server ->
      val (status, body) = postEvaluateRaw(server.port, """{"dsl":""}""")
      assertEquals(400, status)
      assertEquals(false, body["success"])
      assertEquals("dsl field is required", body["error"])
    }
  }

  /**
   * OscSchema を返さない DSL（例: 数値リテラル）を送信した場合、 HTTP 200 だが success=false で、エラーメッセージに "OscSchema"
   * が含まれることを確認する。
   *
   * 評価自体は成功するが、戻り値が OscSchema でないためエラーとなるケース。
   */
  @Test
  fun nonOscSchemaDslReturns200WithSuccessFalseAndOscSchemaError() {
    withServer { server ->
      val (status, body) = postEvaluateRaw(server.port, """{"dsl":"42"}""")
      assertEquals(200, status)
      assertEquals(false, body["success"])
      val error = body["error"] as String
      assertTrue(error.contains("OscSchema"), "error should mention 'OscSchema': $error")
    }
  }

  /**
   * エラーレスポンスの error フィールドが空でない文字列であることを確認する。
   *
   * ユーザーに対してわかりやすいエラーメッセージが返されているかの検証。
   */
  @Test
  fun errorResponseContainsNonBlankMessage() {
    withServer { server ->
      // 不正な JSON を送信してエラーレスポンスを取得
      val (_, body) = postEvaluateRaw(server.port, "{bad json}")
      assertEquals(false, body["success"])
      val error = body["error"] as? String
      assertNotNull(error, "error field should be a non-null string")
      assertTrue(error.isNotBlank(), "error message should not be blank")
    }
  }
}
