package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [String.wrapOscSchemaScript] と [Any?.requireOscSchema] の単体テスト。
 *
 * schema.kts 評価前処理の正常系と、評価結果型が不正な場合の異常系を検証する。
 */
class OscSchemaScriptSupportTest {

  /** DSL 本文をラップした結果の先頭に import 行が 1 回だけ付与されることを検証する。 */
  @Test
  fun wrapOscSchemaScriptPrependsDslImport() {
    val source = "oscSchema {\n    message(\"/test/path\") { }\n}"

    val wrapped = source.wrapOscSchemaScript()

    assertTrue(wrapped.startsWith("$OSC_SCHEMA_DSL_IMPORT\n"))
    assertTrue(wrapped.contains(source))
  }

  /** 評価結果が [OscSchema] の場合は同じインスタンスを返すことを検証する。 */
  @Test
  fun requireOscSchemaReturnsSchemaInstance() {
    val schema =
        OscSchema(
            messages =
                listOf(
                    OscMessageSpec(
                        path = "/test/path",
                        name = "test.path",
                        description = null,
                        args = emptyList(),
                    ),
                ),
        )

    val result = schema.requireOscSchema()

    assertSame(schema, result)
  }

  /** 評価結果が [OscSchema] 以外の場合は [IllegalStateException] を送出することを検証する。 */
  @Test
  fun requireOscSchemaThrowsWhenResultIsNotSchema() {
    try {
      42.requireOscSchema()
      fail("OscSchema 以外の評価結果は例外を送出するべき")
    } catch (e: IllegalStateException) {
      assertEquals(
          "Schema script must evaluate to OscSchema. Example: oscSchema { ... }",
          e.message,
      )
    }
  }
}
