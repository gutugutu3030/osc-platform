package com.oscplatform.adapter.mcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [McpQueryRouter] のユニットテスト。 */
class McpQueryRouterTest {

    // -------------------------------------------------------------------------
    // 時間依存キーワードの検出
    // -------------------------------------------------------------------------

    @Test
    fun classifyWithTimeKeywordNeedsCurrentTime() {
        val keywords = listOf("最近", "最新", "今", "現在", "直近", "さっき", "先ほど", "いま",
            "今日", "昨日", "今週", "先週", "直近数日", "この前")
        for (keyword in keywords) {
            val result = McpQueryRouter.classify("$keyword の状況を教えて")
            assertTrue(result.needsCurrentTime, "keyword='$keyword' は時間依存と判定されること")
            assertTrue(result.requiresToolCall, "keyword='$keyword' はツール呼び出しが必要と判定されること")
        }
    }

    @Test
    fun classifyWithNoTimeKeywordDoesNotNeedCurrentTime() {
        val result = McpQueryRouter.classify("ライトの色を赤に設定して")
        assertFalse(result.needsCurrentTime, "時間キーワードがなければ needsCurrentTime は false")
    }

    // -------------------------------------------------------------------------
    // 記憶・履歴キーワードの検出
    // -------------------------------------------------------------------------

    @Test
    fun classifyWithMemoryKeywordNeedsMemorySearch() {
        val queries = listOf(
            "最近の思い出を教えて",
            "直近の会話を振り返りたい",
            "以前の出来事を調べて",
            "これまでの履歴を確認したい",
            "過去の行動を振り返って",
            "過去の記録を探して",
            "あの写真はいつ撮ったか教えて",
            "履歴を見せて",
        )
        for (query in queries) {
            val result = McpQueryRouter.classify(query)
            assertTrue(result.needsMemorySearch, "query='$query' は記憶依存と判定されること")
            assertTrue(result.requiresToolCall, "query='$query' はツール呼び出しが必要と判定されること")
        }
    }

    @Test
    fun classifyWithNoMemoryKeywordDoesNotNeedMemorySearch() {
        val result = McpQueryRouter.classify("ライトを点灯して")
        assertFalse(result.needsMemorySearch, "記憶キーワードがなければ needsMemorySearch は false")
    }

    // -------------------------------------------------------------------------
    // 複合ケース
    // -------------------------------------------------------------------------

    @Test
    fun classifyWithBothKeywordsNeedsBoth() {
        val result = McpQueryRouter.classify("最近の思い出を教えて")
        assertTrue(result.needsCurrentTime, "「最近」で needsCurrentTime が true")
        assertTrue(result.needsMemorySearch, "「思い出」で needsMemorySearch が true")
        assertTrue(result.requiresToolCall, "両方必要なので requiresToolCall が true")
    }

    @Test
    fun classifyWithNoKeywordsRequiresNoToolCall() {
        val result = McpQueryRouter.classify("/light/color を 255 128 0 に設定して")
        assertFalse(result.needsCurrentTime)
        assertFalse(result.needsMemorySearch)
        assertFalse(result.requiresToolCall, "キーワードなしはツール呼び出し不要")
    }

    // -------------------------------------------------------------------------
    // プロンプト定数の存在確認
    // -------------------------------------------------------------------------

    @Test
    fun promptNameIsNotBlank() {
        assertTrue(McpQueryRouter.PROMPT_NAME.isNotBlank())
    }

    @Test
    fun promptDescriptionIsNotBlank() {
        assertTrue(McpQueryRouter.PROMPT_DESCRIPTION.isNotBlank())
    }

    @Test
    fun systemPromptTextContainsKeyRoutingRules() {
        val text = McpQueryRouter.systemPromptText
        assertTrue(text.contains("最近"), "プロンプトにキーワード「最近」が含まれること")
        assertTrue(text.contains("記憶"), "プロンプトに「記憶」が含まれること")
        assertTrue(text.contains("ツール"), "プロンプトに「ツール」が含まれること")
        assertTrue(text.contains("禁止"), "プロンプトに禁止事項が含まれること")
    }
}
