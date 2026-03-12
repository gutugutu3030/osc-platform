package com.oscplatform.gradle

import com.oscplatform.codegen.OscCodegen
import java.io.File
import java.net.URLClassLoader
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
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
          it.workerClasspath.from(collectWorkerClasspath(project))
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

  /**
   * Worker の classloader isolation に使用する classpath を収集する。
   *
   * プラグイン自身の classloader から全 JAR / クラスディレクトリを取得する。 これにより
   * osc-codegen・osc-core・kotlin-scripting-jsr223 等が Gradle の内部 classpath と分離され、KTS
   * スクリプトエンジンを安全に使用できる。
   */
  private fun collectWorkerClasspath(project: Project): FileCollection {
    val files = LinkedHashSet<File>()

    // WorkAction クラスを含む classloader からすべての URL を収集する。
    // Gradle の VisitableURLClassLoader は URLClassLoader を継承しているため
    // キャストが成功するケースが多い。失敗した場合はリフレクションで getURLs() を試みる。
    val cl = GenerateOscSourcesWorkAction::class.java.classLoader
    if (cl != null) {
      when (cl) {
        is URLClassLoader ->
            cl.urLs
                .mapNotNull { url ->
                  runCatching { File(url.toURI()).takeIf { it.exists() } }.getOrNull()
                }
                .forEach { files.add(it) }
        else ->
            try {
              @Suppress("UNCHECKED_CAST")
              val urls = cl.javaClass.getMethod("getURLs").invoke(cl) as? Array<java.net.URL>
              urls
                  ?.mapNotNull { url ->
                    runCatching { File(url.toURI()).takeIf { it.exists() } }.getOrNull()
                  }
                  ?.forEach { files.add(it) }
            } catch (_: Exception) {
              // URLClassLoader でない Gradle 内部 loader では無視する
            }
      }
    }

    // フォールバック: URLClassLoader が使えない環境でも最低限のクラスパスを確保する
    listOf(GenerateOscSourcesWorkAction::class.java, OscCodegen::class.java)
        .mapNotNull { cls ->
          cls.protectionDomain?.codeSource?.location?.let {
            runCatching { File(it.toURI()).takeIf { it.exists() } }.getOrNull()
          }
        }
        .forEach { files.add(it) }

    return project.files(files)
  }
}
