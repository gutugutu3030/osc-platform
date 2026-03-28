package com.oscplatform.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [oscTyped]・[oscTypedList]・[oscTypedMapList] の型安全キャストヘルパーを検証するテスト。
 *
 * 正常系では正しい型の取り出しを確認し、 異常系ではキー不在・型不一致・構造不整合時に [IllegalArgumentException] がスローされることを検証する。
 */
class OscNamedArgsTest {

  // -------------------------------------------------------------------------
  // oscTyped: 正常系
  // -------------------------------------------------------------------------

  /** [oscTyped] が正しい Int 値を返すことを検証する。 */
  @Test
  fun oscTypedReturnsCorrectIntValue() {
    val args: Map<String, Any?> = mapOf("x" to 42)
    val result = args.oscTyped<Int>("x", "TestMessage")
    assertEquals(42, result)
  }

  // -------------------------------------------------------------------------
  // oscTyped: 異常系
  // -------------------------------------------------------------------------

  /** キーが存在しない場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun oscTypedThrowsWhenKeyMissing() {
    val args: Map<String, Any?> = mapOf("y" to 1)

    val ex = assertFailsWith<IllegalArgumentException> { args.oscTyped<Int>("x", "TestMessage") }
    assertTrue(ex.message?.contains("missing") == true, "エラーメッセージに missing を含むべき: ${ex.message}")
  }

  /** 型が一致しない場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun oscTypedThrowsOnTypeMismatch() {
    val args: Map<String, Any?> = mapOf("x" to "not_an_int")

    val ex = assertFailsWith<IllegalArgumentException> { args.oscTyped<Int>("x", "TestMessage") }
    assertTrue(
        ex.message?.contains("Type mismatch") == true,
        "エラーメッセージに Type mismatch を含むべき: ${ex.message}",
    )
  }

  // -------------------------------------------------------------------------
  // oscTypedList: 正常系
  // -------------------------------------------------------------------------

  /** [oscTypedList] が正しい Int リストを返すことを検証する。 */
  @Test
  fun oscTypedListReturnsCorrectList() {
    val args: Map<String, Any?> = mapOf("values" to listOf(1, 2, 3))
    val result = args.oscTypedList<Int>("values", "TestMessage")
    assertEquals(listOf(1, 2, 3), result)
  }

  // -------------------------------------------------------------------------
  // oscTypedList: 異常系
  // -------------------------------------------------------------------------

  /** キーが存在しない場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun oscTypedListThrowsWhenKeyMissing() {
    val args: Map<String, Any?> = emptyMap()

    val ex =
        assertFailsWith<IllegalArgumentException> {
          args.oscTypedList<Int>("values", "TestMessage")
        }
    assertTrue(ex.message?.contains("missing") == true, "エラーメッセージに missing を含むべき: ${ex.message}")
  }

  /** 値が List でなくスカラーの場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun oscTypedListThrowsWhenValueIsNotList() {
    val args: Map<String, Any?> = mapOf("values" to 42)

    val ex =
        assertFailsWith<IllegalArgumentException> {
          args.oscTypedList<Int>("values", "TestMessage")
        }
    assertTrue(
        ex.message?.contains("Expected List") == true,
        "エラーメッセージに Expected List を含むべき: ${ex.message}",
    )
  }

  // -------------------------------------------------------------------------
  // oscTypedMapList: 正常系
  // -------------------------------------------------------------------------

  /** [oscTypedMapList] が正しい Map リストを返すことを検証する。 */
  @Test
  fun oscTypedMapListReturnsCorrectMapList() {
    val maps: List<Map<String, Any?>> = listOf(mapOf("a" to 1), mapOf("b" to 2))
    val args: Map<String, Any?> = mapOf("tuples" to maps)

    val result = args.oscTypedMapList("tuples", "TestMessage")
    assertEquals(2, result.size)
    assertEquals(1, result[0]["a"])
    assertEquals(2, result[1]["b"])
  }

  // -------------------------------------------------------------------------
  // oscTypedMapList: 異常系
  // -------------------------------------------------------------------------

  /** キーが存在しない場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun oscTypedMapListThrowsWhenKeyMissing() {
    val args: Map<String, Any?> = emptyMap()

    val ex =
        assertFailsWith<IllegalArgumentException> { args.oscTypedMapList("tuples", "TestMessage") }
    assertTrue(ex.message?.contains("missing") == true, "エラーメッセージに missing を含むべき: ${ex.message}")
  }

  /** 要素が Map でない場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun oscTypedMapListThrowsWhenElementIsNotMap() {
    val args: Map<String, Any?> = mapOf("tuples" to listOf("not_a_map", "also_not"))

    val ex =
        assertFailsWith<IllegalArgumentException> { args.oscTypedMapList("tuples", "TestMessage") }
    assertTrue(
        ex.message?.contains("not a Map") == true,
        "エラーメッセージに not a Map を含むべき: ${ex.message}",
    )
  }
}
