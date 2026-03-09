package com.oscplatform.gradle

import com.oscplatform.codegen.CodeGenOptions
import com.oscplatform.codegen.OscCodegen
import java.nio.charset.StandardCharsets
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * OscSchema ファイルから Kotlin/Java ソースを生成する Gradle タスク。
 *
 * 出力は [outputDirectory] に書き出される。ビルドキャッシュ対応済み。
 */
@CacheableTask
abstract class GenerateOscSourcesTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemaFile: RegularFileProperty

  @get:Input abstract val packageName: Property<String>

  @get:Input abstract val language: Property<String>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val schemaPath = schemaFile.get().asFile.toPath()
    val options = CodeGenOptions(packageName = packageName.get(), language = language.get())
    val files = OscCodegen.generateFromFile(schemaPath, options)

    val outDir = outputDirectory.get().asFile
    outDir.deleteRecursively()
    outDir.mkdirs()

    files.forEach { (relativePath, content) ->
      val file = outDir.resolve(relativePath)
      file.parentFile.mkdirs()
      file.writeText(content, StandardCharsets.UTF_8)
    }

    logger.lifecycle("generateOscSources: ${files.size} file(s) → ${outDir.path}")
  }
}
