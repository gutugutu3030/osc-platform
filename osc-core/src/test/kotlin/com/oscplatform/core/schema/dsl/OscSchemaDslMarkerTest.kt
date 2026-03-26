package com.oscplatform.core.schema.dsl

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [OscSchemaDslMarker] アノテーションが全ビルダークラスに付与されていることを検証するテスト。
 *
 * `@DslMarker` により、ネストされたラムダ内で外側レシーバーのメンバーが 暗黙的に補完・呼び出しされることを防ぎ、IDE 補完の精度を高める。
 */
class OscSchemaDslMarkerTest {

  /** [OscSchemaDslMarker] アノテーションクラスが存在し、 `@DslMarker` メタアノテーション（コンパイル時スコープ制御）用に定義されていることを確認する。 */
  @Test
  fun dslMarkerAnnotationClassExists() {
    // OscSchemaDslMarker がアノテーションクラスとして存在する
    assertTrue(OscSchemaDslMarker::class.java.isAnnotation, "OscSchemaDslMarker がアノテーションでない")
  }

  /** 全ビルダークラスに [OscSchemaDslMarker] が付与されていることを確認する。 */
  @Test
  fun allBuilderClassesAreAnnotated() {
    val builderClasses =
        listOf(
            OscSchemaBuilder::class,
            OscMessageBuilder::class,
            ArrayItemBuilder::class,
            TupleFieldBuilder::class,
            OscBundleBuilder::class,
        )

    builderClasses.forEach { klass ->
      val annotation = klass.annotations.filterIsInstance<OscSchemaDslMarker>()
      assertNotNull(annotation.firstOrNull()) {
        "${klass.simpleName} に @OscSchemaDslMarker が付与されていない"
      }
    }
  }

  /**
   * `@OscSchemaDslMarker` 適用後も既存の DSL 構文でスキーマを構築できることを確認する。
   *
   * スコープ制御が正しく動作していれば、各ビルダーの正規メンバーのみで構築でき、 外側スコープへの暗黙アクセスが不要であることの間接的な検証となる。
   */
  @Test
  fun dslScopeProducesCorrectSchema() {
    val schema = oscSchema {
      message("/light/color") {
        description("set RGB color")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }

      message("/mesh/points") {
        description("set xyz points")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
          tuple {
            field("x", INT)
            field("y", INT)
            field("z", FLOAT)
          }
        }
      }

      bundle("LightBundle") {
        description("照明関連メッセージのバンドル")
        message("/light/color")
      }
    }

    assertTrue(schema.messages.size == 2, "メッセージ数が正しくない")
    assertTrue(schema.bundles.size == 1, "バンドル数が正しくない")
    assertNotNull(schema.resolveMessage("light.color"))
    assertNotNull(schema.resolveMessage("mesh.points"))
    assertNotNull(schema.findBundle("LightBundle"))
  }
}
