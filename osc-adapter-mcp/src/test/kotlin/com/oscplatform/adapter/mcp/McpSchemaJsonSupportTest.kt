package com.oscplatform.adapter.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.dsl.BLOB
import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.oscSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [McpSchemaJsonSupport] の JSON Schema 生成および JSON ノード変換を検証するテスト。
 *
 * - [McpSchemaJsonSupport.toInputSchema]: OscMessageSpec → JSON Schema 変換
 *   - 配列・タプル型、BOOL・BLOB 型、導出長さフィールドの optional 化
 * - [McpSchemaJsonSupport.jsonNodeToValue]: Jackson JsonNode → Kotlin ネイティブ⋮
 */
class McpSchemaJsonSupportTest {
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    // -------------------------------------------------------------------------
    // toInputSchema: 配列・タプル横断
    // -------------------------------------------------------------------------

    /** 導出長さフィールドは required から除外され、{type:array, items:{type:object}} に展開される */
    @Test
    fun toInputSchemaMarksDerivedLengthFieldOptionalAndBuildsTupleSchema() {
        val spec = meshPointsSpec()

        val schemaNode = McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)

        val required = schemaNode.path("required")
        val requiredNames = required.map { it.asText() }.toSet()
        assertTrue(requiredNames.contains("points"))
        assertFalse(requiredNames.contains("pointCount"))

        val pointsSchema = schemaNode.path("properties").path("points")
        assertEquals("array", pointsSchema.path("type").asText())
        assertEquals("pointCount", pointsSchema.path("x-osc-lengthFrom").asText())

        val tupleSchema = pointsSchema.path("items")
        assertEquals("object", tupleSchema.path("type").asText())

        val tupleRequired = tupleSchema.path("required").map { it.asText() }.toSet()
        assertEquals(setOf("x", "y", "z"), tupleRequired)
        assertEquals("integer", tupleSchema.path("properties").path("x").path("type").asText())
        assertEquals("number", tupleSchema.path("properties").path("z").path("type").asText())
    }

    /** Fixed 長さの配列には minItems / maxItems が設定される */
    @Test
    fun toInputSchemaAddsMinMaxForFixedLengthArray() {
        val spec = fixedValuesSpec()

        val schemaNode = McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)

        val valuesSchema = schemaNode.path("properties").path("values")
        assertEquals("array", valuesSchema.path("type").asText())
        assertEquals(3, valuesSchema.path("minItems").asInt())
        assertEquals(3, valuesSchema.path("maxItems").asInt())
    }

    // -------------------------------------------------------------------------
    // jsonNodeToValue: 再帰変換
    // -------------------------------------------------------------------------

    /** ネストした object/array が Kotlin Map/List に変換される */
    @Test
    fun jsonNodeToValueConvertsNestedObjectArray() {
        val node = mapper.readTree("""
            {
                            "name": "mesh",
                            "points": [{"x":1,"y":2,"z":3.25}],
                            "enabled": true,
                            "nullable": null
            }
        """.trimIndent())

        val value = McpSchemaJsonSupport.jsonNodeToValue(node)

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

    private fun meshPointsSpec(): OscMessageSpec {
        val schema = oscSchema {
            message("/mesh/points") {
                scalar("pointCount", INT, role = LENGTH)
                array("points", lengthFrom = "pointCount") {
                    tuple {
                        field("x", INT)
                        field("y", INT)
                        field("z", FLOAT)
                    }
                }
            }
        }
        return requireNotNull(schema.findByPath("/mesh/points"))
    }

    private fun fixedValuesSpec(): OscMessageSpec {
        val schema = oscSchema {
            message("/sensor/values") {
                array("values", length = 3) {
                    scalar(INT)
                }
            }
        }
        return requireNotNull(schema.findByPath("/sensor/values"))
    }

    // -------------------------------------------------------------------------
    // toInputSchema: BOOL / BLOB 型
    // -------------------------------------------------------------------------

    /** BOOL → {"type":"boolean"} */
    @Test
    fun boolArgGeneratesBooleanSchema() {
        val schema = oscSchema {
            message("/device/flag") {
                scalar("enabled", BOOL)
            }
        }
        val spec = requireNotNull(schema.findByPath("/device/flag"))

        val schemaNode = McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)

        val enabledSchema = schemaNode.path("properties").path("enabled")
        assertEquals("boolean", enabledSchema.path("type").asText())
        // required に含まれること
        val required = schemaNode.path("required").map { it.asText() }.toSet()
        assertTrue(required.contains("enabled"))
    }

    /** BLOB → {"type":"string", "contentEncoding":"base64"} */
    @Test
    fun blobArgGeneratesStringWithBase64Schema() {
        val schema = oscSchema {
            message("/device/data") {
                scalar("payload", BLOB)
            }
        }
        val spec = requireNotNull(schema.findByPath("/device/data"))

        val schemaNode = McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)

        val payloadSchema = schemaNode.path("properties").path("payload")
        assertEquals("string", payloadSchema.path("type").asText())
        assertEquals("base64", payloadSchema.path("contentEncoding").asText())
        val required = schemaNode.path("required").map { it.asText() }.toSet()
        assertTrue(required.contains("payload"))
    }

    /** BOOL 配列の items も {"type":"boolean"} に展開される */
    @Test
    fun boolInArrayItemsGeneratesBooleanItemSchema() {
        val schema = oscSchema {
            message("/flags/all") {
                array("flags", length = 4) {
                    scalar(BOOL)
                }
            }
        }
        val spec = requireNotNull(schema.findByPath("/flags/all"))

        val schemaNode = McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)

        val itemsSchema = schemaNode.path("properties").path("flags").path("items")
        assertEquals("boolean", itemsSchema.path("type").asText())
    }
}
