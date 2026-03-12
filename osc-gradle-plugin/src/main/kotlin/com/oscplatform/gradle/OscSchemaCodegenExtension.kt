package com.oscplatform.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** [OscSchemaCodegenPlugin] の拡張設定。 */
abstract class OscSchemaCodegenExtension {
  /** スキーマファイル (schema.yaml / schema.kts)。必須。 */
  abstract val schema: RegularFileProperty

  /** 生成クラスのパッケージ名。必須。 */
  abstract val packageName: Property<String>

  /** 生成言語。デフォルト `"kotlin"`。 */
  abstract val language: Property<String>

  /** 出力ディレクトリ。デフォルト `build/generated/sources/osc/main/kotlin`。 */
  abstract val outputDir: DirectoryProperty
}
