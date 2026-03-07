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

internal object SchemaHtmlDocRenderer {
  fun render(schema: OscSchema, schemaPath: Path, title: String?): String {
    val documentTitle =
        title?.trim().takeUnless { it.isNullOrEmpty() } ?: "OSC Schema Documentation"
    val generatedAt =
        OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    val messages = schema.messages.sortedBy { it.path }
    val bundles = schema.bundles.sortedBy { it.name }

    return buildString {
      appendLine("<!doctype html>")
      appendLine("<html lang=\"en\">")
      appendLine("<head>")
      appendLine("  <meta charset=\"utf-8\">")
      appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
      appendLine("  <title>${escape(documentTitle)}</title>")
      appendLine("  <style>")
      appendLine("    :root {")
      appendLine("      color-scheme: light;")
      appendLine("      --bg: #f8fafc;")
      appendLine("      --card: #ffffff;")
      appendLine("      --text: #0f172a;")
      appendLine("      --muted: #475569;")
      appendLine("      --line: #d1d5db;")
      appendLine("      --accent: #0f766e;")
      appendLine("    }")
      appendLine("    * { box-sizing: border-box; }")
      appendLine("    body {")
      appendLine("      margin: 0;")
      appendLine("      font-family: 'IBM Plex Sans', 'Noto Sans', 'Segoe UI', sans-serif;")
      appendLine("      background: linear-gradient(180deg, #f8fafc 0%, #eef2ff 100%);")
      appendLine("      color: var(--text);")
      appendLine("      line-height: 1.5;")
      appendLine("    }")
      appendLine("    main {")
      appendLine("      max-width: 1100px;")
      appendLine("      margin: 0 auto;")
      appendLine("      padding: 2rem 1rem 4rem;")
      appendLine("    }")
      appendLine("    h1 { margin: 0 0 0.5rem; font-size: 2rem; }")
      appendLine("    h2 { margin: 2rem 0 0.75rem; font-size: 1.4rem; }")
      appendLine("    h3 { margin: 1.5rem 0 0.5rem; font-size: 1.1rem; }")
      appendLine("    p.meta { margin: 0.2rem 0; color: var(--muted); }")
      appendLine("    .summary {")
      appendLine("      display: grid;")
      appendLine("      gap: 0.75rem;")
      appendLine("      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));")
      appendLine("      margin-top: 1rem;")
      appendLine("    }")
      appendLine("    .card {")
      appendLine("      background: var(--card);")
      appendLine("      border: 1px solid var(--line);")
      appendLine("      border-radius: 10px;")
      appendLine("      padding: 0.85rem 1rem;")
      appendLine("      box-shadow: 0 4px 14px rgba(15, 23, 42, 0.06);")
      appendLine("    }")
      appendLine("    .card .label { color: var(--muted); font-size: 0.85rem; }")
      appendLine("    .card .value { font-size: 1.4rem; font-weight: 700; }")
      appendLine("    table {")
      appendLine("      width: 100%;")
      appendLine("      border-collapse: collapse;")
      appendLine("      background: var(--card);")
      appendLine("      border: 1px solid var(--line);")
      appendLine("      border-radius: 10px;")
      appendLine("      overflow: hidden;")
      appendLine("    }")
      appendLine("    th, td {")
      appendLine("      border-bottom: 1px solid var(--line);")
      appendLine("      text-align: left;")
      appendLine("      padding: 0.55rem 0.65rem;")
      appendLine("      vertical-align: top;")
      appendLine("      font-size: 0.95rem;")
      appendLine("    }")
      appendLine("    th { background: #f1f5f9; font-weight: 700; }")
      appendLine("    tr:last-child td { border-bottom: none; }")
      appendLine("    code {")
      appendLine("      background: #eef2ff;")
      appendLine("      border: 1px solid #dbeafe;")
      appendLine("      border-radius: 6px;")
      appendLine("      padding: 0.1rem 0.35rem;")
      appendLine("      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;")
      appendLine("      font-size: 0.9em;")
      appendLine("    }")
      appendLine("    a { color: var(--accent); text-decoration: none; }")
      appendLine("    a:hover { text-decoration: underline; }")
      appendLine("    .message-section {")
      appendLine("      margin-top: 1rem;")
      appendLine("      padding: 0.8rem;")
      appendLine("      border: 1px solid var(--line);")
      appendLine("      border-radius: 10px;")
      appendLine("      background: #ffffff;")
      appendLine("    }")
      appendLine("    .empty { color: var(--muted); }")
      appendLine("    @media (max-width: 760px) {")
      appendLine("      th, td { font-size: 0.88rem; }")
      appendLine("      h1 { font-size: 1.6rem; }")
      appendLine("    }")
      appendLine("  </style>")
      appendLine("</head>")
      appendLine("<body>")
      appendLine("<main>")
      appendLine("  <h1>${escape(documentTitle)}</h1>")
      appendLine("  <p class=\"meta\">source: <code>${escape(schemaPath.toString())}</code></p>")
      appendLine("  <p class=\"meta\">generatedAt(UTC): <code>${escape(generatedAt)}</code></p>")
      appendLine("  <div class=\"summary\">")
      appendLine(
          "    <div class=\"card\"><div class=\"label\">Messages</div><div class=\"value\">${messages.size}</div></div>")
      appendLine(
          "    <div class=\"card\"><div class=\"label\">Bundles</div><div class=\"value\">${bundles.size}</div></div>")
      appendLine("  </div>")

      appendLine("  <h2>Messages</h2>")
      if (messages.isEmpty()) {
        appendLine("  <p class=\"empty\">No message definitions.</p>")
      } else {
        appendLine("  <table>")
        appendLine("    <thead>")
        appendLine("      <tr><th>Name</th><th>Path</th><th>Description</th><th>Args</th></tr>")
        appendLine("    </thead>")
        appendLine("    <tbody>")
        messages.forEach { spec ->
          val anchor = anchorId(spec.name)
          appendLine(
              "      <tr><td><a href=\"#$anchor\"><code>${escape(spec.name)}</code></a></td><td><code>${escape(spec.path)}</code></td><td>${escape(spec.description ?: "")}</td><td>${escape(formatArgSignature(spec.args))}</td></tr>")
        }
        appendLine("    </tbody>")
        appendLine("  </table>")

        messages.forEach { spec ->
          appendLine("  <section class=\"message-section\" id=\"${anchorId(spec.name)}\">")
          appendLine(
              "    <h3><code>${escape(spec.name)}</code> <small>(<code>${escape(spec.path)}</code>)</small></h3>")
          val description = spec.description
          if (!description.isNullOrBlank()) {
            appendLine("    <p>${escape(description)}</p>")
          }
          appendLine("    <table>")
          appendLine("      <thead>")
          appendLine("        <tr><th>Arg</th><th>Kind</th><th>Type</th><th>Constraints</th></tr>")
          appendLine("      </thead>")
          appendLine("      <tbody>")
          spec.args.forEach { arg ->
            appendLine(
                "        <tr><td><code>${escape(arg.name)}</code></td><td>${escape(kindOf(arg))}</td><td>${escape(typeOf(arg))}</td><td>${escape(constraintsOf(arg))}</td></tr>")
          }
          appendLine("      </tbody>")
          appendLine("    </table>")
          appendLine("  </section>")
        }
      }

      appendLine("  <h2>Bundles</h2>")
      if (bundles.isEmpty()) {
        appendLine("  <p class=\"empty\">No bundle definitions.</p>")
      } else {
        appendLine("  <table>")
        appendLine("    <thead>")
        appendLine("      <tr><th>Name</th><th>Description</th><th>Message Refs</th></tr>")
        appendLine("    </thead>")
        appendLine("    <tbody>")
        bundles.forEach { bundle ->
          appendLine(
              "      <tr><td><code>${escape(bundle.name)}</code></td><td>${escape(bundle.description ?: "")}</td><td>${escape(bundle.messageRefs.joinToString(", "))}</td></tr>")
        }
        appendLine("    </tbody>")
        appendLine("  </table>")
      }

      appendLine("</main>")
      appendLine("</body>")
      appendLine("</html>")
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

  private fun anchorId(name: String): String {
    return "msg-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
  }

  private fun escape(raw: String): String {
    return raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
  }
}
