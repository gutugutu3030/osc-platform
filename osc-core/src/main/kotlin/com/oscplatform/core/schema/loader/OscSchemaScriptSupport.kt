package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema

/** schema.kts 評価時に先頭へ挿入する DSL import 行。 */
const val OSC_SCHEMA_DSL_IMPORT = "import com.oscplatform.core.schema.dsl.*"

/**
 * schema.kts 用の DSL テキストを評価可能な Kotlin Script 文字列へ整形する。
 *
 * @return DSL import を先頭に付与した評価用スクリプト
 * @receiver ユーザーが記述した schema DSL 本文
 */
fun String.wrapOscSchemaScript(): String {
  return buildString {
    appendLine(OSC_SCHEMA_DSL_IMPORT)
    appendLine(this@wrapOscSchemaScript)
  }
}

/**
 * Kotlin Script の評価結果が [OscSchema] であることを検証する。
 *
 * @return [OscSchema] として解釈された評価結果
 * @receiver スクリプト評価結果
 * @throws IllegalStateException 評価結果が [OscSchema] ではない場合
 */
fun Any?.requireOscSchema(): OscSchema {
  return this as? OscSchema
      ?: error("Schema script must evaluate to OscSchema. Example: oscSchema { ... }")
}
