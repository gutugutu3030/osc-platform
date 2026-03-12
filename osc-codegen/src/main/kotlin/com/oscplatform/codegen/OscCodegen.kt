package com.oscplatform.codegen

import com.oscplatform.core.schema.loader.SchemaLoader
import java.nio.file.Path

/**
 * ファサード: スキーマファイルパスから直接コード生成する。
 *
 * `osc-gradle-plugin` 等は本クラスのみに依存すれば `osc-core` への直接依存が不要になる。
 */
object OscCodegen {

  /**
   * スキーマファイルを読み込んでソースコードを生成する。
   *
   * @param schemaFile 読み込むスキーマファイル (`.yaml` / `.kts`)
   * @param options 生成オプション
   * @return 相対ファイルパス → ファイル内容 のマップ
   */
  fun generateFromFile(schemaFile: Path, options: CodeGenOptions): Map<String, String> {
    val schema = SchemaLoader().load(schemaFile)
    return when (options.language) {
      "kotlin" -> KotlinCodeGenerator().generate(schema, options)
      else -> error("Unsupported language: '${options.language}'. Supported: kotlin")
    }
  }
}
