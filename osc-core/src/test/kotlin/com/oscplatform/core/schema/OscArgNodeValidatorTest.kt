package com.oscplatform.core.schema

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OscArgNodeValidatorTest {
    @Test
    fun validateAcceptsLengthReferencedTupleArray() {
        val args = listOf(
            ScalarArgNode(name = "pointCount", type = OscType.INT, role = ScalarRole.LENGTH),
            ArrayArgNode(
                name = "points",
                length = LengthSpec.FromField("pointCount"),
                item = ArrayItemSpec.TupleItem(
                    fields = listOf(
                        TupleFieldSpec("x", OscType.INT),
                        TupleFieldSpec("y", OscType.INT),
                        TupleFieldSpec("z", OscType.FLOAT),
                    ),
                ),
            ),
        )

        OscArgNodeValidator.validate(path = "/mesh/points", args = args)
    }

    @Test
    fun validateRejectsLengthReferenceToNonLengthScalar() {
        val ex = assertFailsWith<IllegalArgumentException> {
            OscArgNodeValidator.validate(
                path = "/mesh/points",
                args = listOf(
                    ScalarArgNode(name = "pointCount", type = OscType.INT, role = ScalarRole.VALUE),
                    ArrayArgNode(
                        name = "points",
                        length = LengthSpec.FromField("pointCount"),
                        item = ArrayItemSpec.ScalarItem(OscType.INT),
                    ),
                ),
            )
        }

        assertTrue(ex.message?.contains("role=LENGTH") == true)
    }

    @Test
    fun validateRejectsDuplicateTupleFieldNames() {
        val ex = assertFailsWith<IllegalArgumentException> {
            OscArgNodeValidator.validate(
                path = "/mesh/points",
                args = listOf(
                    ScalarArgNode(name = "pointCount", type = OscType.INT, role = ScalarRole.LENGTH),
                    ArrayArgNode(
                        name = "points",
                        length = LengthSpec.FromField("pointCount"),
                        item = ArrayItemSpec.TupleItem(
                            fields = listOf(
                                TupleFieldSpec("x", OscType.INT),
                                TupleFieldSpec("x", OscType.FLOAT),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertTrue(ex.message?.contains("Duplicate tuple field names") == true)
    }

    @Test
    fun validateRejectsNegativeFixedLength() {
        val ex = assertFailsWith<IllegalArgumentException> {
            OscArgNodeValidator.validate(
                path = "/mesh/points",
                args = listOf(
                    ArrayArgNode(
                        name = "points",
                        length = LengthSpec.Fixed(-1),
                        item = ArrayItemSpec.ScalarItem(OscType.INT),
                    ),
                ),
            )
        }

        assertTrue(ex.message?.contains("must be >= 0") == true)
    }
}
