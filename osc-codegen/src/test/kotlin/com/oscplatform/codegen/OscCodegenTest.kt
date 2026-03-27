package com.oscplatform.codegen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [OscCodegen] ファサードの統合テスト。
 *
 * スキーマファイルからコード生成までのエンドツーエンド動作を検証する。
 */
class OscCodegenTest {

  // -------------------------------------------------------------------------
  // 正常系: YAML ファイルから Kotlin コードを生成
  // -------------------------------------------------------------------------

  @Test
  fun generateFromYamlFileProducesKotlinFiles() {
    val yamlContent =
        """
            messages:
              - path: "/test/msg"
                args:
                  - name: "x"
                    type: "INT"
            """
            .trimIndent()

    val tempFile = createTempSchemaFile(suffix = ".yaml", content = yamlContent)
    try {
      val options = CodeGenOptions(packageName = "com.gen", language = "kotlin")
      // YAML ファイルを読み込み、コード生成を実行
      val result = OscCodegen.generateFromFile(tempFile, options)

      assertTrue(result.isNotEmpty(), "生成結果が空でないこと")

      val expectedPath = "com/gen/TestMsg.kt"
      assertTrue(result.containsKey(expectedPath), "ファイル $expectedPath が存在すること")

      val content = result[expectedPath]!!
      assertTrue(content.contains("package com.gen"), "パッケージ宣言が含まれること")
      assertTrue(content.contains("data class TestMsg("), "data class 宣言が含まれること")
      assertTrue(content.contains("val x: Int,"), "フィールド x が含まれること")
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: KTS ファイルから Kotlin コードを生成
  // -------------------------------------------------------------------------

  @Test
  fun generateFromKtsFileProducesKotlinFiles() {
    val ktsContent =
        """
            import com.oscplatform.core.schema.dsl.*
            oscSchema {
                message("/test/msg") {
                    scalar("x", INT)
                }
            }
            """
            .trimIndent()

    val tempFile = createTempSchemaFile(suffix = ".kts", content = ktsContent)
    try {
      val options = CodeGenOptions(packageName = "com.gen", language = "kotlin")
      // KTS ファイルを読み込み、コード生成を実行
      val result = OscCodegen.generateFromFile(tempFile, options)

      assertTrue(result.isNotEmpty(), "生成結果が空でないこと")

      val expectedPath = "com/gen/TestMsg.kt"
      assertTrue(result.containsKey(expectedPath), "ファイル $expectedPath が存在すること")

      val content = result[expectedPath]!!
      assertTrue(content.contains("data class TestMsg("), "data class 宣言が含まれること")
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  // -------------------------------------------------------------------------
  // 異常系: サポートされていない言語を指定した場合
  // -------------------------------------------------------------------------

  @Test
  fun generateFromFileThrowsOnUnsupportedLanguage() {
    val yamlContent =
        """
            messages:
              - path: "/test/msg"
                args:
                  - name: "x"
                    type: "INT"
            """
            .trimIndent()

    val tempFile = createTempSchemaFile(suffix = ".yaml", content = yamlContent)
    try {
      val options = CodeGenOptions(packageName = "com.gen", language = "java")
      // 未サポート言語はエラーになること
      val ex =
          assertFailsWith<IllegalStateException> { OscCodegen.generateFromFile(tempFile, options) }
      assertTrue(
          ex.message!!.contains("java"),
          "エラーメッセージに指定した言語名が含まれること",
      )
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  // -------------------------------------------------------------------------
  // 異常系: 不正なスキーマファイル内容
  // -------------------------------------------------------------------------

  @Test
  fun generateFromFileThrowsOnInvalidSchemaContent() {
    val invalidContent = "this is not valid schema content !!!"

    val tempFile = createTempSchemaFile(suffix = ".yaml", content = invalidContent)
    try {
      val options = CodeGenOptions(packageName = "com.gen", language = "kotlin")
      // 不正なスキーマは例外が発生すること
      assertFailsWith<Exception> { OscCodegen.generateFromFile(tempFile, options) }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: 複数メッセージを含む YAML からの生成
  // -------------------------------------------------------------------------

  @Test
  fun generateFromYamlWithMultipleMessages() {
    val yamlContent =
        """
            messages:
              - path: "/a/b"
                args:
                  - name: "x"
                    type: "INT"
              - path: "/c/d"
                args:
                  - name: "y"
                    type: "FLOAT"
            """
            .trimIndent()

    val tempFile = createTempSchemaFile(suffix = ".yaml", content = yamlContent)
    try {
      val options = CodeGenOptions(packageName = "pkg", language = "kotlin")
      val result = OscCodegen.generateFromFile(tempFile, options)

      assertEquals(2, result.size, "2 つのファイルが生成されること")
      assertTrue(result.containsKey("pkg/AB.kt"), "AB.kt が存在すること")
      assertTrue(result.containsKey("pkg/CD.kt"), "CD.kt が存在すること")
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: デフォルト言語 (kotlin) が使用されること
  // -------------------------------------------------------------------------

  @Test
  fun generateFromFileUsesDefaultLanguage() {
    val yamlContent =
        """
            messages:
              - path: "/default/lang"
                args:
                  - name: "v"
                    type: "STRING"
            """
            .trimIndent()

    val tempFile = createTempSchemaFile(suffix = ".yaml", content = yamlContent)
    try {
      // language を明示しないデフォルトオプション
      val options = CodeGenOptions(packageName = "out")
      val result = OscCodegen.generateFromFile(tempFile, options)

      assertTrue(result.isNotEmpty(), "デフォルト言語でも生成されること")
      val content = result["out/DefaultLang.kt"]!!
      assertTrue(content.contains("val v: String,"), "STRING 型のフィールドが含まれること")
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  // =========================================================================
  // ヘルパー
  // =========================================================================

  /**
   * 一時スキーマファイルを作成する。
   *
   * @param suffix ファイル拡張子 (e.g. ".yaml", ".kts")
   * @param content ファイルに書き込む内容
   * @return 作成された一時ファイルのパス
   */
  private fun createTempSchemaFile(suffix: String, content: String): Path {
    val tempFile = Files.createTempFile("osc-codegen-test-", suffix)
    Files.writeString(tempFile, content)
    return tempFile
  }
}
