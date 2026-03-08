package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
