package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [KotlinScriptSchemaLoader] の単体テスト。
 *
 * Kotlin Script (`.kts`) を評価して [OscSchema] を返す正常系と、 戻り値型不一致・スクリプト評価例外の異常系を検証する。
 */
class KotlinScriptSchemaLoaderTest {

  private val loader = KotlinScriptSchemaLoader()

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** 有効な oscSchema DSL を含む `.kts` ファイルを読み込んで [OscSchema] が返ることを検証する。 */
  @Test
  fun loadReturnsOscSchemaFromValidKtsFile() {
    val tmp = Files.createTempFile("schema-valid-", ".kts")
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
      assertIs<OscSchema>(schema)
      val msg = schema.resolveMessage("/test/msg")
      assertNotNull(msg, "スキーマに /test/msg が含まれるべき")
    } finally {
      tmp.deleteIfExists()
    }
  }

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  /** スクリプトが [OscSchema] 以外の値を返した場合に [IllegalStateException] となることを検証する。 */
  @Test
  fun loadThrowsWhenScriptReturnsNonSchema() {
    val tmp = Files.createTempFile("schema-bad-return-", ".kts")
    try {
      tmp.writeText("42")

      try {
        loader.load(tmp)
        fail("OscSchema 以外を返すスクリプトは例外をスローするべき")
      } catch (e: IllegalStateException) {
        assertTrue(
            e.message?.contains("OscSchema") == true,
            "エラーメッセージに OscSchema が含まれるべき: ${e.message}",
        )
      }
    } finally {
      tmp.deleteIfExists()
    }
  }

  /**
   * スクリプト内で例外が発生した場合にその例外が伝播することを検証する。
   *
   * JSR-223 エンジンはスクリプト評価例外を [javax.script.ScriptException] でラップするため、 直接の [RuntimeException] ではなく
   * [Exception] として捕捉する。
   */
  @Test
  fun loadPropagatesScriptEvaluationException() {
    val tmp = Files.createTempFile("schema-error-", ".kts")
    try {
      tmp.writeText("""throw RuntimeException("broken")""")

      try {
        loader.load(tmp)
        fail("評価エラーのあるスクリプトは例外をスローするべき")
      } catch (_: Exception) {
        // 期待通り — スクリプト内の例外が ScriptException としてラップされ伝播した
      }
    } finally {
      tmp.deleteIfExists()
    }
  }
}
