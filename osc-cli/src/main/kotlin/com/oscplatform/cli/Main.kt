package com.oscplatform.cli

import com.oscplatform.adapter.cli.CliAdapter
import com.oscplatform.adapter.mcp.McpAdapter
import com.oscplatform.adapter.webui.SchemaEditorAdapter
import com.oscplatform.adapter.webui.WebUiAdapter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** ヘルプ表示を要求するフラグの集合。 */
private val helpFlags = setOf("help", "-h", "--help")

/** バージョン表示を要求するフラグの集合。 */
private val versionFlags = setOf("-V", "--version")

/**
 * OSC CLI のエントリーポイント。
 *
 * コマンドライン引数を解析し、対応するアダプターにディスパッチする。
 *
 * @param args コマンドライン引数
 */
fun main(args: Array<String>) = runBlocking {
  val cliAdapter = CliAdapter()
  val mcpAdapter = McpAdapter()
  val webUiAdapter = WebUiAdapter()
  val editorAdapter = SchemaEditorAdapter()

  val exitCode =
      when (val command = args.firstOrNull()) {
        "mcp" -> mcpAdapter.execute(args.drop(1))
        "webui" -> webUiAdapter.execute(args.drop(1))
        "editor" -> editorAdapter.execute(args.drop(1))
        null -> {
          printTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter, editorAdapter)
          1
        }
        in helpFlags -> {
          printTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter, editorAdapter)
          0
        }
        in versionFlags -> cliAdapter.execute(listOf("version"))
        in cliAdapter.commandNames() -> cliAdapter.execute(args.toList())
        else -> {
          System.err.println("error: Unknown command: $command")
          printTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter, editorAdapter)
          1
        }
      }

  if (exitCode != 0) {
    exitProcess(exitCode)
  }
}

/**
 * トップレベルの使用方法テキストを構築する。
 *
 * 各アダプターのコマンドサマリーを結合した文字列を返す。
 *
 * @param cliAdapter CLI アダプター
 * @param mcpAdapter MCP アダプター
 * @param webUiAdapter Web UI アダプター
 * @param editorAdapter スキーマエディタアダプター
 * @return 使用方法のテキスト
 */
internal fun buildTopLevelUsage(
    cliAdapter: CliAdapter = CliAdapter(),
    mcpAdapter: McpAdapter = McpAdapter(),
    webUiAdapter: WebUiAdapter = WebUiAdapter(),
    editorAdapter: SchemaEditorAdapter = SchemaEditorAdapter(),
): String = buildString {
  cliAdapter.commandSummaries().forEach { appendLine(it) }
  appendLine(mcpAdapter.commandSummary())
  appendLine(webUiAdapter.commandSummary())
  appendLine(editorAdapter.commandSummary())
  appendLine("osc --version")
  append("osc help")
}

/**
 * トップレベルの使用方法を標準出力に表示する。
 *
 * @param cliAdapter CLI アダプター
 * @param mcpAdapter MCP アダプター
 * @param webUiAdapter Web UI アダプター
 * @param editorAdapter スキーマエディタアダプター
 */
private fun printTopLevelUsage(
    cliAdapter: CliAdapter,
    mcpAdapter: McpAdapter,
    webUiAdapter: WebUiAdapter,
    editorAdapter: SchemaEditorAdapter,
) {
  println(buildTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter, editorAdapter))
}
