package com.oscplatform.core.schema.dsl

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OscSchemaDslStructuredArgsTest {
    @Test
    fun dslBuildsStructuredArgs() {
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

        val spec = assertNotNull(schema.resolveMessage("mesh.points"))
        assertEquals(2, spec.args.size)

        val count = assertIs<ScalarArgNode>(spec.args[0])
        assertEquals("pointCount", count.name)
        assertEquals(OscType.INT, count.type)
        assertEquals(ScalarRole.LENGTH, count.role)

        val points = assertIs<ArrayArgNode>(spec.args[1])
        assertEquals("pointCount", (points.length as LengthSpec.FromField).fieldName)
        val tuple = assertIs<ArrayItemSpec.TupleItem>(points.item)
        assertEquals(listOf("x", "y", "z"), tuple.fields.map { it.name })
        assertEquals(listOf(OscType.INT, OscType.INT, OscType.FLOAT), tuple.fields.map { it.type })
    }

    @Test
    fun argShorthandCreatesScalarValueNodes() {
        val schema = oscSchema {
            message("/light/color") {
                arg("r", INT)
                arg("g", INT)
                arg("b", INT)
            }
        }

        val spec = assertNotNull(schema.resolveMessage("light.color"))
        assertEquals(listOf("r", "g", "b"), spec.args.map { it.name })
        spec.args.forEach { node ->
            val scalar = assertIs<ScalarArgNode>(node)
            assertEquals(ScalarRole.VALUE, scalar.role)
        }
    }
}
