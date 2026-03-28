package com.oscplatform.codegen

import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.STRING
import com.oscplatform.core.schema.dsl.oscSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 生成コードの契約パターンを検証するテスト。
 *
 * `toNamedArgs` / `fromNamedArgs` が正しいマッピングパターンを生成し、 サポート外の言語指定時にエラーが返ることを確認する。
 */
class GeneratedContractTest {

  // -------------------------------------------------------------------------
  // 正常系: toNamedArgs が全引数を正しくマッピングする
  // -------------------------------------------------------------------------

  @Test
  fun toNamedArgsMapsAllScalarArgsCorrectly() {
    val schema = oscSchema {
      message("/contract/scalar") {
        scalar("intVal", INT)
        scalar("floatVal", FLOAT)
        scalar("strVal", STRING)
        scalar("boolVal", BOOL)
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/ContractScalar.kt"]!!

    // toNamedArgs に各フィールドが含まれること
    assertContains(content, "\"intVal\" to intVal,")
    assertContains(content, "\"floatVal\" to floatVal,")
    assertContains(content, "\"strVal\" to strVal,")
    assertContains(content, "\"boolVal\" to boolVal,")
  }

  @Test
  fun toNamedArgsMapsScalarArrayCorrectly() {
    val schema = oscSchema {
      message("/contract/arr") {
        scalar("len", INT, role = LENGTH)
        array("data", lengthFrom = "len") { scalar(FLOAT) }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/ContractArr.kt"]!!

    // LENGTH スカラーも toNamedArgs に含まれる
    assertContains(content, "\"len\" to len,")
    // 配列フィールドがそのままマッピングされる
    assertContains(content, "\"data\" to data,")
  }

  @Test
  fun toNamedArgsMapsTupleArrayWithMapConversion() {
    val schema = oscSchema {
      message("/contract/tuple") {
        scalar("n", INT, role = LENGTH)
        array("entries", lengthFrom = "n") {
          tuple {
            field("key", STRING)
            field("value", INT)
          }
        }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/ContractTuple.kt"]!!

    // タプル配列は map { mapOf(...) } パターンで変換される
    assertContains(content, "entries.map { mapOf(")
    assertContains(content, "\"key\" to it.key")
    assertContains(content, "\"value\" to it.value")
  }

  // -------------------------------------------------------------------------
  // 正常系: fromNamedArgs が正しい型安全ヘルパーを使用する
  // -------------------------------------------------------------------------

  @Test
  fun fromNamedArgsUsesOscTypedForScalars() {
    val schema = oscSchema {
      message("/contract/typed") {
        scalar("i", INT)
        scalar("f", FLOAT)
        scalar("s", STRING)
        scalar("b", BOOL)
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/ContractTyped.kt"]!!

    // 各型が oscTyped で正しく取得される
    assertContains(content, "i = args.oscTyped<Int>(\"i\", NAME),")
    assertContains(content, "f = args.oscTyped<Float>(\"f\", NAME),")
    assertContains(content, "s = args.oscTyped<String>(\"s\", NAME),")
    assertContains(content, "b = args.oscTyped<Boolean>(\"b\", NAME),")
  }

  @Test
  fun fromNamedArgsUsesOscTypedListForScalarArrays() {
    val schema = oscSchema {
      message("/contract/list") {
        scalar("count", INT, role = LENGTH)
        array("values", lengthFrom = "count") { scalar(INT) }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/ContractList.kt"]!!

    // スカラー配列は oscTypedList で取得される
    assertContains(content, "values = args.oscTypedList<Int>(\"values\", NAME),")
  }

  @Test
  fun fromNamedArgsUsesOscTypedMapListForTupleArrays() {
    val schema = oscSchema {
      message("/contract/maps") {
        scalar("n", INT, role = LENGTH)
        array("points", lengthFrom = "n") {
          tuple {
            field("x", FLOAT)
            field("y", FLOAT)
          }
        }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/ContractMaps.kt"]!!

    // タプル配列は oscTypedMapList で取得される
    assertContains(content, "args.oscTypedMapList(\"points\", NAME)")
    // ネスト型のフィールドマッピング
    assertContains(content, "x = m.oscTyped<Float>(\"x\", NAME)")
    assertContains(content, "y = m.oscTyped<Float>(\"y\", NAME)")
  }

  // -------------------------------------------------------------------------
  // 異常系: ファサード経由で未サポート言語を指定
  // -------------------------------------------------------------------------

  @Test
  fun facadeRejectsUnsupportedLanguage() {
    val yamlContent =
        """
            messages:
              - path: "/reject/lang"
                args:
                  - name: "v"
                    type: "INT"
            """
            .trimIndent()

    val tempFile = java.nio.file.Files.createTempFile("osc-contract-test-", ".yaml")
    try {
      java.nio.file.Files.writeString(tempFile, yamlContent)
      val options = CodeGenOptions(packageName = "com.contract", language = "java")

      // 未サポート言語は IllegalStateException になること
      val ex =
          assertFailsWith<IllegalStateException> { OscCodegen.generateFromFile(tempFile, options) }
      assertTrue(
          ex.message!!.contains("java"),
          "エラーメッセージに指定言語 'java' が含まれること",
      )
      assertTrue(
          ex.message!!.contains("Unsupported"),
          "エラーメッセージに 'Unsupported' が含まれること",
      )
    } finally {
      java.nio.file.Files.deleteIfExists(tempFile)
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: companion object が PATH と NAME を正しく保持する
  // -------------------------------------------------------------------------

  @Test
  fun companionObjectContainsCorrectPathAndName() {
    val schema = oscSchema { message("/my/special/path") { scalar("v", INT) } }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.contract"))
    val content = files["com/contract/MySpecialPath.kt"]!!

    assertContains(content, "override val PATH: String = \"/my/special/path\"")
    assertContains(content, "override val NAME: String = \"my.special.path\"")
  }
}
