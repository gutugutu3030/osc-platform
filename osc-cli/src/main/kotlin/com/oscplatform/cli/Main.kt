package com.oscplatform.cli

import com.oscplatform.adapter.cli.CliAdapter
import com.oscplatform.adapter.mcp.McpAdapter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private val helpFlags = setOf("help", "-h", "--help")
private val versionFlags = setOf("-V", "--version")

fun main(args: Array<String>) = runBlocking {
  val cliAdapter = CliAdapter()
  val mcpAdapter = McpAdapter()

  val exitCode =
      when (val command = args.firstOrNull()) {
        "mcp" -> mcpAdapter.execute(args.drop(1))
        null -> {
          printTopLevelUsage(cliAdapter, mcpAdapter)
          1
        }
        in helpFlags -> {
          printTopLevelUsage(cliAdapter, mcpAdapter)
          0
        }
        in versionFlags -> cliAdapter.execute(listOf("version"))
        in cliAdapter.commandNames() -> cliAdapter.execute(args.toList())
        else -> {
          System.err.println("error: Unknown command: $command")
          printTopLevelUsage(cliAdapter, mcpAdapter)
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
): String = buildString {
  cliAdapter.commandSummaries().forEach { appendLine(it) }
  appendLine(mcpAdapter.commandSummary())
  appendLine("osc --version")
  append("osc help")
}

private fun printTopLevelUsage(cliAdapter: CliAdapter, mcpAdapter: McpAdapter) {
  println(buildTopLevelUsage(cliAdapter, mcpAdapter))
}
