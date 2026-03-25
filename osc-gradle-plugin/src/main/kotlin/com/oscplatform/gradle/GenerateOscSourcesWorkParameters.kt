package com.oscplatform.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters

/** [GenerateOscSourcesWorkAction] に渡すパラメータ。 */
interface GenerateOscSourcesWorkParameters : WorkParameters {
  /** 読み込むスキーマファイル (schema.yaml / schema.kts)。 */
  val schemaFile: RegularFileProperty

  /** 生成コードのパッケージ名。 */
  val packageName: Property<String>

  /** 生成言語 (`"kotlin"` または `"java"`)。 */
  val language: Property<String>

  /** 生成されたソースの出力ディレクトリ。 */
  val outputDirectory: DirectoryProperty
}
