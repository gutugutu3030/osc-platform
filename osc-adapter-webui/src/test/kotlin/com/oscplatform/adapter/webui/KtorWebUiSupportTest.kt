package com.oscplatform.adapter.webui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Web UI 共通 JSON helper の単体テスト。
 *
 * 正常系の JSON 変換と、異常系の JSON 解析エラーを検証する。
 */
class KtorWebUiSupportTest {

  /** Map を JSON 文字列へ変換できることを検証する。 */
  @Test
  fun toWebUiJsonSerializesMap() {
    val json = mapOf("name" to "osc", "port" to 3000).toWebUiJson()

    assertTrue(json.contains("\"name\":\"osc\""))
    assertTrue(json.contains("\"port\":3000"))
  }

  /** HTML 埋め込み向け変換で閉じ script タグがエスケープされることを検証する。 */
  @Test
  fun toWebUiHtmlSafeJsonEscapesClosingScriptTag() {
    val json = mapOf("html" to "</script>").toWebUiHtmlSafeJson()

    assertTrue(json.contains("<\\/script>"))
  }

  /** JSON オブジェクト文字列を Map として読み取れることを検証する。 */
  @Test
  fun parseWebUiJsonObjectParsesMap() {
    val parsed = "{\"success\":true,\"count\":2}".parseWebUiJsonObject()

    assertEquals(true, parsed["success"])
    assertEquals(2, parsed["count"])
  }

  /** 不正な JSON 文字列を読み取った場合に例外が送出されることを検証する。 */
  @Test
  fun parseWebUiJsonObjectThrowsOnInvalidJson() {
    assertFailsWith<Exception> { "{not-json}".parseWebUiJsonObject() }
  }
}
