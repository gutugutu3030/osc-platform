package com.oscplatform.adapter.cli

import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.STRING
import com.oscplatform.core.schema.dsl.oscSchema
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * [SchemaHtmlDocRenderer] の単体テスト。
 *
 * 検証内容:
 * - 完全なスキーマからタイトル・メッセージテーブル・バンドルテーブルを含むHTMLが生成される
 * - カスタムタイトルがHTMLに反映される
 * - nullタイトルでデフォルトタイトルが使用される
 * - バンドルが空の場合に "No bundle definitions." が出力される
 * - HTMLエンティティが正しくエスケープされる
 * - タプル配列型が正しく表示される
 * - 固定長配列の制約が正しく表示される
 */
class SchemaHtmlDocRendererTest {

  /**
   * テスト用の共通スキーマパスを返す。
   *
   * @return テスト用のスキーマパス
   */
  private fun testSchemaPath(): Path = Path.of("test-schema.yaml")

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** 完全なスキーマからタイトル・メッセージテーブル・バンドルテーブルを含むHTMLが生成される */
  @Test
  fun renderProducesHtmlWithTitleMessagesAndBundles() {
    val schema = oscSchema {
      message("/light/color") {
        description("Set RGB color")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      message("/device/flag") { scalar("enabled", STRING) }
      bundle("set_scene") {
        description("update scene atomically")
        message("/light/color")
        message("/device/flag")
      }
    }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = "My Schema",
        )

    // ドキュメント構造の検証
    assertContains(html, "<!doctype html>")
    assertContains(html, "<title>My Schema</title>")
    assertContains(html, "<h1>My Schema</h1>")

    // メッセージテーブルの検証
    assertContains(html, "/light/color")
    assertContains(html, "/device/flag")
    assertContains(html, "Set RGB color")

    // バンドルテーブルの検証
    assertContains(html, "set_scene")
    assertContains(html, "update scene atomically")
    assertFalse(html.contains("No bundle definitions."))
    assertFalse(html.contains("No message definitions."))
  }

  /** カスタムタイトルがHTMLに反映される */
  @Test
  fun renderUsesCustomTitleWhenProvided() {
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = "Custom Title 日本語",
        )

    assertContains(html, "<title>Custom Title 日本語</title>")
    assertContains(html, "<h1>Custom Title 日本語</h1>")
  }

  // -------------------------------------------------------------------------
  // 境界値
  // -------------------------------------------------------------------------

  /** nullタイトルはデフォルト "OSC Schema Documentation" に置き換わる */
  @Test
  fun renderUsesDefaultTitleWhenNullProvided() {
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    assertContains(html, "<title>OSC Schema Documentation</title>")
    assertContains(html, "<h1>OSC Schema Documentation</h1>")
  }

  /** バンドルが空の場合 "No bundle definitions." が出力される */
  @Test
  fun renderShowsNoBundleDefinitionsWhenBundlesEmpty() {
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    assertContains(html, "No bundle definitions.")
  }

  /** HTMLエンティティ（&lt;&gt;&amp;&quot;&#39;）が正しくエスケープされる */
  @Test
  fun renderEscapesHtmlEntitiesInDescription() {
    val schema = oscSchema {
      message("/test/msg") {
        description("<script>alert('xss')&\"end\"</script>")
        scalar("x", INT)
      }
    }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    // エスケープ後のHTML文字参照が含まれる
    assertContains(html, "&lt;script&gt;")
    assertContains(html, "&amp;")
    assertContains(html, "&quot;end&quot;")
    assertContains(html, "&#39;xss&#39;")
    // 生のHTMLタグが含まれない
    assertFalse(html.contains("<script>"))
  }

  /** タプル配列型が "array&lt;tuple{x:int, y:float}&gt;" として正しく表示される */
  @Test
  fun renderDisplaysTupleArrayTypeCorrectly() {
    val schema = oscSchema {
      message("/mesh/points") {
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
          tuple {
            field("x", INT)
            field("y", FLOAT)
          }
        }
      }
    }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    // エスケープ後の文字列で確認（< → &lt;, > → &gt;）
    assertContains(html, "array&lt;tuple{x:int, y:float}&gt;")
  }

  /** 固定長配列の制約 "length=N" が正しく表示される */
  @Test
  fun renderShowsFixedLengthConstraintForArray() {
    val schema = oscSchema {
      message("/data/fixed") { array("values", length = 10) { scalar(FLOAT) } }
    }

    val html =
        SchemaHtmlDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    assertContains(html, "length=10")
  }
}
