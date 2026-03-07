package com.oscplatform.adapter.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliDynamicValueParserTest {
    @Test
    fun parseReturnsRawStringWhenNotJsonLiteral() {
        val value: Any? = CliDynamicValueParser.parse("123")
        assertEquals("123", value)
    }

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

    @Test
    fun parseThrowsOnInvalidJsonLiteral() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CliDynamicValueParser.parse("[{\"x\":1}")
        }

        assertTrue(ex.message?.contains("Invalid JSON argument value") == true)
    }
}
