package com.oscplatform.adapter.cli

import com.oscplatform.core.schema.OscArgNode
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

  /**
   * 引数リストからシグネチャ文字列をフォーマットする。
   *
   * @param args フォーマット対象の引数ノードリスト
   * @return フォーマットされたシグネチャ文字列。引数がない場合は"-"
   */
  private fun formatArgSignature(args: List<OscArgNode>): String =
      SchemaDocRenderSupport.formatArgSignature(args)

  /**
   * 引数ノードの種別を文字列で返す。
   *
   * @param arg 種別を判定する引数ノード
   * @return "scalar"または"array"
   */
  private fun kindOf(arg: OscArgNode): String = SchemaDocRenderSupport.kindOf(arg)

  /**
   * 引数ノードの型を表示用文字列に変換する。
   *
   * @param arg 型情報を取得する引数ノード
   * @return 型の表示用文字列（例: "int", "array&lt;float&gt;"）
   */
  private fun typeOf(arg: OscArgNode): String = SchemaDocRenderSupport.typeOf(arg)

  /**
   * 引数ノードの制約情報を表示用文字列に変換する。
   *
   * @param arg 制約情報を取得する引数ノード
   * @return 制約の表示用文字列（例: "role=length", "length=10", "-"）
   */
  private fun constraintsOf(arg: OscArgNode): String = SchemaDocRenderSupport.constraintsOf(arg)

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
