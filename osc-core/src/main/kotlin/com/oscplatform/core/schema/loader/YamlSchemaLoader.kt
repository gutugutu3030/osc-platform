package com.oscplatform.core.schema.loader

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscBundleSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscNaming
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.schema.TupleFieldSpec
import java.nio.file.Path
import kotlin.io.path.inputStream

private data class YamlSchemaDocument(
    val messages: List<YamlMessage> = emptyList(),
    val bundles: List<YamlBundle> = emptyList(),
)

private data class YamlBundle(
    val name: String = "",
    val description: String? = null,
    val messages: List<YamlBundleMessage> = emptyList(),
)

private data class YamlBundleMessage(
    val ref: String = "",
)

private data class YamlMessage(
    val path: String = "",
    val name: String? = null,
    val description: String? = null,
    val args: List<YamlArg> = emptyList(),
)

private data class YamlArg(
    val name: String = "",
    val kind: String? = null,
    val type: String? = null,
    val role: String? = null,
    val length: Int? = null,
    val lengthFrom: String? = null,
    val items: YamlArrayItems? = null,
)

private data class YamlArrayItems(
    val kind: String? = null,
    val type: String? = null,
    val fields: List<YamlTupleField> = emptyList(),
)

private data class YamlTupleField(
    val name: String = "",
    val type: String = "",
)

val test =     YAMLFactory()

class YamlSchemaLoader(
    private val mapper: ObjectMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build(),
) {
    fun load(path: Path): OscSchema {
        val doc = path.inputStream().use { mapper.readValue<YamlSchemaDocument>(it) }
        val messages = doc.messages.map { raw ->
            val normalizedPath = OscSchema.normalizePath(raw.path)
            val resolvedName = raw.name?.takeIf { it.isNotBlank() } ?: OscNaming.defaultMessageName(normalizedPath)
            val args = raw.args.map { arg ->
                parseArg(arg = arg, messagePath = normalizedPath)
            }
            OscMessageSpec(
                path = normalizedPath,
                name = resolvedName,
                description = raw.description,
                args = args,
            )
        }

        val bundles = doc.bundles.map { raw ->
            require(raw.name.isNotBlank()) { "Bundle name cannot be blank" }
            require(raw.messages.isNotEmpty()) { "Bundle '${raw.name}' must reference at least one message" }
            OscBundleSpec(
                name = raw.name.trim(),
                description = raw.description,
                messageRefs = raw.messages.map { m ->
                    require(m.ref.isNotBlank()) { "Bundle '${raw.name}': message ref cannot be blank" }
                    m.ref.trim()
                },
            )
        }

        return OscSchema(messages = messages, bundles = bundles)
    }

    private fun parseArg(arg: YamlArg, messagePath: String): com.oscplatform.core.schema.OscArgNode {
        require(arg.name.isNotBlank()) { "Arg name cannot be blank in message '$messagePath'" }

        val kind = arg.kind?.trim()?.lowercase() ?: "scalar"
        return when (kind) {
            "scalar" -> {
                val token = arg.type?.trim().orEmpty()
                require(token.isNotBlank()) {
                    "Scalar arg '${arg.name}' in '$messagePath' must define type"
                }
                val role = when (arg.role?.trim()?.lowercase()) {
                    null, "", "value" -> ScalarRole.VALUE
                    "length" -> ScalarRole.LENGTH
                    else -> throw IllegalArgumentException(
                        "Unsupported scalar role '${arg.role}' for '${arg.name}' in '$messagePath'",
                    )
                }
                ScalarArgNode(name = arg.name.trim(), type = OscType.fromToken(token), role = role)
            }

            "array" -> {
                require(!(arg.length != null && !arg.lengthFrom.isNullOrBlank())) {
                    "Arg '${arg.name}' in '$messagePath' cannot define both length and lengthFrom"
                }

                val length = when {
                    arg.length != null -> LengthSpec.Fixed(arg.length)
                    !arg.lengthFrom.isNullOrBlank() -> LengthSpec.FromField(arg.lengthFrom.trim())
                    else -> throw IllegalArgumentException(
                        "Array arg '${arg.name}' in '$messagePath' must define either length or lengthFrom",
                    )
                }

                val item = parseArrayItem(arg = arg, messagePath = messagePath)
                ArrayArgNode(name = arg.name.trim(), length = length, item = item)
            }

            else -> throw IllegalArgumentException(
                "Unsupported arg kind '${arg.kind}' for '${arg.name}' in '$messagePath'",
            )
        }
    }

    private fun parseArrayItem(arg: YamlArg, messagePath: String): ArrayItemSpec {
        val items = arg.items ?: throw IllegalArgumentException(
            "Array arg '${arg.name}' in '$messagePath' must define items",
        )

        val kind = items.kind?.trim()?.lowercase() ?: if (!items.type.isNullOrBlank()) "scalar" else "tuple"
        return when (kind) {
            "scalar" -> {
                val token = items.type?.trim().orEmpty()
                require(token.isNotBlank()) {
                    "Array arg '${arg.name}' in '$messagePath' with scalar items must define items.type"
                }
                ArrayItemSpec.ScalarItem(type = OscType.fromToken(token))
            }

            "tuple" -> {
                val fields = items.fields.map { field ->
                    require(field.name.isNotBlank()) {
                        "Tuple field name cannot be blank in '${arg.name}' for '$messagePath'"
                    }
                    TupleFieldSpec(name = field.name.trim(), type = OscType.fromToken(field.type))
                }
                ArrayItemSpec.TupleItem(fields = fields)
            }

            else -> throw IllegalArgumentException(
                "Unsupported items.kind '${items.kind}' for '${arg.name}' in '$messagePath'",
            )
        }
    }
}
