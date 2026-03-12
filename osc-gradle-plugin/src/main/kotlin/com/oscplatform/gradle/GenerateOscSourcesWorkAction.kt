package com.oscplatform.gradle

import com.oscplatform.codegen.CodeGenOptions
import com.oscplatform.codegen.OscCodegen
import java.nio.charset.StandardCharsets
import org.gradle.api.logging.Logging
import org.gradle.workers.WorkAction

/**
 * classloader isolation モードで実行されるコード生成 Worker。
 *
 * Gradle の内部 Kotlin スクリプティング基盤と衝突しないよう、 KTS スクリプトエンジン（kotlin-scripting-jsr223）は 専用の isolated
 * classloader 内でのみロードされる。
 */
abstract class GenerateOscSourcesWorkAction : WorkAction<GenerateOscSourcesWorkParameters> {

  private val logger = Logging.getLogger(GenerateOscSourcesWorkAction::class.java)

  override fun execute() {
    val schemaPath = parameters.schemaFile.get().asFile.toPath()
    val options =
        CodeGenOptions(
            packageName = parameters.packageName.get(),
            language = parameters.language.get(),
        )
    val files = OscCodegen.generateFromFile(schemaPath, options)

    val outDir = parameters.outputDirectory.get().asFile
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
