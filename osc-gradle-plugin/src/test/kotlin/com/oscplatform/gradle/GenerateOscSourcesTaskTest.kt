package com.oscplatform.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * [GenerateOscSourcesTask] のパラメータ配線と [GenerateOscSourcesWorkAction] の動作を検証するテスト。
 *
 * ProjectBuilder を使用したユニットテストでタスクプロパティの配線を確認し、 Gradle TestKit を使用した機能テストで WorkAction
 * の出力削除・再生成および異常系を検証する。
 */
class GenerateOscSourcesTaskTest {

  /** TestKit 用の一時ディレクトリ。各テスト後にクリーンアップされる。 */
  private val testDir: File = Files.createTempDirectory("osc-task-test").toFile()

  /** テスト終了後に一時ディレクトリを再帰的に削除する。 */
  @AfterTest
  fun cleanup() {
    testDir.deleteRecursively()
  }

  // ---------------------------------------------------------------
  // ProjectBuilder テスト — タスクプロパティの配線検証
  // ---------------------------------------------------------------

  /**
   * 拡張で設定したスキーマファイルがタスクの [GenerateOscSourcesTask.schemaFile] に配線されることを検証する。
   *
   * 正常系: プラグインを適用し拡張の schema を設定した後、タスクの schemaFile が同じファイルを指すことを確認する。
   */
  @Test
  fun taskSchemaFileIsWiredFromExtension() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    // 拡張にスキーマファイルを設定
    val ext = project.extensions.getByType(OscSchemaCodegenExtension::class.java)
    val schemaFile = project.file("schema.yaml")
    schemaFile.writeText(SIMPLE_SCHEMA)
    ext.schema.set(schemaFile)

