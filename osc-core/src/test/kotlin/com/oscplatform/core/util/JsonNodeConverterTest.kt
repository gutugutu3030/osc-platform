package com.oscplatform.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** [toKotlinValue] が JsonNode を Kotlin のネイティブ型へ再帰変換することを検証するテスト。 */
class JsonNodeConverterTest {
  private val mapper: ObjectMapper =
      JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

  /** ネストした object/array を Map/List に再帰変換する。 */
  @Test
  fun toKotlinValueConvertsNestedObjectArray() {
    val node =
        mapper.readTree(
            """
            {
              "name": "mesh",
              "points": [{"x":1,"y":2,"z":3.25}],
              "enabled": true,
              "nullable": null
            }
            """
                .trimIndent())

    val value = node.toKotlinValue()

    val map = assertIs<Map<*, *>>(value)
    assertEquals("mesh", map["name"])
    assertEquals(true, map["enabled"])
    assertNull(map["nullable"])

    val points = assertIs<List<*>>(map["points"])
    val first = assertIs<Map<*, *>>(points.first())
    assertEquals(1, first["x"])
    assertEquals(2, first["y"])
    assertEquals(3.25, first["z"])
  }
}
