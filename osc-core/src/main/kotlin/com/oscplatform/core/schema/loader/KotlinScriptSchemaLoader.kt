package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema
import javax.script.ScriptEngineManager
import kotlin.io.path.readText

/**
 * Kotlin Script（`.kts`）ファイルからOSCスキーマを読み込むローダー。
 *
 * スクリプト内で `oscSchema { ... }` を評価し、[OscSchema] を返す。
 */
class KotlinScriptSchemaLoader {
  private val engine by lazy {
    ScriptEngineManager().getEngineByExtension("kts")
        ?: error(
            "Kotlin script engine not found. Ensure kotlin-scripting-jsr223 is on the classpath")
  }

  /**
   * 指定されたパスのKotlin Scriptファイルを読み込み、[OscSchema] として返す。
   *
   * スクリプトにはDSLインポートが自動で挿入される。
   *
   * @param path スキーマスクリプトファイルのパス
   * @return 読み込まれた [OscSchema]
   * @throws IllegalStateException スクリプトの評価結果が [OscSchema] でない場合
   */
  fun load(path: java.nio.file.Path): OscSchema {
    val source = path.readText()
    val wrappedScript = source.wrapOscSchemaScript()

    val result = engine.eval(wrappedScript)
    return result.requireOscSchema()
  }
}
