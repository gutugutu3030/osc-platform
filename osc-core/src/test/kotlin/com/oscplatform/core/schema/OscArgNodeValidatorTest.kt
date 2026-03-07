package com.oscplatform.core.schema

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [OscArgNodeValidator] のバリデーションルールを検証するテスト。
 *
 * 検証内容:
 * - lengthFrom 参照先が role=LENGTH の ScalarArgNode であること
 * - タプルフィールド名の重複がないこと
 * - Fixed 長さが 0 以上であること
 */
class OscArgNodeValidatorTest {

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** lengthFrom 参照先が role=LENGTH 策定されている正常パターン */
  @Test
  fun validateAcceptsLengthReferencedTupleArray() {
    val args =
        listOf(
            ScalarArgNode(name = "pointCount", type = OscType.INT, role = ScalarRole.LENGTH),
            ArrayArgNode(
                name = "points",
                length = LengthSpec.FromField("pointCount"),
                item =
                    ArrayItemSpec.TupleItem(
                        fields =
                            listOf(
                                TupleFieldSpec("x", OscType.INT),
                                TupleFieldSpec("y", OscType.INT),
                                TupleFieldSpec("z", OscType.FLOAT),
                            ),
                    ),
            ),
        )

    OscArgNodeValidator.validate(path = "/mesh/points", args = args)
  }

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  /** lengthFrom が指すフィールドが role=VALUE のため拒否される */
  @Test
  fun validateRejectsLengthReferenceToNonLengthScalar() {
    val ex =
        assertFailsWith<IllegalArgumentException> {
          OscArgNodeValidator.validate(
              path = "/mesh/points",
              args =
                  listOf(
                      ScalarArgNode(
                          name = "pointCount", type = OscType.INT, role = ScalarRole.VALUE),
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

  /** タプル内で同名のフィールドが存在するため拒否される */
  @Test
  fun validateRejectsDuplicateTupleFieldNames() {
    val ex =
        assertFailsWith<IllegalArgumentException> {
          OscArgNodeValidator.validate(
              path = "/mesh/points",
              args =
                  listOf(
                      ScalarArgNode(
                          name = "pointCount", type = OscType.INT, role = ScalarRole.LENGTH),
                      ArrayArgNode(
                          name = "points",
                          length = LengthSpec.FromField("pointCount"),
                          item =
                              ArrayItemSpec.TupleItem(
                                  fields =
                                      listOf(
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

  /** Fixed 長さに負数を指定したため拒否される */
  @Test
  fun validateRejectsNegativeFixedLength() {
    val ex =
        assertFailsWith<IllegalArgumentException> {
          OscArgNodeValidator.validate(
              path = "/mesh/points",
              args =
                  listOf(
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
