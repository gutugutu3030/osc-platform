package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YamlSchemaLoaderStructuredArgsTest {
    @Test
  fun loadParsesStructuredArgs() {
        val yaml = """
            messages:
              - path: /mesh/points
                args:
                  - name: pointCount
                    kind: scalar
                    type: int
                    role: length
                  - name: points
                    kind: array
                    lengthFrom: pointCount
                    items:
                      kind: tuple
                      fields:
                        - name: x
                          type: int
                        - name: y
                          type: int
                        - name: z
                          type: float
        """.trimIndent()

        val path = Files.createTempFile("schema-", ".yaml")
        try {
            path.writeText(yaml)
            val schema = YamlSchemaLoader().load(path)
            val spec = assertNotNull(schema.findByPath("/mesh/points"))

            val count = assertIs<ScalarArgNode>(spec.args[0])
            assertEquals("pointCount", count.name)
            assertEquals(OscType.INT, count.type)
            assertEquals(ScalarRole.LENGTH, count.role)

            val points = assertIs<ArrayArgNode>(spec.args[1])
            assertEquals("points", points.name)
            assertEquals("pointCount", (points.length as LengthSpec.FromField).fieldName)

            val tuple = assertIs<ArrayItemSpec.TupleItem>(points.item)
            assertEquals(listOf("x", "y", "z"), tuple.fields.map { it.name })
            assertEquals(listOf(OscType.INT, OscType.INT, OscType.FLOAT), tuple.fields.map { it.type })
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun loadRejectsArrayWithBothLengthAndLengthFrom() {
        val yaml = """
            messages:
              - path: /mesh/points
                args:
                  - name: points
                    kind: array
                    length: 2
                    lengthFrom: pointCount
                    items:
                      kind: scalar
                      type: int
        """.trimIndent()

        val path = Files.createTempFile("schema-invalid-", ".yaml")
        try {
            path.writeText(yaml)
            val ex = assertFailsWith<IllegalArgumentException> {
                YamlSchemaLoader().load(path)
            }
            assertTrue(ex.message?.contains("both length and lengthFrom") == true)
        } finally {
            path.deleteIfExists()
        }
    }
}
