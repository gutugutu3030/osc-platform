package com.oscplatform.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Gradle TestKit を使用した [OscSchemaCodegenPlugin] の機能テスト。
 *
 * 一時ディレクトリにテスト用プロジェクトを構築し、実際の Gradle ビルドを通してプラグインの動作を検証する。
 */
class GradlePluginFunctionalTest {

  /** テスト用一時ディレクトリ。各テストの後にクリーンアップされる。 */
  private val testDir: File = Files.createTempDirectory("osc-gradle-plugin-test").toFile()

  /** テスト終了後に一時ディレクトリを再帰的に削除する。 */
  @AfterTest
  fun cleanup() {
    testDir.deleteRecursively()
  }

  /**
   * 有効な YAML スキーマで generateOscSources タスクが成功することを検証する。
   *
   * 正常系: タスクの結果が [TaskOutcome.SUCCESS] であることを確認する。
   */
  @Test
  fun generateTaskSucceedsWithValidSchema() {
    createTestProject(testDir, SIMPLE_SCHEMA)

    val result =
        GradleRunner.create()
            .withProjectDir(testDir)
            .withPluginClasspath()
            .withArguments("generateOscSources", "--stacktrace")
            .build()

    assertEquals(
        TaskOutcome.SUCCESS,
        result.task(":generateOscSources")?.outcome,
        "generateOscSources タスクが SUCCESS であること",
    )
  }

  /**
   * generateOscSources タスクがソースファイルを出力ディレクトリに生成することを検証する。
   *
   * 正常系: ビルド後に出力ディレクトリにファイルが存在し、パッケージ宣言を含むことを確認する。
   */
  @Test
  fun generateTaskProducesOutputFiles() {
    createTestProject(testDir, SIMPLE_SCHEMA)

    GradleRunner.create()
        .withProjectDir(testDir)
        .withPluginClasspath()
        .withArguments("generateOscSources", "--stacktrace")
        .build()

    val outputDir = testDir.resolve("build/generated/sources/osc/main/kotlin")
    assertTrue(outputDir.exists(), "出力ディレクトリが存在すること")

    // 生成されたファイルの存在確認
    val generatedFiles =
        outputDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertTrue(generatedFiles.isNotEmpty(), "少なくとも1つの Kotlin ファイルが生成されていること")

    // パッケージ宣言の確認
    val hasPackageDeclaration =
        generatedFiles.any { it.readText().contains("package com.example.gen") }
    assertTrue(hasPackageDeclaration, "生成されたファイルに正しいパッケージ宣言が含まれること")
  }

  /**
   * sealed interface 名を指定した場合に helper 拡張ファイルまで生成されることを検証する。
   *
   * 正常系: ビルド後に sealed interface 本体と runtime helper の両方が出力されることを確認する。
   */
  @Test
  fun generateTaskProducesSealedRuntimeExtensionFiles() {
    createTestProject(testDir, SIMPLE_SCHEMA, sealedInterfaceName = "OscMessages")

    GradleRunner.create()
        .withProjectDir(testDir)
        .withPluginClasspath()
        .withArguments("generateOscSources", "--stacktrace")
        .build()

    val outputDir = testDir.resolve("build/generated/sources/osc/main/kotlin/com/example/gen")
    assertTrue(outputDir.resolve("TestMsg.kt").exists(), "通常のメッセージクラスが生成されること")
    assertTrue(outputDir.resolve("OscMessages.kt").exists(), "sealed interface が生成されること")
    assertTrue(
        outputDir.resolve("OscMessagesRuntimeExtensions.kt").exists(),
        "runtime helper が生成されること",
    )
  }

  /**
   * sealed interface 名を未指定の場合に helper 拡張ファイルが生成されないことを検証する。
   *
   * 異常系: sealed 導線を有効化していないビルドでは追加 helper が出力されないことを確認する。
   */
  @Test
  fun generateTaskDoesNotProduceSealedRuntimeExtensionFilesByDefault() {
    createTestProject(testDir, SIMPLE_SCHEMA)

    GradleRunner.create()
        .withProjectDir(testDir)
        .withPluginClasspath()
        .withArguments("generateOscSources", "--stacktrace")
        .build()

    val outputDir = testDir.resolve("build/generated/sources/osc/main/kotlin/com/example/gen")
    assertTrue(outputDir.resolve("TestMsg.kt").exists(), "通常のメッセージクラスが生成されること")
    assertTrue(
        !outputDir.resolve("OscMessagesRuntimeExtensions.kt").exists(),
        "sealed 未指定時は runtime helper が生成されないこと",
    )
  }

  /**
   * サポートされていない言語を指定した場合にビルドが失敗することを検証する。
   *
   * 異常系: language に "java" を指定し、ビルドが失敗してエラーメッセージに "Unsupported language" が含まれることを確認する。
   */
  @Test
  fun unsupportedLanguageFailsBuild() {
    createTestProject(testDir, SIMPLE_SCHEMA, language = "java")

    val result =
        GradleRunner.create()
            .withProjectDir(testDir)
            .withPluginClasspath()
            .withArguments("generateOscSources", "--stacktrace")
            .buildAndFail()

    assertTrue(
        result.output.contains("Unsupported language"),
        "エラー出力に 'Unsupported language' が含まれること: ${result.output}",
    )
  }

  /**
   * スキーマ未変更時に2回目の実行が UP-TO-DATE になることを検証する。
   *
   * 正常系: 同じ入力で2回ビルドし、2回目のタスク結果が [TaskOutcome.UP_TO_DATE] であることを確認する。
   */
  @Test
  fun secondRunIsUpToDate() {
    createTestProject(testDir, SIMPLE_SCHEMA)

    val runner =
        GradleRunner.create()
            .withProjectDir(testDir)
            .withPluginClasspath()
            .withArguments("generateOscSources", "--stacktrace")

    // 1回目: 初回生成
    val firstResult = runner.build()
    assertEquals(
        TaskOutcome.SUCCESS,
        firstResult.task(":generateOscSources")?.outcome,
        "1回目の実行が SUCCESS であること",
    )

    // 2回目: キャッシュヒット
    val secondResult = runner.build()
    assertEquals(
        TaskOutcome.UP_TO_DATE,
        secondResult.task(":generateOscSources")?.outcome,
        "2回目の実行が UP_TO_DATE であること",
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
  }
}
