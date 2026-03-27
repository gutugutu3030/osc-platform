package com.oscplatform.codegen

import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.oscSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KotlinCodeGenerator] のクラス名変換ロジックと境界ケースの識別子を検証するテスト。
 *
 * メッセージ名やバンドル名に含まれるドット・スラッシュ・アンダースコア等の 区切り文字が PascalCase クラス名に正しく変換されるかを確認する。
 */
class KotlinCodeGeneratorIdentifierTest {

  private val generator = KotlinCodeGenerator()

  // -------------------------------------------------------------------------
  // 境界: ドット区切りのメッセージ名
  // -------------------------------------------------------------------------

  @Test
  fun messageNameWithDotsProducesCorrectClassName() {
    // ドットで区切られた名前が各セグメント先頭大文字化される
    assertEquals("ABC", generator.toClassName("a.b.c"))
  }

  @Test
  fun messageNameWithDotsGeneratesCorrectFile() {
    val schema = oscSchema { message("/a/b/c") { scalar("v", INT) } }

    val files = generator.generate(schema, CodeGenOptions("pkg"))

    assertTrue(files.containsKey("pkg/ABC.kt"), "ABC.kt が生成されること")
    val content = files["pkg/ABC.kt"]!!
    assertContains(content, "data class ABC(")
  }

  // -------------------------------------------------------------------------
  // 境界: アンダースコアとドットを含むバンドル名
  // -------------------------------------------------------------------------

  @Test
  fun bundleNameWithUnderscoresAndDotsProducesCorrectClassName() {
    // アンダースコアとドットの混在が正しく処理される
    assertEquals("MyTest123Bundle", generator.toBundleClassName("my_test.123"))
  }

  @Test
  fun bundleNameWithHyphenProducesCorrectClassName() {
    assertEquals("FooBarBundle", generator.toBundleClassName("foo-bar"))
  }

  @Test
  fun bundleNameWithAllSeparatorsProducesCorrectClassName() {
    // アンダースコア・ドット・ハイフンすべてが区切り文字として機能する
    assertEquals("ABCBundle", generator.toBundleClassName("a_b.c"))
  }

  // -------------------------------------------------------------------------
  // 境界: パス区切りで始まるメッセージ名
  // -------------------------------------------------------------------------

  @Test
  fun messageNameStartingWithSlashProducesCorrectClassName() {
    // 先頭スラッシュが除去され、セグメントが PascalCase 化される
    assertEquals("XY", generator.toClassName("/x/y"))
  }

  @Test
  fun messagePathWithMultipleSegmentsProducesCorrectClassName() {
    assertEquals("AlphaBetaGamma", generator.toClassName("/alpha/beta/gamma"))
  }

  // -------------------------------------------------------------------------
  // 境界: 短い単一セグメント名
  // -------------------------------------------------------------------------

  @Test
  fun singleSegmentNameProducesCapitalizedClassName() {
    assertEquals("Test", generator.toClassName("test"))
  }

  @Test
  fun singleSegmentNameGeneratesCorrectCode() {
    val schema = oscSchema { message("/test") { scalar("v", INT) } }

    val files = generator.generate(schema, CodeGenOptions("pkg"))

    assertTrue(files.containsKey("pkg/Test.kt"), "Test.kt が生成されること")
    val content = files["pkg/Test.kt"]!!
    assertContains(content, "data class Test(")
    assertContains(content, "override val NAME: String = \"test\"")
  }

  // -------------------------------------------------------------------------
  // 境界: 単一文字のセグメント
  // -------------------------------------------------------------------------

  @Test
  fun singleCharacterSegmentsAreCapitalized() {
    assertEquals("AB", generator.toClassName("a.b"))
  }

  // -------------------------------------------------------------------------
  // 境界: パス内のスラッシュとドットの混合
  // -------------------------------------------------------------------------

  @Test
  fun classNameFromPathWithSlashesOnly() {
    assertEquals("FooBarBaz", generator.toClassName("/foo/bar/baz"))
  }

  @Test
  fun classNameFromNameWithDotsOnly() {
    assertEquals("FooBarBaz", generator.toClassName("foo.bar.baz"))
  }

  // -------------------------------------------------------------------------
  // 境界: バンドルクラスの生成コードにおけるパラメータ名
  // -------------------------------------------------------------------------

  @Test
  fun bundleParameterNamesAreDerivedFromMessageClassNames() {
    val schema = oscSchema {
      message("/my/widget") { scalar("w", INT) }
      message("/other/item") { scalar("v", FLOAT) }
      bundle("combined") {
        message("my.widget")
        message("other.item")
      }
    }

    val files = generator.generate(schema, CodeGenOptions("pkg"))
    val bundleContent = files["pkg/CombinedBundle.kt"]!!

    // クラス名の先頭小文字がパラメータ名になる
    assertContains(bundleContent, "val myWidget: MyWidget,")
    assertContains(bundleContent, "val otherItem: OtherItem,")
  }
}
