package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * ファイル拡張子に基づいてスキーマローダーを振り分ける統合ローダー。
 *
 * `.yaml`/`.yml` は [YamlSchemaLoader]、`.kts` は [KotlinScriptSchemaLoader] に委譲する。
 *
 * @param yamlLoader YAML形式のスキーマローダー
 * @param scriptLoader Kotlin Script形式のスキーマローダー
 */
class SchemaLoader(
    private val yamlLoader: YamlSchemaLoader = YamlSchemaLoader(),
    private val scriptLoader: KotlinScriptSchemaLoader = KotlinScriptSchemaLoader(),
) {
  /**
   * 指定されたパスのスキーマファイルを読み込む。
   *
   * ファイル拡張子により適切なローダーを選択する。
   *
   * @param path スキーマファイルのパス
   * @return 読み込まれた [OscSchema]
   * @throws IllegalStateException サポートされていない拡張子の場合
   */
  fun load(path: Path): OscSchema {
    return when (path.extension.lowercase()) {
      "yaml",
      "yml" -> yamlLoader.load(path)
      "kts" -> scriptLoader.load(path)
      else -> error("Unsupported schema extension: ${path.fileName}")
    }
  }
}
