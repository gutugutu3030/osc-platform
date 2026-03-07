package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliAdapterExecuteTest {
    @Test
    fun executeHelpReturnsZeroAndPrintsUsage() {
        val outBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
        val errBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
        val adapter = CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

        val exitCode: Int = runBlocking {
            adapter.execute(listOf("help"))
        }

        assertEquals(0, exitCode)
        assertTrue(outBuffer.toString().contains("osc run"))
        assertEquals("", errBuffer.toString())
    }

    @Test
    fun executeUnknownCommandReturnsOneAndPrintsError() {
        val outBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
        val errBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
        val adapter = CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

        val exitCode: Int = runBlocking {
            adapter.execute(listOf("unknown"))
        }

        assertEquals(1, exitCode)
        assertTrue(errBuffer.toString().contains("Unknown command"))
        assertTrue(outBuffer.toString().contains("osc run"))
    }

    @Test
    fun executeWithoutArgsReturnsOneAndPrintsUsage() {
        val outBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
        val errBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
        val adapter = CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

        val exitCode: Int = runBlocking {
            adapter.execute(emptyList())
        }

        assertEquals(1, exitCode)
        assertTrue(outBuffer.toString().contains("osc send"))
        assertEquals("", errBuffer.toString())
    }
}