    val task = project.tasks.getByName("generateOscSources") as GenerateOscSourcesTask
    assertEquals(
        schemaFile,
        task.schemaFile.get().asFile,
        "タスクの schemaFile が拡張の schema と一致すること",
    )
  }

  /**
   * 拡張で設定したパッケージ名がタスクの [GenerateOscSourcesTask.packageName] に配線されることを検証する。
   *
   * 正常系: 拡張の packageName を設定し、タスクの packageName が同じ値であることを確認する。
   */
  @Test
  fun taskPackageNameIsWiredFromExtension() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val ext = project.extensions.getByType(OscSchemaCodegenExtension::class.java)
    ext.packageName.set("com.example.test")

    val task = project.tasks.getByName("generateOscSources") as GenerateOscSourcesTask
    assertEquals(
        "com.example.test",
        task.packageName.get(),
        "タスクの packageName が拡張の packageName と一致すること",
    )
  }

  /**
   * 拡張で設定した言語がタスクの [GenerateOscSourcesTask.language] に配線されることを検証する。
   *
   * 正常系: 拡張の language を設定し、タスクの language が同じ値であることを確認する。
   */
  @Test
  fun taskLanguageIsWiredFromExtension() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val ext = project.extensions.getByType(OscSchemaCodegenExtension::class.java)
    ext.language.set("kotlin")

    val task = project.tasks.getByName("generateOscSources") as GenerateOscSourcesTask
    assertEquals(
        "kotlin",
        task.language.get(),
        "タスクの language が拡張の language と一致すること",
    )
  }

  /**
   * 拡張で設定した sealed interface 名がタスクの [GenerateOscSourcesTask.sealedInterfaceName] に配線されることを検証する。
   *
   * 正常系: 拡張の sealedInterfaceName を設定し、タスクの sealedInterfaceName が同じ値であることを確認する。
   */
  @Test
  fun taskSealedInterfaceNameIsWiredFromExtension() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val ext = project.extensions.getByType(OscSchemaCodegenExtension::class.java)
    ext.sealedInterfaceName.set("OscMessages")

    val task = project.tasks.getByName("generateOscSources") as GenerateOscSourcesTask
    assertEquals(
        "OscMessages",
        task.sealedInterfaceName.get(),
        "タスクの sealedInterfaceName が拡張の sealedInterfaceName と一致すること",
    )
  }

  /**
   * 拡張で設定した出力ディレクトリがタスクの [GenerateOscSourcesTask.outputDirectory] に配線されることを検証する。
   *
   * 正常系: 拡張の outputDir を設定し、タスクの outputDirectory が同じディレクトリを指すことを確認する。
   */
  @Test
  fun taskOutputDirectoryIsWiredFromExtension() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val ext = project.extensions.getByType(OscSchemaCodegenExtension::class.java)
    val customDir = project.layout.buildDirectory.dir("custom-output")
    ext.outputDir.set(customDir)

    val task = project.tasks.getByName("generateOscSources") as GenerateOscSourcesTask
    assertEquals(
        customDir.get().asFile,
        task.outputDirectory.get().asFile,
        "タスクの outputDirectory が拡張の outputDir と一致すること",
    )
  }

  /**
   * 拡張で language を未設定のままにした場合、タスクの language がデフォルト値 "kotlin" であることを検証する。
   *
   * 正常系: language を明示的に設定せず、デフォルトの convention 値が使用されることを確認する。
   */
  @Test
  fun taskLanguageDefaultsToKotlin() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("com.oscplatform.schema-codegen")

    val task = project.tasks.getByName("generateOscSources") as GenerateOscSourcesTask
    assertEquals(
        "kotlin",
        task.language.get(),
        "language のデフォルト値が 'kotlin' であること",
    )
  }

  // ---------------------------------------------------------------
  // TestKit 機能テスト — WorkAction の動作検証
  // ---------------------------------------------------------------

  /**
   * WorkAction が既存の出力ディレクトリを削除してから再生成することを検証する。
   *
   * 正常系: 1回目の generateOscSources 実行後に出力ディレクトリへ余分なファイルを追加し、 2回目を実行した後にその余分なファイルが削除されていることを確認する。
   */
  @Test
  fun workActionDeletesExistingOutputAndRegenerates() {
    createTestProject(testDir, SIMPLE_SCHEMA)

    val runner =
        GradleRunner.create()
            .withProjectDir(testDir)
            .withPluginClasspath()
            .withArguments("generateOscSources", "--stacktrace")

    // 1回目の実行: ソース生成
    val firstResult = runner.build()
    assertEquals(
        TaskOutcome.SUCCESS,
        firstResult.task(":generateOscSources")?.outcome,
        "1回目の generateOscSources が SUCCESS であること",
    )

    val outputDir = testDir.resolve("build/generated/sources/osc/main/kotlin")
    assertTrue(outputDir.exists(), "出力ディレクトリが存在すること")

    // 出力ディレクトリに余分なファイルを手動追加
    val staleFile = outputDir.resolve("stale-artifact.kt")
    staleFile.writeText("// this should be removed on next run")
    assertTrue(staleFile.exists(), "余分なファイルが追加されたこと")

    // スキーマを微修正して再生成をトリガー（UP-TO-DATE 回避）
    val schemaFile = testDir.resolve("schema.yaml")
    schemaFile.writeText(MODIFIED_SCHEMA)

    // 2回目の実行: 再生成
    val secondResult = runner.build()
    assertEquals(
        TaskOutcome.SUCCESS,
        secondResult.task(":generateOscSources")?.outcome,
        "2回目の generateOscSources が SUCCESS であること",
    )

    // 余分なファイルが削除されていることを検証
    assertFalse(
        staleFile.exists(),
        "再生成後に余分なファイルが削除されていること",
    )

    // 生成ファイルは存在すること
    val generatedFiles =
        outputDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertTrue(generatedFiles.isNotEmpty(), "再生成後にソースファイルが存在すること")
  }

  /**
   * 不正なスキーマファイルを指定した場合にコード生成が失敗し、タスクが FAILED になることを検証する。
   *
   * 異常系: 不正な YAML を含むスキーマで generateOscSources を実行し、ビルドが失敗することを確認する。
   */
  @Test
  fun codegenExceptionFailsTask() {
    createTestProject(testDir, INVALID_SCHEMA)

    val result =
        GradleRunner.create()
            .withProjectDir(testDir)
            .withPluginClasspath()
            .withArguments("generateOscSources", "--stacktrace")
            .buildAndFail()

    val taskResult = result.task(":generateOscSources")
    assertNotNull(taskResult, "タスク結果が存在すること")
    assertEquals(
        TaskOutcome.FAILED,
        taskResult.outcome,
        "不正なスキーマでタスクが FAILED であること",
    )
  }

  /**
   * sealed interface 名を指定した場合に helper 拡張ファイルまで出力されることを検証する。
   *
   * 正常系: generateOscSources 実行後に sealed interface 本体と runtime helper の両方が生成されることを確認する。
   */
  @Test
  fun workActionGeneratesSealedRuntimeExtensionsWhenConfigured() {
    createTestProject(testDir, SIMPLE_SCHEMA, sealedInterfaceName = "OscMessages")

    val result =
        GradleRunner.create()
            .withProjectDir(testDir)
            .withPluginClasspath()
            .withArguments("generateOscSources", "--stacktrace")
            .build()

    assertEquals(
        TaskOutcome.SUCCESS,
        result.task(":generateOscSources")?.outcome,
        "sealed interface 指定時の generateOscSources が SUCCESS であること",
    )

    val outputDir = testDir.resolve("build/generated/sources/osc/main/kotlin/com/example/gen")
    assertTrue(outputDir.resolve("TestMsg.kt").exists(), "通常のメッセージクラスが生成されること")
    assertTrue(outputDir.resolve("OscMessages.kt").exists(), "sealed interface が生成されること")
    assertTrue(
        outputDir.resolve("OscMessagesRuntimeExtensions.kt").exists(),
        "runtime helper が生成されること",
    )
  }

  companion object {

    /** テストで使用するシンプルな YAML スキーマ定義。 */
    private const val SIMPLE_SCHEMA =
        """messages:
  - path: "/test/msg"
    args:
      - name: "x"
        type: "INT"
"""

    /** 再生成トリガー用の変更済みスキーマ。引数を追加して入力変更を発生させる。 */
    private const val MODIFIED_SCHEMA =
        """messages:
  - path: "/test/msg"
    args:
      - name: "x"
        type: "INT"
      - name: "y"
        type: "FLOAT"
"""

    /** コード生成の失敗を引き起こす不正なスキーマ。 */
    private const val INVALID_SCHEMA = "{{{{ not valid yaml at all !!!!"

    /**
     * テスト用 Gradle プロジェクトを指定ディレクトリに構築する。
     *
     * settings.gradle.kts、schema.yaml、build.gradle.kts を生成し、 プラグインを適用した最小構成のプロジェクトを作成する。
     *
     * @param dir プロジェクトルートとなるディレクトリ
     * @param schemaContent YAML スキーマの内容
     * @param language 生成言語 (デフォルト: "kotlin")
     * @param sealedInterfaceName 生成する sealed interface 名。未指定時は生成しない
     */
    private fun createTestProject(
        dir: File,
        schemaContent: String,
        language: String = "kotlin",
        sealedInterfaceName: String? = null,
    ) {
      dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
      dir.resolve("schema.yaml").writeText(schemaContent)
      val sealedConfig =
          sealedInterfaceName?.let {
            """
        sealedInterfaceName.set("$it")
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
  }
}
