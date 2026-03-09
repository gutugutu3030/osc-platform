package com.oscplatform.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer

/**
 * OscSchema コード生成プラグイン。
 *
 * plugin id: `com.oscplatform.schema-codegen`
 *
 * 使い方:
 * ```kotlin
 * plugins {
 *     id("com.oscplatform.schema-codegen")
 * }
 * oscSchemaCodegen {
 *     schema.set(layout.projectDirectory.file("schema.yaml"))
 *     packageName.set("com.example.osc.generated")
 *     language.set("kotlin")           // optional, default = "kotlin"
 *     outputDir.set(...)               // optional
 * }
 * ```
 */
class OscSchemaCodegenPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    val extension =
        project.extensions.create(
            "oscSchemaCodegen",
            OscSchemaCodegenExtension::class.java,
        )

    // デフォルト値
    extension.language.convention("kotlin")
    extension.outputDir.convention(
        project.layout.buildDirectory.dir("generated/sources/osc/main/kotlin"),
    )

    val generateTask =
        project.tasks.register("generateOscSources", GenerateOscSourcesTask::class.java) {
          it.schemaFile.set(extension.schema)
          it.packageName.set(extension.packageName)
          it.language.set(extension.language)
          it.outputDirectory.set(extension.outputDir)
        }

    // Kotlin JVM プロジェクトへのフック
    project.plugins.withId("org.jetbrains.kotlin.jvm") {
      val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
      sourceSets.named("main") { sourceSet ->
        sourceSet.java.srcDir(generateTask.map { it.outputDirectory })
      }
      project.tasks.named("compileKotlin") { it.dependsOn(generateTask) }
    }

    // Java プロジェクトへのフック
    project.plugins.withType(JavaPlugin::class.java) {
      val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
      sourceSets.named("main") { sourceSet ->
        sourceSet.java.srcDir(generateTask.map { it.outputDirectory })
      }
      project.tasks.named("compileJava") { it.dependsOn(generateTask) }
    }
  }
}
