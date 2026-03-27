package com.oscplatform.adapter.webui

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [SchemaEditorAdapter] のコマンドルーティングとオプション解析を検証するテスト。
 *
 * 検証内容:
 * - help コマンドはコード 0 で終了し、使い方を stdout に出力する
 * - 未知オプションはコード 1 で終了し、エラーを stderr に出力する
 * - commandSummary は "osc editor" を含む
 */
class SchemaEditorAdapterTest {

  /** commandSummary が "osc editor" を含むことを確認する。 */
  @Test
  fun commandSummaryContainsEditorKeyword() {
    val adapter = SchemaEditorAdapter()
    assertTrue(adapter.commandSummary().contains("osc editor"))
  }

  /** --help フラグでコード 0 が返り、使用方法が stdout に出力されることを確認する。 */
  @Test
  fun helpReturnsZeroAndPrintsUsage() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter = SchemaEditorAdapter(out = PrintStream(outBuffer), err = PrintStream(errBuffer))

    val exitCode = runBlocking { adapter.execute(listOf("--help")) }

    assertEquals(0, exitCode)
    assertTrue(outBuffer.toString().contains("osc editor"))
    assertEquals("", errBuffer.toString())
  }

  /** 未知のオプションが指定された場合にコード 1 が返りエラーメッセージが出力されることを確認する。 */
  @Test
  fun unknownOptionReturnsOneAndPrintsError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter = SchemaEditorAdapter(out = PrintStream(outBuffer), err = PrintStream(errBuffer))

    val exitCode = runBlocking { adapter.execute(listOf("--bogus")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Unknown option for editor: --bogus"))
  }

  /** --port に無効な値が指定された場合にコード 1 が返ることを確認する。 */
  @Test
  fun invalidPortReturnsOne() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter = SchemaEditorAdapter(out = PrintStream(outBuffer), err = PrintStream(errBuffer))

    val exitCode = runBlocking { adapter.execute(listOf("--port", "abc")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Invalid --port value"))
  }

  /** --port に値がない場合にコード 1 が返ることを確認する。 */
  @Test
  fun portWithoutValueReturnsOne() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter = SchemaEditorAdapter(out = PrintStream(outBuffer), err = PrintStream(errBuffer))

    val exitCode = runBlocking { adapter.execute(listOf("--port")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("--port requires a value"))
  }
}
