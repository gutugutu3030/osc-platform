package com.oscplatform.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor

/**
 * OscSchema ファイルから Kotlin/Java ソースを生成する Gradle タスク。
 *
 * 出力は [outputDirectory] に書き出される。ビルドキャッシュ対応済み。
 *
 * コード生成は Worker API の classloader isolation モードで実行される。 これにより、KTS
 * スクリプトエンジン（kotlin-scripting-jsr223）が Gradle の内部 Kotlin スクリプティング基盤と衝突しないよう classpath が分離される。
 */
@CacheableTask
abstract class GenerateOscSourcesTask : DefaultTask() {

  /** 読み込むスキーマファイル (schema.yaml / schema.kts)。 */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemaFile: RegularFileProperty

  /** 生成コードのパッケージ名。 */
  @get:Input abstract val packageName: Property<String>

  /** 生成言語 (`"kotlin"` または `"java"`)。 */
  @get:Input abstract val language: Property<String>

  /** 生成されたソースの出力ディレクトリ。 */
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  /** Worker の classloader isolation に使用する classpath。 */
  @get:Classpath abstract val workerClasspath: ConfigurableFileCollection

  /** Gradle Worker API の [WorkerExecutor] インスタンス。 */
  @get:Inject abstract val workerExecutor: WorkerExecutor

  /**
   * コード生成を実行する。
   *
   * Worker API の classloader isolation モードを利用し、[GenerateOscSourcesWorkAction] をサブミットする。
   */
  @TaskAction
  fun generate() {
    val workQueue =
        workerExecutor.classLoaderIsolation { spec -> spec.classpath.from(workerClasspath) }
    workQueue.submit(GenerateOscSourcesWorkAction::class.java) { params ->
      params.schemaFile.set(schemaFile)
      params.packageName.set(packageName)
      params.language.set(language)
      params.outputDirectory.set(outputDirectory)
    }
  }
}
