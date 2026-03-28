package com.oscplatform.gradle

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

/** [OscSchemaCodegenPlugin] の単体テスト。ProjectBuilder を使用してプラグイン適用を検証する。 */
class OscSchemaCodegenPluginTest {

  /**
   * プラグイン適用後に "oscSchemaCodegen" 拡張が生成されることを検証する。
   *
   * 正常系: プラグイン ID を使って適用し、[OscSchemaCodegenExtension] が取得できることを確認する。
   */
  @Test
  fun pluginCreatesExtension() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val ext = project.extensions.findByName("oscSchemaCodegen")
    assertNotNull(ext, "oscSchemaCodegen 拡張が登録されていること")
    assertIs<OscSchemaCodegenExtension>(ext)
  }

  /**
   * プラグイン適用後に "generateOscSources" タスクが登録されることを検証する。
   *
   * 正常系: タスク名で検索し、[GenerateOscSourcesTask] インスタンスであることを確認する。
   */
  @Test
  fun pluginRegistersGenerateTask() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val task = project.tasks.findByName("generateOscSources")
    assertNotNull(task, "generateOscSources タスクが登録されていること")
    assertIs<GenerateOscSourcesTask>(task)
  }

  /**
   * 拡張プロパティのデフォルト値が正しく設定されることを検証する。
   *
   * 正常系: language のデフォルトが "kotlin"、outputDir のデフォルトパスに "generated/sources/osc" が含まれることを確認する。
   */
  @Test
  fun extensionHasDefaultConventions() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val ext = project.extensions.getByType(OscSchemaCodegenExtension::class.java)
    kotlin.test.assertEquals("kotlin", ext.language.get(), "language のデフォルトが 'kotlin' であること")
    assertTrue(
        ext.outputDir.get().asFile.path.contains("generated/sources/osc"),
        "outputDir のデフォルトパスに 'generated/sources/osc' が含まれること",
    )
  }

  /**
   * Kotlin JVM プラグインと併用した場合に compileKotlin が generateOscSources に依存することを検証する。
   *
   * 正常系: 両プラグインを適用し、compileKotlin のタスク依存に generateOscSources が含まれることを確認する。
   */
  @Test
  fun kotlinJvmPluginHooksCompileKotlinDependency() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("org.jetbrains.kotlin.jvm")
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val compileKotlin = project.tasks.findByName("compileKotlin")
    assertNotNull(compileKotlin, "compileKotlin タスクが存在すること")

    val dependsOnNames =
        compileKotlin.dependsOn.flatMap { dep ->
          when (dep) {
            is org.gradle.api.tasks.TaskProvider<*> -> listOf(dep.name)
            is org.gradle.api.Task -> listOf(dep.name)
            else -> emptyList()
          }
        }
    assertTrue(
        dependsOnNames.contains("generateOscSources"),
        "compileKotlin が generateOscSources に依存すること: $dependsOnNames",
    )
  }

  /**
   * Java プラグインと併用した場合に compileJava が generateOscSources に依存することを検証する。
   *
   * 正常系: 両プラグインを適用し、compileJava のタスク依存に generateOscSources が含まれることを確認する。
   */
  @Test
  fun javaPluginHooksCompileJavaDependency() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("java")
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val compileJava = project.tasks.findByName("compileJava")
    assertNotNull(compileJava, "compileJava タスクが存在すること")

    val dependsOnNames =
        compileJava.dependsOn.flatMap { dep ->
          when (dep) {
            is org.gradle.api.tasks.TaskProvider<*> -> listOf(dep.name)
            is org.gradle.api.Task -> listOf(dep.name)
            else -> emptyList()
          }
        }
    assertTrue(
        dependsOnNames.contains("generateOscSources"),
        "compileJava が generateOscSources に依存すること: $dependsOnNames",
    )
  }
}
