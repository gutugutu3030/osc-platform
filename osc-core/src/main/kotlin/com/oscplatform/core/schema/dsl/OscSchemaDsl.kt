package com.oscplatform.core.schema.dsl

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.schema.TupleFieldSpec

val INT: OscType = OscType.INT
val FLOAT: OscType = OscType.FLOAT
val STRING: OscType = OscType.STRING
val VALUE: ScalarRole = ScalarRole.VALUE
val LENGTH: ScalarRole = ScalarRole.LENGTH

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
    private val args = mutableListOf<OscArgNode>()

    fun name(value: String) {
        explicitName = value.trim()
    }

    fun description(value: String) {
        textDescription = value.trim()
    }

    fun scalar(
        name: String,
        type: OscType,
        role: ScalarRole = ScalarRole.VALUE,
    ) {
        args += ScalarArgNode(name = name.trim(), type = type, role = role)
    }

    fun arg(name: String, type: OscType) {
        scalar(name = name, type = type)
    }

    fun array(
        name: String,
        length: Int? = null,
        lengthFrom: String? = null,
        block: ArrayItemBuilder.() -> Unit,
    ) {
        require(!(length != null && !lengthFrom.isNullOrBlank())) {
            "array('$name') cannot define both length and lengthFrom"
        }

        val resolvedLength = when {
            length != null -> LengthSpec.Fixed(length)
            !lengthFrom.isNullOrBlank() -> LengthSpec.FromField(lengthFrom.trim())
            else -> throw IllegalArgumentException("array('$name') must define either length or lengthFrom")
        }

        val item = ArrayItemBuilder().apply(block).build()
        args += ArrayArgNode(name = name.trim(), length = resolvedLength, item = item)
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

class ArrayItemBuilder {
    private var item: ArrayItemSpec? = null

    fun scalar(type: OscType) {
        require(item == null) { "Array item is already defined" }
        item = ArrayItemSpec.ScalarItem(type)
    }

    fun tuple(block: TupleFieldBuilder.() -> Unit) {
        require(item == null) { "Array item is already defined" }
        val fields = TupleFieldBuilder().apply(block).build()
        item = ArrayItemSpec.TupleItem(fields = fields)
    }

    internal fun build(): ArrayItemSpec {
        return item ?: throw IllegalArgumentException("Array item definition is required")
    }
}

class TupleFieldBuilder {
    private val fields = mutableListOf<TupleFieldSpec>()

    fun field(name: String, type: OscType) {
        fields += TupleFieldSpec(name = name.trim(), type = type)
    }

    internal fun build(): List<TupleFieldSpec> = fields.toList()
}

fun oscSchema(block: OscSchemaBuilder.() -> Unit): OscSchema {
    val builder = OscSchemaBuilder()
    builder.block()
    return builder.build()
}
