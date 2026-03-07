package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CliAdapterDocCommandTest {

  @Test
  fun docCommandGeneratesHtmlFileFromSchema() = runBlocking {
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
        """
            .trimIndent()

    val tmpSchema = Files.createTempFile("osc-doc-schema-", ".yaml")
    val tmpOutDir = Files.createTempDirectory("osc-doc-out-")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(tmpSchema, schemaYaml)
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode =
          adapter.execute(
              listOf(
                  "doc",
                  "--schema",
                  tmpSchema.toAbsolutePath().toString(),
                  "--out",
                  tmpOutDir.toAbsolutePath().toString(),
                  "--title",
                  "Schema Reference",
              ),
          )

      assertEquals(0, exitCode)
      val generated = tmpOutDir.resolve("index.html")
      assertTrue(Files.exists(generated), "index.html should be generated")

      val html = Files.readString(generated)
      assertTrue(html.contains("Schema Reference"))
      assertTrue(html.contains("/light/color"))
      assertTrue(outBuffer.toString().contains("generated schema docs:"))
      assertEquals("", errBuffer.toString())
    } finally {
      tmpSchema.deleteIfExists()
      tmpOutDir.resolve("index.html").deleteIfExists()
      tmpOutDir.deleteIfExists()
    }
  }

  @Test
  fun docCommandGeneratesMarkdownFileFromSchema() = runBlocking {
    val schemaYaml =
        """
        messages:
          - path: /mesh/points
            description: set xyz points
            args:
              - name: pointCount
                kind: scalar
                type: int
                role: length
              - name: points
                kind: array
                lengthFrom: pointCount
                items:
                  kind: tuple
                  fields:
                    - name: x
                      type: int
                    - name: y
                      type: int
                    - name: z
                      type: float
        """
            .trimIndent()

    val tmpSchema = Files.createTempFile("osc-doc-schema-", ".yaml")
    val tmpOutDir = Files.createTempDirectory("osc-doc-out-")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(tmpSchema, schemaYaml)
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode =
          adapter.execute(
              listOf(
                  "doc",
                  "--schema",
                  tmpSchema.toAbsolutePath().toString(),
                  "--format",
                  "markdown",
                  "--out",
                  tmpOutDir.toAbsolutePath().toString(),
                  "--title",
                  "Schema Markdown",
              ),
          )

      assertEquals(0, exitCode)
      val generated = tmpOutDir.resolve("index.md")
      assertTrue(Files.exists(generated), "index.md should be generated")

      val markdown = Files.readString(generated)
      assertTrue(markdown.contains("# Schema Markdown"))
      assertTrue(markdown.contains("| Name | Path | Description | Args |"))
      assertTrue(markdown.contains("/mesh/points"))
      assertTrue(outBuffer.toString().contains("generated schema docs:"))
      assertEquals("", errBuffer.toString())
    } finally {
      tmpSchema.deleteIfExists()
      tmpOutDir.resolve("index.md").deleteIfExists()
      tmpOutDir.deleteIfExists()
    }
  }

  @Test
  fun docCommandInfersMarkdownFromMdExtension() = runBlocking {
    val schemaYaml =
        """
        messages:
          - path: /device/flag
            args:
              - name: enabled
                kind: scalar
                type: bool
        """
            .trimIndent()

    val tmpSchema = Files.createTempFile("osc-doc-schema-", ".yaml")
    val tmpOutDir = Files.createTempDirectory("osc-doc-out-")
    val outFile = tmpOutDir.resolve("spec.md")

    try {
      Files.writeString(tmpSchema, schemaYaml)
      val adapter =
          CliAdapter(
              out = PrintStream(ByteArrayOutputStream()),
              err = PrintStream(ByteArrayOutputStream()))

      val exitCode =
          adapter.execute(
              listOf(
                  "doc",
                  "--schema",
                  tmpSchema.toAbsolutePath().toString(),
                  "--out",
                  outFile.toAbsolutePath().toString(),
              ),
          )

      assertEquals(0, exitCode)
      assertTrue(Files.exists(outFile), "spec.md should be generated")
      val markdown = Files.readString(outFile)
      assertTrue(markdown.contains("## Messages"))
    } finally {
      tmpSchema.deleteIfExists()
      outFile.deleteIfExists()
      tmpOutDir.deleteIfExists()
    }
  }
}
