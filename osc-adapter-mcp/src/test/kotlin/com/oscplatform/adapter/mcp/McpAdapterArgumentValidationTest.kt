package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** McpAdapter の CLI 引数バリデーションを検証するテスト。 */
class McpAdapterArgumentValidationTest {

  /**
   * --host が指定されていない場合、終了コード 1 で "mcp requires --host" が stderr に出力される。
   *
   * --port だけ指定してホストを省略するケースを検証する。
   */
  @Test
  fun missingHostReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) = executeWithArgs(listOf("--port", "9000"))

    assertEquals(1, exitCode)
    assertTrue(errText.contains("mcp requires --host"))
  }

  /**
   * --port が指定されていない場合、終了コード 1 で "mcp requires --port" が stderr に出力される。
   *
   * --host だけ指定してポートを省略するケースを検証する。
   */
  @Test
  fun missingPortReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) = executeWithArgs(listOf("--host", "127.0.0.1"))

    assertEquals(1, exitCode)
    assertTrue(errText.contains("mcp requires --port"))
  }

  /**
   * --port に数値以外の文字列を指定した場合、終了コード 1 で "Invalid --port value" が stderr に出力される。
   *
   * toPortOrUsage が非数値を検出して McpUsageException を投げるケースを検証する。
   */
  @Test
  fun nonNumericPortReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) = executeWithArgs(listOf("--host", "127.0.0.1", "--port", "abc"))

    assertEquals(1, exitCode)
    assertTrue(errText.contains("Invalid --port value"))
  }

  /**
   * 未知のオプションを指定した場合、終了コード 1 で "Unknown mcp option" が stderr に出力される。
   *
   * パーサーが "--bogus" を認識できず即座にエラーとなるケースを検証する。
   */
  @Test
  fun unknownOptionReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) =
        executeWithArgs(listOf("--bogus", "--host", "127.0.0.1", "--port", "9000"))

    assertEquals(1, exitCode)
    assertTrue(errText.contains("Unknown mcp option"))
  }

  /**
   * 未知のオプションで失敗した場合、usage テキストが stdout に出力される。
   *
   * McpUsageException を捕捉した際に printUsage() が呼ばれることを確認する。
   */
  @Test
  fun unknownOptionPrintsUsageToStdout() = runBlocking {
    val (exitCode, outText, _) =
        executeWithArgs(listOf("--bogus", "--host", "127.0.0.1", "--port", "9000"))

    assertEquals(1, exitCode)
    assertTrue(outText.contains("osc mcp"))
  }

  /**
   * --port に範囲外の数値を指定した場合、終了コード 1 でエラーが返る。
   *
   * ポート番号の上限 65535 を超える値を検証する。
   */
  @Test
  fun outOfRangePortReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) = executeWithArgs(listOf("--host", "127.0.0.1", "--port", "99999"))

    assertEquals(1, exitCode)
    assertTrue(errText.contains("Invalid --port value"))
  }

  /**
   * --port に負数を指定した場合、終了コード 1 でエラーが返る。
   *
   * ポート番号の下限 0 未満の値を検証する。
   */
  @Test
  fun negativePortReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) = executeWithArgs(listOf("--host", "127.0.0.1", "--port", "-1"))

    assertEquals(1, exitCode)
    assertTrue(errText.contains("Invalid --port value"))
  }

  /**
   * --streamable-http-port と --webui-port が同じ値の場合、終了コード 1 でエラーが返る。
   *
   * ポート競合の検出を検証する。execute 経由で parseArgs のバリデーションが発動する。
   */
  @Test
  fun conflictingHttpAndWebUiPortsReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) =
        executeWithArgs(
            listOf(
                "--host",
                "127.0.0.1",
                "--port",
                "9000",
                "--webui",
                "--webui-port",
                "8080",
                "--streamable-http-port",
                "8080",
            ),
        )

    assertEquals(1, exitCode)
    assertTrue(errText.contains("--streamable-http-port must differ from --webui-port"))
  }

  /**
   * --listen-host に空文字列を指定した場合、終了コード 1 でエラーが返る。
   *
   * --listen-host の blank バリデーションを検証する。
   */
  @Test
  fun blankListenHostReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) =
        executeWithArgs(
            listOf(
                "--host",
                "127.0.0.1",
                "--port",
                "9000",
                "--listen-host=",
                "--streamable-http-port",
                "8081",
            ),
        )

    assertEquals(1, exitCode)
    assertTrue(errText.contains("--listen-host requires a non-blank value"))
  }

  /**
   * --streamable-http-port に数値以外を指定した場合、終了コード 1 でエラーが返る。
   *
   * streamable HTTP ポートの数値バリデーションを検証する。
   */
  @Test
  fun nonNumericStreamableHttpPortReturnsOneWithError() = runBlocking {
    val (exitCode, _, errText) =
        executeWithArgs(
            listOf(
                "--host",
                "127.0.0.1",
                "--port",
                "9000",
                "--streamable-http-port",
                "notAPort",
            ),
        )

    assertEquals(1, exitCode)
    assertTrue(errText.contains("Invalid --streamable-http-port value"))
  }

  /**
   * 予期しないトークン（非オプション引数の重複）が渡された場合、終了コード 1 でエラーが返る。
   *
   * スキーマパスが既に設定された後の余分なトークンを検証する。
   */
  @Test
  fun unexpectedTokenReturnsOneWithError() = runBlocking {
    val schemaPath = resolveSchemaPathForTest()
    val (exitCode, _, errText) =
        executeWithArgs(
            listOf(
                schemaPath,
                "extra-token",
                "--host",
                "127.0.0.1",
                "--port",
                "9000",
            ),
        )

    assertEquals(1, exitCode)
    assertTrue(errText.contains("Unexpected token"))
  }

  /**
   * McpAdapter.execute を引数で呼び出し、終了コード・stdout・stderr を返す。
   *
   * @param args コマンドライン引数
   * @return 終了コード、stdout テキスト、stderr テキストの Triple
   */
  private suspend fun executeWithArgs(args: List<String>): Triple<Int, String, String> {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        McpAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode =
        withTimeout(10.seconds) {
          adapter.execute(
              args = args,
              input = ByteArrayInputStream(byteArrayOf()),
              output = ByteArrayOutputStream(),
              transport = NoopTransportForValidation(),
          )
        }

    return Triple(exitCode, outBuffer.toString(), errBuffer.toString())
  }
}

/** テスト用の schema.yaml を見つけて返す。 */
private fun resolveSchemaPathForTest(): String {
  val candidates = listOf(Path.of("schema.yaml"), Path.of("..", "schema.yaml"))
  return candidates.firstOrNull { Files.exists(it) }?.normalize()?.toString()
      ?: error("schema.yaml not found for test")
}

/** 送信を何もしないテスト用トランスポート。 */
private class NoopTransportForValidation : OscTransport {
  override val incomingPackets: Flow<OscPacket> = emptyFlow()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) = Unit
}
