package com.oscplatform.scripting

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OscSchemaScript] の script definition を検証するテスト。
 *
 * 正常系では `schema.kts` が DSL を import なしで解決できることを確認し、 異常系では未定義シンボルがコンパイルエラーになることを確認する。
 */
class OscSchemaScriptDefinitionTest {

  /** `schema.kts` 用のコンパイル設定が期待する拡張子とパスパターンを持つことを検証する。 */
  @Test
  fun compilationConfigurationUsesSchemaKtsPattern() {
    val annotation = OscSchemaScript::class.java.getAnnotation(KotlinScript::class.java)

    assertTrue(annotation != null, "OscSchemaScript には @KotlinScript が付与されていること")
    val kotlinScript = requireNotNull(annotation)
    assertEquals(
        "kts",
        kotlinScript.fileExtension,
        "script definition は .kts を対象にすること",
    )
    assertEquals(
        "(.*/)?schema\\.kts",
        kotlinScript.filePathPattern,
        "script definition は schema.kts のみにマッチすること",
    )
  }

  /** import なしの `schema.kts` が custom script definition により成功することを検証する。 */
  @Test
  fun validSchemaScriptSucceedsWithoutExplicitImports() {
    val scriptPath = Files.createTempDirectory("osc-schema-script-valid-").resolve("schema.kts")

    try {
      scriptPath.writeText(
          """
          oscSchema {
            message("/test/msg") {
              scalar("x", INT)
            }
          }
          """
              .trimIndent(),
      )

      val result = evalScript(scriptPath)
      assertIs<ResultWithDiagnostics.Success<*>>(result)
    } finally {
      scriptPath.deleteIfExists()
      scriptPath.parent.deleteIfExists()
    }
  }

  /** 未定義シンボルを含む `schema.kts` が失敗し、エラー診断を返すことを検証する。 */
  @Test
  fun invalidSchemaScriptFailsWithDiagnostics() {
    val scriptPath = Files.createTempDirectory("osc-schema-script-invalid-").resolve("schema.kts")

    try {
      scriptPath.writeText(
          """
          oscSchema {
            unknownDslCall()
          }
          """
              .trimIndent(),
      )

      val result = evalScript(scriptPath)
      assertIs<ResultWithDiagnostics.Failure>(result)
      assertTrue(
          result.reports.any { report -> report.severity >= ScriptDiagnostic.Severity.ERROR },
          "失敗時は ERROR 以上の診断が含まれること",
      )
    } finally {
      scriptPath.deleteIfExists()
      scriptPath.parent.deleteIfExists()
    }
  }

  /**
   * 指定した `schema.kts` を custom script definition で評価する。
   *
   * @param scriptPath 評価対象の `schema.kts` パス
   * @return scripting host の評価結果
   */
  private fun evalScript(
      scriptPath: java.nio.file.Path
  ): ResultWithDiagnostics<kotlin.script.experimental.api.EvaluationResult> {
    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<OscSchemaScript>()
    return BasicJvmScriptingHost()
        .eval(scriptPath.toFile().toScriptSource(), compilationConfiguration, null)
  }
}
