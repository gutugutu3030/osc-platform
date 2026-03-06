package com.oscplatform.core.schema.dsl

import com.oscplatform.core.schema.OscArgSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType

val INT: OscType = OscType.INT
val FLOAT: OscType = OscType.FLOAT
val STRING: OscType = OscType.STRING

class OscSchemaBuilder {
    private val messages = mutableListOf<OscMessageSpec>()

    fun message(path: String, block: OscMessageBuilder.() -> Unit) {
        val builder = OscMessageBuilder(path)
        builder.block()
        messages += builder.build()
    }

    internal fun build(): OscSchema = OscSchema(messages.toList())
}

class OscMessageBuilder(
    private val rawPath: String,
) {
    private var explicitName: String? = null
    private var textDescription: String? = null
    private val args = mutableListOf<OscArgSpec>()

    fun name(value: String) {
        explicitName = value.trim()
    }

    fun description(value: String) {
        textDescription = value.trim()
    }

    fun arg(name: String, type: OscType) {
        args += OscArgSpec(name = name.trim(), type = type)
    }

    internal fun build(): OscMessageSpec {
        val normalizedPath = OscSchema.normalizePath(rawPath)
        val resolvedName = explicitName?.takeIf { it.isNotBlank() } ?: OscNaming.defaultMessageName(normalizedPath)
        val duplicateArgs = args.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateArgs.isEmpty()) { "Duplicate args for '$normalizedPath': ${duplicateArgs.joinToString()}" }

        return OscMessageSpec(
            path = normalizedPath,
            name = resolvedName,
            description = textDescription,
            args = args.toList(),
        )
    }
}

fun oscSchema(block: OscSchemaBuilder.() -> Unit): OscSchema {
    val builder = OscSchemaBuilder()
    builder.block()
    return builder.build()
}
