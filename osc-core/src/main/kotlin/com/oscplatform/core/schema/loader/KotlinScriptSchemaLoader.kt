package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema
import javax.script.ScriptEngineManager
import kotlin.io.path.readText

class KotlinScriptSchemaLoader {
  private val engine by lazy {
    ScriptEngineManager().getEngineByExtension("kts")
        ?: error(
            "Kotlin script engine not found. Ensure kotlin-scripting-jsr223 is on the classpath")
  }

  fun load(path: java.nio.file.Path): OscSchema {
    val source = path.readText()
    val wrappedScript = buildString {
      appendLine("import com.oscplatform.core.schema.dsl.*")
      appendLine(source)
    }

    val result = engine.eval(wrappedScript)
    return result as? OscSchema
        ?: error("Schema script must evaluate to OscSchema. Example: oscSchema { ... }")
  }
}
