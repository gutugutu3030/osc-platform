package com.oscplatform.codegen

import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.oscSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * [KotlinCodeGenerator] が生成するコードの構造的正当性を検証するテスト。
 *
 * コンパイラを実行する代わりに、生成コードが有効な Kotlin ソースに必要な 構造要素（パッケージ宣言、import、data class、companion object 等）を
 * すべて含んでいるかを検証する。
 */
class KotlinCodeGeneratorCompileTest {

  // -------------------------------------------------------------------------
  // 正常系: 単一メッセージクラスが有効な構造を持つ
  // -------------------------------------------------------------------------

  @Test
  fun generatedMessageClassHasValidStructure() {
    val schema = oscSchema {
      message("/compile/check") {
        scalar("alpha", INT)
        scalar("beta", FLOAT)
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.test.gen"))
    val content = files["com/test/gen/CompileCheck.kt"]!!

    // パッケージ宣言
    assertContains(content, "package com.test.gen")

    // 必須 import
    assertContains(content, "import com.oscplatform.core.runtime.OscMessage")
    assertContains(content, "import com.oscplatform.core.runtime.OscMessageCompanion")
    assertContains(content, "import com.oscplatform.core.runtime.oscTyped")

    // data class 宣言と OscMessage の実装
    assertContains(content, "data class CompileCheck(")
    assertContains(content, ") : OscMessage {")

    // toNamedArgs メソッド
    assertContains(content, "override fun toNamedArgs(): Map<String, Any?>")

    // companion object の構造
    assertContains(content, "companion object : OscMessageCompanion<CompileCheck> {")
    assertContains(content, "override val PATH: String = \"/compile/check\"")
    assertContains(content, "override val NAME: String = \"compile.check\"")

    // fromNamedArgs メソッド
    assertContains(content, "override fun fromNamedArgs(args: Map<String, Any?>): CompileCheck")
  }

  // -------------------------------------------------------------------------
  // 正常系: バンドル + メッセージの両方が整合する構造を持つ
  // -------------------------------------------------------------------------

  @Test
  fun bundleAndMessageClassesAreWellFormed() {
    val schema = oscSchema {
      message("/scene/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      message("/scene/position") {
        scalar("x", FLOAT)
        scalar("y", FLOAT)
      }
      bundle("scene_update") {
        message("scene.color")
        message("scene.position")
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.test.gen"))

    // メッセージクラスの検証
    val colorContent = files["com/test/gen/SceneColor.kt"]!!
    assertContains(colorContent, "data class SceneColor(")
    assertContains(colorContent, ") : OscMessage {")

    val posContent = files["com/test/gen/ScenePosition.kt"]!!
    assertContains(posContent, "data class ScenePosition(")
    assertContains(posContent, ") : OscMessage {")

    // バンドルクラスの検証
    val bundleContent = files["com/test/gen/SceneUpdateBundle.kt"]!!
    assertContains(bundleContent, "package com.test.gen")
    assertContains(bundleContent, "import com.oscplatform.core.runtime.OscBundle")
    assertContains(bundleContent, "import com.oscplatform.core.runtime.OscBundleCompanion")
    assertContains(bundleContent, "data class SceneUpdateBundle(")
    assertContains(bundleContent, "val sceneColor: SceneColor,")
    assertContains(bundleContent, "val scenePosition: ScenePosition,")
    assertContains(bundleContent, ") : OscBundle {")
    assertContains(bundleContent, "companion object : OscBundleCompanion<SceneUpdateBundle> {")
    assertContains(bundleContent, "override val NAME: String = \"scene_update\"")
  }

  // -------------------------------------------------------------------------
  // 正常系: すべての生成ファイルに必須要素が含まれている
  // -------------------------------------------------------------------------

  @Test
  fun allGeneratedFilesHaveRequiredElements() {
    val schema = oscSchema {
      message("/check/first") { scalar("a", INT) }
      message("/check/second") { scalar("b", FLOAT) }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.test.gen"))

    // すべてのファイルにパッケージ宣言、data class、import が含まれること
    files.forEach { (path, content) ->
      assertTrue(
          content.contains("package com.test.gen"),
          "ファイル $path にパッケージ宣言が含まれること",
      )
      assertTrue(
          content.contains("data class "),
          "ファイル $path に data class が含まれること",
      )
      assertTrue(
          content.contains("import "),
          "ファイル $path に import 文が含まれること",
      )
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: タプル配列を含むメッセージの構造が有効
  // -------------------------------------------------------------------------

  @Test
  fun generatedTupleArrayMessageHasValidStructure() {
    val schema = oscSchema {
      message("/struct/data") {
        scalar("count", INT, role = LENGTH)
        array("items", lengthFrom = "count") {
          tuple {
            field("id", INT)
            field("value", FLOAT)
          }
        }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.test.gen"))
    val content = files["com/test/gen/StructData.kt"]!!

    // 追加 import の検証
    assertContains(content, "import com.oscplatform.core.runtime.oscTypedMapList")

    // nested data class の検証
    assertContains(content, "data class Item(")
    assertContains(content, "val id: Int,")
    assertContains(content, "val value: Float,")

    // コンストラクタパラメータでリスト型を使用
    assertContains(content, "val items: List<Item>,")

    // LENGTH の computed property
    assertContains(content, "val count: Int get() = items.size")

    // fromNamedArgs でタプルマッピング
    assertContains(content, "args.oscTypedMapList(\"items\", NAME)")
  }

  // -------------------------------------------------------------------------
  // 正常系: スカラー配列を含むメッセージの構造が有効
  // -------------------------------------------------------------------------

  @Test
  fun generatedScalarArrayMessageHasValidStructure() {
    val schema = oscSchema {
      message("/data/stream") {
        scalar("size", INT, role = LENGTH)
        array("values", lengthFrom = "size") { scalar(FLOAT) }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.test.gen"))
    val content = files["com/test/gen/DataStream.kt"]!!

    // スカラー配列用の import
    assertContains(content, "import com.oscplatform.core.runtime.oscTypedList")

    // コンストラクタパラメータ
    assertContains(content, "val values: List<Float>,")

    // fromNamedArgs で型安全リスト取得
    assertContains(content, "args.oscTypedList<Float>(\"values\", NAME)")
  }
}
