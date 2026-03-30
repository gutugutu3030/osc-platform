package com.oscplatform.codegen

import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.oscSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KotlinCodeGenerator] の sealed interface 生成機能を検証するテスト。
 *
 * sealed interface 指定時の生成コード構造、既存 API との互換性、 未指定時の後方互換動作を検証する。
 */
class KotlinCodeGeneratorSealedTest {

  // -------------------------------------------------------------------------
  // 正常系: sealed interface ファイルが生成される
  // -------------------------------------------------------------------------

  @Test
  fun generateSealedInterfaceFile() {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      message("/sensor/value") { scalar("v", FLOAT) }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)

    // sealed interface ファイルが生成されること
    val sealedPath = "com/example/gen/OscMessages.kt"
    assertTrue(files.containsKey(sealedPath), "sealed interface ファイル $sealedPath が存在すること")

    val content = files[sealedPath]!!
    assertContains(content, "package com.example.gen")
    assertContains(content, "import com.oscplatform.core.runtime.OscMessage")
    assertContains(content, "sealed interface OscMessages : OscMessage")
  }

  // -------------------------------------------------------------------------
  // 正常系: sealed interface の KDoc に実装クラス一覧が含まれる
  // -------------------------------------------------------------------------

  @Test
  fun sealedInterfaceKDocListsImplementingClasses() {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      message("/sensor/value") { scalar("v", FLOAT) }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/OscMessages.kt"]!!

    // KDoc に各メッセージクラス名が列挙されること
    assertContains(content, "* - [LightColor]")
    assertContains(content, "* - [SensorValue]")
  }

  /** sealed interface 有効時に受信 helper 拡張ファイルが生成されることを検証する。 */
  @Test
  fun generateSealedRuntimeExtensionsFile() {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      message("/sensor/value") { scalar("v", FLOAT) }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)

    val extensionsPath = "com/example/gen/OscMessagesRuntimeExtensions.kt"
    assertTrue(files.containsKey(extensionsPath), "受信 helper ファイル $extensionsPath が存在すること")

    val content = files[extensionsPath]!!
    assertContains(content, "import com.oscplatform.core.runtime.OscRuntime")
    assertContains(content, "inline fun <reified T : OscMessages> OscRuntime.on(")
    assertContains(content, "OscMessages::class ->")
    assertContains(content, "LightColor::class -> on(LightColor)")
    assertContains(content, "SensorValue::class -> on(SensorValue)")
  }

  /** 受信 helper が未対応型に対する明示的な失敗分岐を含むことを検証する。 */
  @Test
  fun sealedRuntimeExtensionsIncludeUnsupportedTypeBranch() {
    val schema = oscSchema { message("/light/color") { scalar("r", INT) } }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/OscMessagesRuntimeExtensions.kt"]!!

    assertContains(content, "Unsupported generated OSC message type")
    assertContains(content, "${'$'}{T::class.qualifiedName}")
  }

  // -------------------------------------------------------------------------
  // 正常系: メッセージクラスが sealed interface を実装する
  // -------------------------------------------------------------------------

  @Test
  fun messageClassImplementsSealedInterface() {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/LightColor.kt"]!!

    // sealed interface を実装すること
    assertContains(content, ") : OscMessages {")
    // OscMessage の直接 import がないこと（sealed interface 経由で継承される）
    assertFalse(
        content.lines().any { it.trim() == "import com.oscplatform.core.runtime.OscMessage" },
        "sealed 指定時は OscMessage の直接 import が不要",
    )
  }

  // -------------------------------------------------------------------------
  // 正常系: sealed 未指定時は従来どおり OscMessage を直接実装する
  // -------------------------------------------------------------------------

  @Test
  fun withoutSealedOptionMessageClassImplementsOscMessageDirectly() {
    val schema = oscSchema { message("/light/color") { scalar("r", INT) } }

    val options = CodeGenOptions("com.example.gen")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/LightColor.kt"]!!

    // 従来どおり OscMessage を直接実装すること
    assertContains(content, ") : OscMessage {")
    assertContains(content, "import com.oscplatform.core.runtime.OscMessage")
    // sealed interface ファイルが生成されないこと
    assertFalse(
        files.keys.any { it.endsWith("OscMessages.kt") },
        "sealed 未指定時は sealed interface ファイルが生成されないこと",
    )
  }

  // -------------------------------------------------------------------------
  // 正常系: sealed 指定時でも companion object は OscMessageCompanion を実装する
  // -------------------------------------------------------------------------

  @Test
  fun sealedModePreservesCompanionObject() {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/LightColor.kt"]!!

    // companion object が OscMessageCompanion を実装すること
    assertContains(content, "companion object : OscMessageCompanion<LightColor> {")
    assertContains(content, "override val PATH: String = \"/light/color\"")
    assertContains(content, "override val NAME: String = \"light.color\"")
    assertContains(content, "override fun fromNamedArgs(args: Map<String, Any?>): LightColor")
  }

  // -------------------------------------------------------------------------
  // 正常系: sealed 指定時でも toNamedArgs が正しく生成される
  // -------------------------------------------------------------------------

  @Test
  fun sealedModePreservesToNamedArgs() {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/LightColor.kt"]!!

    assertContains(content, "override fun toNamedArgs(): Map<String, Any?>")
    assertContains(content, "\"r\" to r,")
    assertContains(content, "\"g\" to g,")
    assertContains(content, "\"b\" to b,")
  }

  // -------------------------------------------------------------------------
  // 正常系: sealed 指定時でもバンドルクラスは通常どおり生成される
  // -------------------------------------------------------------------------

  @Test
  fun sealedModeDoesNotAffectBundleGeneration() {
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

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)

    // バンドルクラスが通常どおり生成されること
    val bundleContent = files["com/example/gen/SetSceneBundle.kt"]!!
    assertContains(bundleContent, "data class SetSceneBundle(")
    assertContains(bundleContent, ") : OscBundle {")

    // メッセージクラスが sealed interface を実装すること
    val meshContent = files["com/example/gen/MeshPoints.kt"]!!
    assertContains(meshContent, ") : OscMessages {")

    // sealed interface ファイルも生成されること
    assertTrue(files.containsKey("com/example/gen/OscMessages.kt"))
  }

  // -------------------------------------------------------------------------
  // 正常系: 生成ファイル数の検証
  // -------------------------------------------------------------------------

  /** sealed interface 有効時に型定義と受信 helper を含む追加ファイルが生成されることを検証する。 */
  @Test
  fun sealedModeGeneratesCorrectNumberOfFiles() {
    val schema = oscSchema {
      message("/a/b") { scalar("x", INT) }
      message("/c/d") { scalar("y", FLOAT) }
    }

    // sealed 未指定: メッセージ2ファイルのみ
    val withoutSealed = KotlinCodeGenerator().generate(schema, CodeGenOptions("pkg"))
    assertEquals(2, withoutSealed.size, "sealed 未指定時は2ファイル")

    // sealed 指定: メッセージ2ファイル + sealed interface 1ファイル + runtime helper 1ファイル
    val withSealed =
        KotlinCodeGenerator()
            .generate(schema, CodeGenOptions("pkg", sealedInterfaceName = "OscMessages"))
    assertEquals(4, withSealed.size, "sealed 指定時は4ファイル")
  }

  // -------------------------------------------------------------------------
  // 正常系: generateSealedInterface を直接呼び出す
  // -------------------------------------------------------------------------

  @Test
  fun generateSealedInterfaceDirectly() {
    val gen = KotlinCodeGenerator()
    val content =
        gen.generateSealedInterface(
            interfaceName = "MyMessages",
            messageClassNames = listOf("Alpha", "Beta", "Gamma"),
            packageName = "com.test.pkg",
        )

    assertContains(content, "package com.test.pkg")
    assertContains(content, "import com.oscplatform.core.runtime.OscMessage")
    assertContains(content, "sealed interface MyMessages : OscMessage")
    assertContains(content, "* - [Alpha]")
    assertContains(content, "* - [Beta]")
    assertContains(content, "* - [Gamma]")
  }

  // -------------------------------------------------------------------------
  // 正常系: タプル配列を含むメッセージでも sealed interface を正しく実装する
  // -------------------------------------------------------------------------

  @Test
  fun sealedModeWithTupleArrayMessage() {
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

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/MeshPoints.kt"]!!

    // sealed interface を実装すること
    assertContains(content, ") : OscMessages {")
    // nested data class もそのまま生成されること
    assertContains(content, "data class Point(")
    // computed LENGTH property もそのまま生成されること
    assertContains(content, "val pointCount: Int get() = points.size")
  }

  // -------------------------------------------------------------------------
  // 正常系: スカラー配列を含むメッセージでも sealed interface を正しく実装する
  // -------------------------------------------------------------------------

  @Test
  fun sealedModeWithScalarArrayMessage() {
    val schema = oscSchema {
      message("/sensor/values") {
        scalar("valueCount", INT, role = LENGTH)
        array("values", lengthFrom = "valueCount") { scalar(FLOAT) }
      }
    }

    val options = CodeGenOptions("com.example.gen", sealedInterfaceName = "OscMessages")
    val files = KotlinCodeGenerator().generate(schema, options)
    val content = files["com/example/gen/SensorValues.kt"]!!

    // sealed interface を実装すること
    assertContains(content, ") : OscMessages {")
    // スカラー配列用の import があること
    assertContains(content, "import com.oscplatform.core.runtime.oscTypedList")
  }
}
