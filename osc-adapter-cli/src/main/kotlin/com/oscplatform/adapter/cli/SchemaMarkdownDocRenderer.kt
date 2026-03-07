package com.oscplatform.adapter.cli

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscArgNode
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object SchemaMarkdownDocRenderer {
  fun render(schema: OscSchema, schemaPath: Path, title: String?): String {
    val documentTitle =
        title?.trim().takeUnless { it.isNullOrEmpty() } ?: "OSC Schema Documentation"
    val generatedAt =
        OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    val messages = schema.messages.sortedBy { it.path }
    val bundles = schema.bundles.sortedBy { it.name }

    return buildString {
      appendLine("# ${escapeInline(documentTitle)}")
      appendLine()
      appendLine("- source: `${escapeCode(schemaPath.toString())}`")
      appendLine("- generatedAt(UTC): `${escapeCode(generatedAt)}`")
      appendLine("- messages: `${messages.size}`")
      appendLine("- bundles: `${bundles.size}`")
      appendLine()

      appendLine("## Messages")
      if (messages.isEmpty()) {
        appendLine()
        appendLine("No message definitions.")
        appendLine()
      } else {
        appendLine()
        appendLine("| Name | Path | Description | Args |")
        appendLine("| --- | --- | --- | --- |")
        messages.forEach { spec ->
          appendLine(
              "| `${escapeCode(spec.name)}` | `${escapeCode(spec.path)}` | ${escapeInline(spec.description ?: "")} | `${escapeCode(formatArgSignature(spec.args))}` |")
        }
        appendLine()

        messages.forEach { spec ->
          appendLine("### `${escapeCode(spec.name)}` (`${escapeCode(spec.path)}`)")
          val description = spec.description
          if (!description.isNullOrBlank()) {
            appendLine()
            appendLine(escapeInline(description))
          }
          appendLine()
          appendLine("| Arg | Kind | Type | Constraints |")
          appendLine("| --- | --- | --- | --- |")
          spec.args.forEach { arg ->
            appendLine(
                "| `${escapeCode(arg.name)}` | ${escapeInline(kindOf(arg))} | `${escapeCode(typeOf(arg))}` | `${escapeCode(constraintsOf(arg))}` |")
          }
          appendLine()
        }
      }

      appendLine("## Bundles")
      if (bundles.isEmpty()) {
        appendLine()
        appendLine("No bundle definitions.")
        appendLine()
      } else {
        appendLine()
        appendLine("| Name | Description | Message Refs |")
        appendLine("| --- | --- | --- |")
        bundles.forEach { bundle ->
          appendLine(
              "| `${escapeCode(bundle.name)}` | ${escapeInline(bundle.description ?: "")} | `${escapeCode(bundle.messageRefs.joinToString(", "))}` |")
        }
        appendLine()
      }
    }
  }

  private fun formatArgSignature(args: List<OscArgNode>): String {
    if (args.isEmpty()) {
      return "-"
    }
    return args.joinToString(", ") { arg -> "${arg.name}:${typeOf(arg)}" }
  }

  private fun kindOf(arg: OscArgNode): String {
    return when (arg) {
      is ScalarArgNode -> "scalar"
      is ArrayArgNode -> "array"
    }
  }

  private fun typeOf(arg: OscArgNode): String {
    return when (arg) {
      is ScalarArgNode -> tokenOf(arg.type)
      is ArrayArgNode -> "array<${arrayItemTypeOf(arg.item)}>"
    }
  }

  private fun arrayItemTypeOf(item: ArrayItemSpec): String {
    return when (item) {
      is ArrayItemSpec.ScalarItem -> tokenOf(item.type)
      is ArrayItemSpec.TupleItem -> {
        val body =
            item.fields.joinToString(", ") { field -> "${field.name}:${tokenOf(field.type)}" }
        "tuple{$body}"
      }
    }
  }

  private fun constraintsOf(arg: OscArgNode): String {
    return when (arg) {
      is ScalarArgNode -> {
        if (arg.role == ScalarRole.LENGTH) {
          "role=length"
        } else {
          "-"
        }
      }

      is ArrayArgNode -> {
        when (val length = arg.length) {
          is LengthSpec.Fixed -> "length=${length.size}"
          is LengthSpec.FromField -> "lengthFrom=${length.fieldName}"
        }
      }
    }
  }

  private fun tokenOf(type: OscType): String {
    return when (type) {
      OscType.INT -> "int"
      OscType.FLOAT -> "float"
      OscType.STRING -> "string"
      OscType.BOOL -> "bool"
      OscType.BLOB -> "blob"
    }
  }

  private fun escapeInline(raw: String): String {
    return raw.replace("|", "\\|").replace("\n", "<br>").trim()
  }

  private fun escapeCode(raw: String): String {
    return raw.replace("`", "\\`")
  }
}
