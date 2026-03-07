package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema
import java.nio.file.Path
import kotlin.io.path.extension

class SchemaLoader(
    private val yamlLoader: YamlSchemaLoader = YamlSchemaLoader(),
    private val scriptLoader: KotlinScriptSchemaLoader = KotlinScriptSchemaLoader(),
) {
  fun load(path: Path): OscSchema {
    return when (path.extension.lowercase()) {
      "yaml",
      "yml" -> yamlLoader.load(path)
      "kts" -> scriptLoader.load(path)
      else -> error("Unsupported schema extension: ${path.fileName}")
    }
  }
}
