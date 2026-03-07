package com.oscplatform.adapter.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.oscplatform.core.schema.OscMessageSpec
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

class McpSchemaJsonSupportTest {
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

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

    @Test
    fun toInputSchemaAddsMinMaxForFixedLengthArray() {
        val spec = fixedValuesSpec()

        val schemaNode = McpSchemaJsonSupport.toInputSchema(mapper = mapper, spec = spec)

        val valuesSchema = schemaNode.path("properties").path("values")
        assertEquals("array", valuesSchema.path("type").asText())
        assertEquals(3, valuesSchema.path("minItems").asInt())
        assertEquals(3, valuesSchema.path("maxItems").asInt())
    }

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
}
