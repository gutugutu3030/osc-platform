package com.oscplatform.codegen

import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.oscSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KotlinCodeGenerator] の生成結果を検証するテスト。
 *
 * 生成されたソースコードに期待する文字列が含まれているかを確認する。
 */
class KotlinCodeGeneratorTest {

  // -------------------------------------------------------------------------
  // 基本: スカラーのみのメッセージ
  // -------------------------------------------------------------------------

  @Test
  fun generateSimpleScalarMessage() {
    val schema = oscSchema {
      message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.example.gen"))

    val path = "com/example/gen/LightColor.kt"
    assertTrue(files.containsKey(path), "ファイル $path が存在すること")

    val content = files[path]!!
    assertContains(content, "package com.example.gen")
    assertContains(content, "data class LightColor(")
    assertContains(content, "val r: Int,")
    assertContains(content, "val g: Int,")
    assertContains(content, "val b: Int,")
    assertContains(content, "override fun toNamedArgs(): Map<String, Any?>")
    assertContains(content, "\"r\" to r,")
    assertContains(content, "\"g\" to g,")
    assertContains(content, "\"b\" to b,")
    assertContains(content, "override val PATH: String = \"/light/color\"")
    assertContains(content, "override val NAME: String = \"light.color\"")
    assertContains(content, "override fun fromNamedArgs(args: Map<String, Any?>): LightColor")
    assertContains(content, "r = args.oscTyped<Int>(\"r\", NAME),")
  }

  // -------------------------------------------------------------------------
  // LENGTH スカラー + スカラー配列
  // -------------------------------------------------------------------------

  @Test
  fun generateMessageWithLengthAndScalarArray() {
    val schema = oscSchema {
      message("/sensor/values") {
        scalar("valueCount", INT, role = LENGTH)
        array("values", lengthFrom = "valueCount") { scalar(FLOAT) }
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.example.gen"))
    val content = files["com/example/gen/SensorValues.kt"]!!

    // LENGTH スカラーはコンストラクタパラメータにならない
    assertFalse(content.contains("val valueCount: Int,"), "LENGTH はコンストラクタに含まれないこと")

    // computed property として存在する
    assertContains(content, "val valueCount: Int get() = values.size")

    // 配列はコンストラクタパラメータ
    assertContains(content, "val values: List<Float>,")

    // toNamedArgs に valueCount が含まれる
    assertContains(content, "\"valueCount\" to valueCount,")

    // fromNamedArgs で型安全ヘルパーを使う
    assertContains(content, "values = args.oscTypedList<Float>(\"values\", NAME),")
  }

  // -------------------------------------------------------------------------
  // タプル配列
  // -------------------------------------------------------------------------

  @Test
  fun generateMessageWithTupleArray() {
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

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.example.gen"))
    val content = files["com/example/gen/MeshPoints.kt"]!!

    // nested data class
    assertContains(content, "data class Point(")
    assertContains(content, "val x: Int,")
    assertContains(content, "val y: Int,")
    assertContains(content, "val z: Float,")

    // 配列はコンストラクタパラメータ (ネスト型)
    assertContains(content, "val points: List<Point>,")

    // toNamedArgs でマップ変換
    assertContains(content, "points.map { mapOf(")
    assertContains(content, "\"x\" to it.x")

    // fromNamedArgs で型安全ヘルパーを使う（@Suppress は生成コードに不要）
    assertFalse(content.contains("@Suppress(\"UNCHECKED_CAST\")"), "@Suppress は生成コードに含まれないこと")
    assertContains(
        content,
        "args.oscTypedMapList(\"points\", NAME).map { m -> Point(",
    )
  }

  // -------------------------------------------------------------------------
  // クラス名変換
  // -------------------------------------------------------------------------

  @Test
  fun toClassNameConvertsNameCorrectly() {
    val gen = KotlinCodeGenerator()
    assert(gen.toClassName("light.color") == "LightColor")
    assert(gen.toClassName("mesh.dual") == "MeshDual")
    assert(gen.toClassName("sensor.values") == "SensorValues")
  }

  // -------------------------------------------------------------------------
  // 複数メッセージで複数ファイルが生成される
  // -------------------------------------------------------------------------

  @Test
  fun generateMultipleMessages() {
    val schema = oscSchema {
      message("/a/b") { scalar("x", INT) }
      message("/c/d") { scalar("y", FLOAT) }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("pkg"))
    assertTrue(files.containsKey("pkg/AB.kt"))
    assertTrue(files.containsKey("pkg/CD.kt"))
  }

  // -------------------------------------------------------------------------
  // バンドルクラスの生成
  // -------------------------------------------------------------------------

  @Test
  fun generateBundleClass() {
    val schema = oscSchema {
      message("/mesh/points") {
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
          tuple {
            field("x", INT)
            field("z", FLOAT)
          }
        }
      }
      message("/device/flag") { scalar("enabled", BOOL) }
      bundle("set_scene") {
        message("mesh.points")
        message("device.flag")
      }
    }

    val files = KotlinCodeGenerator().generate(schema, CodeGenOptions("com.example.gen"))

    // バンドルクラスのファイルが生成される
    val bundlePath = "com/example/gen/SetSceneBundle.kt"
    assertTrue(files.containsKey(bundlePath), "バンドルファイル $bundlePath が存在すること")

    val content = files[bundlePath]!!
    assertContains(content, "import com.oscplatform.core.runtime.OscBundle")
    assertContains(content, "import com.oscplatform.core.runtime.OscBundleCompanion")
    assertContains(content, "data class SetSceneBundle(")
    assertContains(content, "val meshPoints: MeshPoints,")
    assertContains(content, "val deviceFlag: DeviceFlag,")
    assertContains(content, ") : OscBundle {")
    assertContains(content, "override fun toMessages(): List<Pair<String, Map<String, Any?>>>")
    assertContains(content, "MeshPoints.NAME to meshPoints.toNamedArgs(),")
    assertContains(content, "DeviceFlag.NAME to deviceFlag.toNamedArgs(),")
    assertContains(content, "companion object : OscBundleCompanion<SetSceneBundle> {")
    assertContains(content, "override val NAME: String = \"set_scene\"")
  }

  // -------------------------------------------------------------------------
  // バンドルクラス名の導出
  // -------------------------------------------------------------------------

  @Test
  fun toBundleClassNameConvertsNameCorrectly() {
    val gen = KotlinCodeGenerator()
    assert(gen.toBundleClassName("set_scene") == "SetSceneBundle")
    assert(gen.toBundleClassName("light.setup") == "LightSetupBundle")
    assert(gen.toBundleClassName("update-all") == "UpdateAllBundle")
  }
}
