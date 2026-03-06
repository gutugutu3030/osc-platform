package com.oscplatform.cli

import com.oscplatform.adapter.cli.CliAdapter
import com.oscplatform.adapter.mcp.McpAdapter
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    val cliAdapter = CliAdapter()
    val mcpAdapter = McpAdapter()

    val exitCode = when (args.firstOrNull()) {
        "mcp" -> mcpAdapter.execute(args.drop(1))
        "help", "-h", "--help" -> {
            printTopLevelUsage()
            0
        }

        null -> {
            printTopLevelUsage()
            1
        }

        else -> cliAdapter.execute(args.toList())
    }

    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

private fun printTopLevelUsage() {
    println(
        """
        osc run [schemaPath] [--schema path] [--host 0.0.0.0] [--port 9000]
        osc send <messageRef> [--schema path] --host <targetHost> --port <targetPort> --arg value
        osc mcp [schemaPath] [--schema path] --host <targetHost> --port <targetPort>
        """.trimIndent(),
    )
}
