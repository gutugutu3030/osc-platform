package com.oscplatform.core.schema.loader

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [SchemaLoader] の統合テスト。
 *
 * ファイル拡張子に基づくローダー振り分け（`.yaml` / `.yml` → [YamlSchemaLoader]、 `.kts` →
 * [KotlinScriptSchemaLoader]）と、未対応拡張子の異常系を検証する。
 */
class SchemaLoaderTest {

  private val loader = SchemaLoader()

  // -------------------------------------------------------------------------
  // 正常系: YAML
  // -------------------------------------------------------------------------

  /** `.yaml` 拡張子のファイルが YAML ローダーに委譲され、正しくスキーマが返ることを検証する。 */
  @Test
  fun loadYamlDelegatesToYamlLoader() {
    val tmp = Files.createTempFile("schema-", ".yaml")
    try {
      tmp.writeText(VALID_YAML)

      val schema = loader.load(tmp)
      val msg = schema.resolveMessage("/test/msg")
      assertNotNull(msg, "YAML から読み込んだスキーマに /test/msg が含まれるべき")
    } finally {
      tmp.deleteIfExists()
    }
  }

  /** `.yml` 拡張子のファイルも YAML ローダーに委譲されることを検証する。 */
  @Test
  fun loadYmlDelegatesToYamlLoader() {
    val tmp = Files.createTempFile("schema-", ".yml")
    try {
      tmp.writeText(VALID_YAML)

      val schema = loader.load(tmp)
      val msg = schema.resolveMessage("/test/msg")
      assertNotNull(msg, "YML から読み込んだスキーマに /test/msg が含まれるべき")
    } finally {
      tmp.deleteIfExists()
    }
  }

  // -------------------------------------------------------------------------
  // 正常系: Kotlin Script
  // -------------------------------------------------------------------------

  /** `.kts` 拡張子のファイルが Kotlin Script ローダーに委譲されることを検証する。 */
  @Test
  fun loadKtsDelegatesToScriptLoader() {
    val tmp = Files.createTempFile("schema-", ".kts")
    try {
      tmp.writeText(
          """
          oscSchema {
            message("/test/msg") {
              scalar("x", INT)
            }
          }
          """
              .trimIndent(),
      )

      val schema = loader.load(tmp)
      val msg = schema.resolveMessage("/test/msg")
      assertNotNull(msg, "KTS から読み込んだスキーマに /test/msg が含まれるべき")
    } finally {
      tmp.deleteIfExists()
    }
  }

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  /** サポートされていない拡張子 (`.json`) で読み込んだ場合に [IllegalStateException] が発生することを検証する。 */
  @Test
  fun loadUnsupportedExtensionThrowsIllegalStateException() {
    val tmp = Files.createTempFile("schema-", ".json")
    try {
      tmp.writeText("{}")

      try {
        loader.load(tmp)
        fail("未対応拡張子は IllegalStateException をスローするべき")
      } catch (e: IllegalStateException) {
        assertTrue(
            e.message?.contains("Unsupported schema extension") == true,
            "エラーメッセージに Unsupported schema extension が含まれるべき: ${e.message}",
        )
      }
    } finally {
      tmp.deleteIfExists()
    }
  }

  companion object {
    /** テスト用の最小限な YAML スキーマ。 */
    private val VALID_YAML =
        """
        messages:
          - path: "/test/msg"
            args:
              - name: "x"
                type: "INT"
        """
            .trimIndent()
  }
}
