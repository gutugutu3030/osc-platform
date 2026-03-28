package com.oscplatform.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [buildTopLevelUsage] の出力内容を検証するテスト。
 *
 * `main()` は `exitProcess()` を呼ぶため直接テストできない。 そのため、内部可視の [buildTopLevelUsage] を通じてコマンド一覧の
 * 正しさ・順序・重複の有無を検証する。
 */
class MainDispatchTest {

  /**
   * [buildTopLevelUsage] が期待されるすべてのコマンドを正しい順序で含むことを検証する。
   *
   * CLI アダプターのコマンド群 → MCP → WebUI → --version → help の順に並ぶはずである。
   */
  @Test
  fun buildTopLevelUsageContainsAllExpectedCommandsInOrder() {
    val usage = buildTopLevelUsage()

    // 期待順に並ぶキーワードリスト
    val expectedOrder =
        listOf(
            "osc run",
            "osc list",
            "osc validate",
            "osc gen",
            "osc mcp",
            "osc webui",
            "osc --version",
            "osc help")

    // 各キーワードのインデックスを取得し、昇順であることを確認する
    val indices =
        expectedOrder.map { keyword ->
          val idx = usage.indexOf(keyword)
          assertTrue(idx >= 0, "Usage に '$keyword' が含まれていない")
          idx
        }

    assertEquals(indices, indices.sorted(), "コマンドが期待順に並んでいない: $indices")
  }

  /** [buildTopLevelUsage] の出力に重複行が無いことを検証する。 */
  @Test
  fun buildTopLevelUsageDoesNotContainDuplicateLines() {
    val usage = buildTopLevelUsage()
    val lines = usage.lines().filter { it.isNotBlank() }

    // 重複行が存在しないことを確認する
    val duplicates = lines.groupBy { it }.filter { it.value.size > 1 }.keys
    assertTrue(duplicates.isEmpty(), "重複行が存在する: $duplicates")
  }

  /** [buildTopLevelUsage] の最終行が "osc help" であることを検証する。 */
  @Test
  fun buildTopLevelUsageEndsWithOscHelp() {
    val usage = buildTopLevelUsage()
    val lastLine = usage.trimEnd().lines().last()

    assertEquals("osc help", lastLine, "最終行が 'osc help' ではない: '$lastLine'")
  }

  /** [buildTopLevelUsage] に "osc --version" が含まれることを検証する。 */
  @Test
  fun buildTopLevelUsageIncludesVersionFlag() {
    val usage = buildTopLevelUsage()

    assertTrue(usage.contains("osc --version"), "Usage に 'osc --version' が含まれていない")
  }

  /**
   * ヘルプ・バージョン・MCP・WebUI・CLI アダプターの各コマンドが期待パターンに合致することを検証する。
   *
   * 各行が "osc " で始まるか "--" オプション形式を含むことを確認する。
   */
  @Test
  fun buildTopLevelUsageMatchesExpectedPatterns() {
    val usage = buildTopLevelUsage()
    val lines = usage.lines().filter { it.isNotBlank() }

    // すべての行が "osc " で始まるか、"--" を含むオプション形式であること
    lines.forEach { line ->
      val trimmed = line.trimStart()
      assertTrue(
          trimmed.startsWith("osc ") || trimmed.contains("--"),
          "行が期待パターン ('osc ...' または '--...') に合致しない: '$trimmed'",
      )
    }

    // 既知のアダプターコマンドが含まれていることを確認する
    assertFalse(lines.isEmpty(), "Usage が空である")
    assertTrue(lines.any { it.contains("mcp") }, "MCP コマンドが見つからない")
    assertTrue(lines.any { it.contains("webui") }, "WebUI コマンドが見つからない")
    assertTrue(lines.any { it.contains("run") }, "run コマンドが見つからない")
    assertTrue(lines.any { it.contains("version") }, "version 関連が見つからない")
    assertTrue(lines.any { it.contains("help") }, "help コマンドが見つからない")
  }
}
