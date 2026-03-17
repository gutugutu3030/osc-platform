package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [CliAdapter.execute] のコマンドルーティングを検証するテスト。
 *
 * 検証内容:
 * - help コマンドはコード 0 で終了し、使い方を stdout に出力する
 * - 不明コマンドはコード 1 で終了し、エラーを stderr に出力する
 * - 引数なしはコード 1 で終了し、使い方を stdout に出力する
 */
class CliAdapterExecuteTest {

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** help コマンド: 戻り値 0, "osc run" を含む使い方を stdout に出力 */
  @Test
  fun executeHelpReturnsZeroAndPrintsUsage() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("help")) }

    assertEquals(0, exitCode)
    assertTrue(outBuffer.toString().contains("osc run"))
    assertTrue(outBuffer.toString().contains("osc doc"))
    assertTrue(outBuffer.toString().contains("osc list"))
    assertTrue(outBuffer.toString().contains("osc validate"))
    assertTrue(outBuffer.toString().contains("osc gen"))
    assertTrue(outBuffer.toString().contains("osc version"))
    assertEquals("", errBuffer.toString())
  }

  /** run --help: 戻り値 0, run 用の使い方を stdout に出力し stderr は空 */
  @Test
  fun executeRunHelpReturnsZeroAndPrintsRunUsage() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("run", "--help")) }

    assertEquals(0, exitCode)
    assertTrue(outBuffer.toString().contains("osc run"))
    assertFalse(outBuffer.toString().contains("osc send"))
    assertEquals("", errBuffer.toString())
  }

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  /** 不明コマンド: 戻り値 1, "Unknown command" を stderr に出力し使い方も出力 */
  @Test
  fun executeUnknownCommandReturnsOneAndPrintsError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("unknown")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Unknown command"))
    assertTrue(outBuffer.toString().contains("osc run"))
  }

  /** 引数なし: 戻り値 1, "osc send" を含む使い方を stdout に出力、stderr は空 */
  @Test
  fun executeWithoutArgsReturnsOneAndPrintsUsage() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(emptyList()) }

    assertEquals(1, exitCode)
    assertTrue(outBuffer.toString().contains("osc send"))
    assertEquals("", errBuffer.toString())
  }

  /** send 単独実行: 戻り値 1, 整形されたエラーを stderr に出力し send の使い方を stdout に出力 */
  @Test
  fun executeSendWithoutMessageRefReturnsOneAndPrintsFormattedError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("send")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("error: send command needs message ref"))
    assertTrue(outBuffer.toString().contains("osc send <messageRef>"))
  }

  /** run の未知オプション: 戻り値 1, 整形されたエラーを stderr に出力し run の使い方を stdout に出力 */
  @Test
  fun executeRunWithUnknownOptionReturnsOneAndPrintsFormattedError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("run", "--bogus")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("error: Unknown option for run: --bogus"))
    assertTrue(outBuffer.toString().contains("osc run"))
  }

  /** version: 戻り値 0, バージョン文字列を stdout に出力 */
  @Test
  fun executeVersionReturnsZeroAndPrintsVersion() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("version")) }

    assertEquals(0, exitCode)
    assertTrue(outBuffer.toString().contains("osc-platform"))
    assertEquals("", errBuffer.toString())
  }
}
