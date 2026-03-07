package com.oscplatform.core.schema.loader

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * スキーマファイルのパス解決を共通化したユーティリティ。
 *
 * 優先順位:
 * 1. `explicitPath` が指定されている場合はそのパスを使用する。
 * 2. カレントディレクトリの `schema.kts`, `schema.yaml`, `schema.yml` を順に探索する。
 * 3. カレントディレクトリ内の `schema` で始まる `.kts/.yaml/.yml` ファイルを辞書順で最初に使用する。
 */
object SchemaPathResolver {
  fun resolve(
      explicitPath: String?,
      warn: ((String) -> Unit)? = null,
      cwd: Path = Path.of("").toAbsolutePath().normalize(),
  ): Path {
    if (!explicitPath.isNullOrBlank()) {
      val resolved = Path.of(explicitPath).toAbsolutePath().normalize()
      require(resolved.exists() && resolved.isRegularFile()) { "Schema not found: $resolved" }
      return resolved
    }

    val priority = listOf("schema.kts", "schema.yaml", "schema.yml")
    val existingPriorityCandidates =
        priority
            .map { fileName -> cwd.resolve(fileName) }
            .filter { it.exists() && it.isRegularFile() }
    if (existingPriorityCandidates.isNotEmpty()) {
      if (existingPriorityCandidates.size >= 2) {
        val found =
            existingPriorityCandidates.joinToString(", ") { path -> path.fileName.toString() }
        warn?.invoke(
            "Multiple schema files found ($found). Using ${existingPriorityCandidates.first().fileName}")
      }
      return existingPriorityCandidates.first()
    }

    Files.list(cwd).use { stream ->
      val fallback =
          stream
              .filter { Files.isRegularFile(it) }
              .filter { path ->
                val lower = path.fileName.toString().lowercase()
                val schemaLike = lower.startsWith("schema")
                val allowedExt =
                    lower.endsWith(".kts") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                schemaLike && allowedExt
              }
              .sorted()
              .findFirst()

      if (fallback.isPresent) {
        return fallback.get()
      }
    }

    error(
        "Schema not found in current directory. Use --schema <path> or add schema.kts/schema.yaml",
    )
  }
}
