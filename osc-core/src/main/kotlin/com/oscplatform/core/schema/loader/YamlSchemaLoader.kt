package com.oscplatform.core.schema.loader

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.oscplatform.core.schema.OscArgSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import java.nio.file.Path
import kotlin.io.path.inputStream

private data class YamlSchemaDocument(
    val messages: List<YamlMessage> = emptyList(),
)

private data class YamlMessage(
    val path: String = "",
    val name: String? = null,
    val description: String? = null,
    val args: List<YamlArg> = emptyList(),
)

private data class YamlArg(
    val name: String = "",
    val type: String = "",
)

class YamlSchemaLoader(
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    fun load(path: Path): OscSchema {
        val doc = path.inputStream().use { mapper.readValue<YamlSchemaDocument>(it) }
        val messages = doc.messages.map { raw ->
            val normalizedPath = OscSchema.normalizePath(raw.path)
            val resolvedName = raw.name?.takeIf { it.isNotBlank() } ?: OscNaming.defaultMessageName(normalizedPath)
            val args = raw.args.map { arg ->
                require(arg.name.isNotBlank()) { "Arg name cannot be blank in message '$normalizedPath'" }
                OscArgSpec(name = arg.name.trim(), type = OscType.fromToken(arg.type))
            }
            OscMessageSpec(
                path = normalizedPath,
                name = resolvedName,
                description = raw.description,
                args = args,
            )
        }

        return OscSchema(messages)
    }
}
