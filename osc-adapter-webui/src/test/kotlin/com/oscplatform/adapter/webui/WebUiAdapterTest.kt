package com.oscplatform.adapter.webui

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [WebUiAdapter] のコマンドルーティングとオプション解析を検証するテスト。
 *
 * 検証内容:
 * - help コマンドはコード 0 で終了し、使い方を stdout に出力する
 * - 未知オプションはコード 1 で終了し、エラーを stderr に出力する
 * - commandSummary は "osc webui" を含む
 */
class WebUiAdapterTest {

  @Test
  fun commandSummaryContainsWebuiKeyword() {
    val adapter = WebUiAdapter()
    assertTrue(adapter.commandSummary().contains("osc webui"))
  }

  @Test
  fun helpReturnsZeroAndPrintsUsage() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter = WebUiAdapter(out = PrintStream(outBuffer), err = PrintStream(errBuffer))

    val exitCode = runBlocking { adapter.execute(listOf("--help")) }

    assertEquals(0, exitCode)
    assertTrue(outBuffer.toString().contains("osc webui"))
    assertEquals("", errBuffer.toString())
  }

  @Test
  fun unknownOptionReturnsOneAndPrintsError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter = WebUiAdapter(out = PrintStream(outBuffer), err = PrintStream(errBuffer))

    val exitCode = runBlocking { adapter.execute(listOf("--bogus")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Unknown option for webui: --bogus"))
  }
}
