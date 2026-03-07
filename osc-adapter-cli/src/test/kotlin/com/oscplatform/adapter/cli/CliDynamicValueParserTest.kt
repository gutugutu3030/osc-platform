package com.oscplatform.adapter.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CliDynamicValueParser] の引数値パースを検証するテスト。
 *
 * CLI から渡される文字列は次のルールで変換される:
 * - JSON 配列 "[...]" またはオブジェクト "{...}" 形式 → Kotlin List / Map
 * - それ以外の値は生文字列としてそのまま扱う（OscRuntime 側の convertToType に委譲）
 * - 不正 JSON は IllegalArgumentException で拒否する
 */
class CliDynamicValueParserTest {

    // -------------------------------------------------------------------------
    // 正常系
    // -------------------------------------------------------------------------

    /** 数字列は「数字」でなく Raw 文字列として返り、型変換は OscRuntime 側で行う */
    @Test
    fun parseReturnsRawStringWhenNotJsonLiteral() {
        val value: Any? = CliDynamicValueParser.parse("123")
        assertEquals("123", value)
    }

    /** JSON 配列およびオブジェクトが再帰的に Kotlin ネイティブ型に変換される */
    @Test
    fun parseConvertsJsonArrayAndObjectRecursively() {
        val raw = """[{"x":1,"y":2,"z":3.5}, {"x":4,"y":5,"z":null}]"""
        val parsed: Any? = CliDynamicValueParser.parse(raw)

        val list = assertIs<List<*>>(parsed)
        assertEquals(2, list.size)

        val first = assertIs<Map<*, *>>(list[0])
        assertEquals(1, first["x"])
        assertEquals(2, first["y"])
        assertEquals(3.5, first["z"])

        val second = assertIs<Map<*, *>>(list[1])
        assertEquals(4, second["x"])
        assertEquals(5, second["y"])
        assertNull(second["z"])
    }

    // -------------------------------------------------------------------------
    // 異常系
    // -------------------------------------------------------------------------

    /** JSON のブラケットが不小などの不正形式は拒否される */
    @Test
    fun parseThrowsOnInvalidJsonLiteral() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CliDynamicValueParser.parse("[{\"x\":1}")
        }

        assertTrue(ex.message?.contains("Invalid JSON argument value") == true)
    }
}
