package com.oscplatform.gradle

import java.io.File

/**
 * Gradle plugin テスト用の最小プロジェクトを指定ディレクトリへ構築する。
 *
 * `settings.gradle.kts`、`schema.yaml`、`build.gradle.kts` を生成し、 `com.oscplatform.schema-codegen`
 * を適用した TestKit 用プロジェクトを作成する。
 *
 * @param dir プロジェクトルートとなるディレクトリ
 * @param schemaContent 書き込む YAML スキーマの内容
 * @param language 生成言語
 * @param sealedInterfaceName 生成する sealed interface 名。未指定時は生成しない
 */
internal fun createTestProject(
    dir: File,
    schemaContent: String,
    language: String = "kotlin",
    sealedInterfaceName: String? = null,
) {
  dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
  dir.resolve("schema.yaml").writeText(schemaContent)

  val sealedConfig =
      sealedInterfaceName?.let { interfaceName ->
        """
        sealedInterfaceName.set("$interfaceName")
        """
            .trimIndent()
      } ?: ""

  dir.resolve("build.gradle.kts")
      .writeText(
          """
          plugins {
              id("com.oscplatform.schema-codegen")
          }
          oscSchemaCodegen {
              schema.set(layout.projectDirectory.file("schema.yaml"))
              packageName.set("com.example.gen")
              language.set("$language")
              $sealedConfig
          }
          """
              .trimIndent(),
      )
}
