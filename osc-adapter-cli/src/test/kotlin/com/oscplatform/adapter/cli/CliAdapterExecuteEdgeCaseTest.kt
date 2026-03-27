package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [CliAdapter.execute] のエッジケースを検証する補足テスト。
 *
 * [CliAdapterExecuteTest] を補完し、各コマンド固有の異常系を検証する。
 *
 * 検証内容:
 * - doc --format bogus で不正フォーマットエラーが発生する
 * - gen --bogus で不明オプションエラーが発生する
 * - send --port abc で不正ポート値エラーが発生する
 */
class CliAdapterExecuteEdgeCaseTest {

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  /** doc --format bogus: 戻り値 1, "Unsupported --format" を含むエラーが stderr に出力される */
  @Test
  fun docCommandWithUnknownFormatReturnsOneWithError() {
    val schemaFile = Files.createTempFile("osc-edge-schema-", ".yaml")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(
          schemaFile,
          """
          messages:
            - path: /test/msg
              args:
                - name: x
                  kind: scalar
                  type: int
          """
              .trimIndent(),
      )
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode: Int = runBlocking {
        adapter.execute(
            listOf(
                "doc",
                "--schema",
                schemaFile.toAbsolutePath().toString(),
                "--format",
                "bogus",
            ),
        )
      }

      assertEquals(1, exitCode)
      assertTrue(errBuffer.toString().contains("Unsupported --format"))
    } finally {
      schemaFile.deleteIfExists()
    }
  }

  /** gen --bogus: 戻り値 1, "Unknown option for gen" を含むエラーが stderr に出力される */
  @Test
  fun genCommandWithBogusOptionReturnsOneWithError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("gen", "--bogus")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Unknown option for gen"))
  }

  /** send light.color --port abc: 戻り値 1, "Invalid --port" を含むエラーが stderr に出力される */
  @Test
  fun sendCommandWithNonNumericPortReturnsOneWithError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking {
      adapter.execute(listOf("send", "light.color", "--port", "abc"))
    }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Invalid --port"))
  }
}
