package com.oscplatform.adapter.cli

import com.oscplatform.core.schema.OscSchema
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * OSCスキーマからMarkdown形式のドキュメントを生成するレンダラー。
 *
 * メッセージ定義やバンドル定義をMarkdownテーブルとして出力する。
 */
internal object SchemaMarkdownDocRenderer {
  /**
   * OSCスキーマからMarkdown形式のドキュメント文字列を生成する。
   *
   * @param schema レンダリング対象のOSCスキーマ
   * @param schemaPath スキーマファイルのパス（ドキュメントのメタ情報に使用）
   * @param title ドキュメントのタイトル。nullまたは空の場合はデフォルトタイトルを使用
   * @return 生成されたMarkdown文字列
   */
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
              "| `${escapeCode(spec.name)}` | `${escapeCode(spec.path)}` | ${escapeInline(spec.description ?: "")} | `${escapeCode(spec.args.formatArgSignature())}` |")
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
                "| `${escapeCode(arg.name)}` | ${escapeInline(arg.kindLabel())} | `${escapeCode(arg.typeLabel())}` | `${escapeCode(arg.constraintsLabel())}` |")
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

  /**
   * Markdownのインラインテキストをエスケープする。
   *
   * @param raw エスケープ対象の生文字列
   * @return Markdownテーブルセル内で安全な文字列
   */
  private fun escapeInline(raw: String): String {
    return raw.replace("|", "\\|").replace("\n", "<br>").trim()
  }

  /**
   * Markdownのコードスパン内の文字列をエスケープする。
   *
   * @param raw エスケープ対象の生文字列
   * @return バッククォート内で安全な文字列
   */
  private fun escapeCode(raw: String): String {
    return raw.replace("`", "\\`")
  }
}
