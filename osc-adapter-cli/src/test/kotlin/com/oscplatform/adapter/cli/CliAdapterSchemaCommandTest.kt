package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CliAdapterSchemaCommandTest {

  @Test
  fun listCommandPrintsMessagesAndBundles() = runBlocking {
    val schemaYaml =
        """
        messages:
          - path: /light/color
            description: set RGB color
            args:
              - name: r
                kind: scalar
                type: int
              - name: g
                kind: scalar
                type: int
              - name: b
                kind: scalar
                type: int
          - path: /device/flag
            args:
              - name: enabled
                kind: scalar
                type: bool
        bundles:
          - name: set_scene
            description: update scene atomically
            messages:
              - ref: /light/color
              - ref: /device/flag
        """
            .trimIndent()

    val schemaFile = Files.createTempFile("osc-list-schema", ".yaml")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(schemaFile, schemaYaml)
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode = adapter.execute(listOf("list", schemaFile.toAbsolutePath().toString()))

      assertEquals(0, exitCode)
      val output = outBuffer.toString()
      assertTrue(output.contains("messages (2):"))
      assertTrue(output.contains("light.color -> /light/color"))
      assertTrue(output.contains("enabled:bool"))
      assertTrue(output.contains("bundles (1):"))
      assertTrue(output.contains("set_scene -> light.color, device.flag"))
      assertEquals("", errBuffer.toString())
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  @Test
  fun validateCommandPrintsSchemaSummary() = runBlocking {
    val schemaYaml =
        """
        messages:
          - path: /light/color
            args:
              - name: r
                kind: scalar
                type: int
        """
            .trimIndent()

    val schemaFile = Files.createTempFile("osc-validate-schema", ".yaml")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(schemaFile, schemaYaml)
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode =
          adapter.execute(listOf("validate", "--schema", schemaFile.toAbsolutePath().toString()))

      assertEquals(0, exitCode)
      val output = outBuffer.toString()
      assertTrue(output.contains("schema valid:"))
      assertTrue(output.contains("messages: 1"))
      assertTrue(output.contains("bundles: 0"))
      assertEquals("", errBuffer.toString())
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }
}
