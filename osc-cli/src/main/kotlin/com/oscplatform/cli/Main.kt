package com.oscplatform.cli

import com.oscplatform.adapter.cli.CliAdapter
import com.oscplatform.adapter.mcp.McpAdapter
import com.oscplatform.adapter.webui.WebUiAdapter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private val helpFlags = setOf("help", "-h", "--help")
private val versionFlags = setOf("-V", "--version")

fun main(args: Array<String>) = runBlocking {
  val cliAdapter = CliAdapter()
  val mcpAdapter = McpAdapter()
  val webUiAdapter = WebUiAdapter()

  val exitCode =
      when (val command = args.firstOrNull()) {
        "mcp" -> mcpAdapter.execute(args.drop(1))
        "webui" -> webUiAdapter.execute(args.drop(1))
        null -> {
          printTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter)
          1
        }
        in helpFlags -> {
          printTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter)
          0
        }
        in versionFlags -> cliAdapter.execute(listOf("version"))
        in cliAdapter.commandNames() -> cliAdapter.execute(args.toList())
        else -> {
          System.err.println("error: Unknown command: $command")
          printTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter)
          1
        }
      }

  if (exitCode != 0) {
    exitProcess(exitCode)
  }
}

internal fun buildTopLevelUsage(
    cliAdapter: CliAdapter = CliAdapter(),
    mcpAdapter: McpAdapter = McpAdapter(),
    webUiAdapter: WebUiAdapter = WebUiAdapter(),
): String = buildString {
  cliAdapter.commandSummaries().forEach { appendLine(it) }
  appendLine(mcpAdapter.commandSummary())
  appendLine(webUiAdapter.commandSummary())
  appendLine("osc --version")
  append("osc help")
}

private fun printTopLevelUsage(
    cliAdapter: CliAdapter,
    mcpAdapter: McpAdapter,
    webUiAdapter: WebUiAdapter,
) {
  println(buildTopLevelUsage(cliAdapter, mcpAdapter, webUiAdapter))
}
