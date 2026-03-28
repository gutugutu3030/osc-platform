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
import kotlin.test.assertTrue

/**
 * [SchemaMarkdownDocRenderer] の単体テスト。
 *
 * 検証内容:
 * - 完全なスキーマからMarkdownテーブル形式のドキュメントが生成される
 * - メッセージ詳細セクションに引数テーブルが含まれる
 * - バンドルが空の場合に "No bundle definitions." が出力される
 * - パイプ文字 | がエスケープされる
 * - バッククォートがコードスパン内でエスケープされる
 * - 説明中の改行が &lt;br&gt; に変換される
 */
class SchemaMarkdownDocRendererTest {

  /**
   * テスト用の共通スキーマパスを返す。
   *
   * @return テスト用のスキーマパス
   */
  private fun testSchemaPath(): Path = Path.of("test-schema.yaml")

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** 完全なスキーマからMarkdownテーブルが正しいフォーマットで生成される */
  @Test
  fun renderProducesMarkdownTableWithCorrectFormat() {
    val schema = oscSchema {
      message("/light/color") {
        description("Set RGB color")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      bundle("set_scene") {
        description("update scene atomically")
        message("/light/color")
      }
    }

    val md =
        SchemaMarkdownDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = "Test Schema",
        )

    // ヘッダーの検証
    assertContains(md, "# Test Schema")

    // メッセージテーブルヘッダーの検証
    assertContains(md, "| Name | Path | Description | Args |")
    assertContains(md, "| --- | --- | --- | --- |")

    // メッセージデータ行の検証
    assertContains(md, "/light/color")
    assertContains(md, "Set RGB color")

    // バンドルテーブルの検証
    assertContains(md, "| Name | Description | Message Refs |")
    assertContains(md, "set_scene")
    assertContains(md, "update scene atomically")
  }

  /** メッセージ詳細セクションに引数テーブルが含まれる */
  @Test
  fun renderProducesMessageDetailSectionsWithArgTables() {
    val schema = oscSchema {
      message("/mesh/points") {
        description("set xyz points")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
          tuple {
            field("x", INT)
            field("y", FLOAT)
          }
        }
      }
    }

    val md =
        SchemaMarkdownDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    // 詳細セクションの見出し検証
    assertContains(md, "### `mesh.points`")

    // 引数テーブルヘッダーの検証
    assertContains(md, "| Arg | Kind | Type | Constraints |")
    assertContains(md, "| --- | --- | --- | --- |")

    // 引数データ行の検証
    assertContains(md, "pointCount")
    assertContains(md, "scalar")
    assertContains(md, "role=length")
    assertContains(md, "array<tuple{x:int, y:float}>")
    assertContains(md, "lengthFrom=pointCount")
  }

  // -------------------------------------------------------------------------
  // 境界値
  // -------------------------------------------------------------------------

  /** バンドルが空の場合 "No bundle definitions." が出力される */
  @Test
  fun renderShowsNoBundleDefinitionsWhenBundlesEmpty() {
    val schema = oscSchema { message("/test/msg") { scalar("x", INT) } }

    val md =
        SchemaMarkdownDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    assertContains(md, "No bundle definitions.")
  }

  /** パイプ文字 | が \\| にエスケープされる */
  @Test
  fun renderEscapesPipeCharacterInDescription() {
    val schema = oscSchema {
      message("/test/msg") {
        description("value A | value B")
        scalar("x", INT)
      }
    }

    val md =
        SchemaMarkdownDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    // パイプがエスケープされている
    assertContains(md, "value A \\| value B")
    // テーブル区切りのパイプとは別にエスケープ済みパイプが含まれるか確認
    assertTrue(md.contains("\\|"))
  }

  /** バッククォートがコードスパン内で \\` にエスケープされる */
  @Test
  fun renderEscapesBacktickInCodeValues() {
    val schema = oscSchema { message("/test/msg") { scalar("x", STRING) } }

    val md =
        SchemaMarkdownDocRenderer.render(
            schema = schema,
            schemaPath = Path.of("schema`special.yaml"),
            title = null,
        )

    // バッククォートがエスケープされている
    assertContains(md, "schema\\`special.yaml")
    // 生のバッククォートがソースパス内に直接含まれない
    assertFalse(md.contains("schema`special.yaml"))
  }

  /** 説明中の改行が &lt;br&gt; に変換される */
  @Test
  fun renderConvertsNewlinesInDescriptionToBrTag() {
    val schema = oscSchema {
      message("/test/msg") {
        description("line one\nline two")
        scalar("x", INT)
      }
    }

    val md =
        SchemaMarkdownDocRenderer.render(
            schema = schema,
            schemaPath = testSchemaPath(),
            title = null,
        )

    // 改行が <br> に変換されている
    assertContains(md, "line one<br>line two")
  }
}
