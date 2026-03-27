package com.oscplatform.adapter.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [CliAdapter] の gen コマンドを検証するテスト。
 *
 * 検証内容:
 * - 有効なスキーマとオプションで正常にコード生成される
 * - --package 未指定でエラー終了する
 * - 非対応の --lang でエラー終了する
 * - 不明オプションでエラー終了する
 */
class CliAdapterGenCommandTest {

  /**
   * テスト用のYAMLスキーマ文字列を返す。
   *
   * @return テスト用スキーマのYAML文字列
   */
  private fun testSchemaYaml(): String =
      """
      messages:
        - path: "/test/msg"
          args:
            - name: "x"
              type: "INT"
      """
          .trimIndent()

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  /** 有効なYAMLスキーマ・--package・--out でコード生成が成功し、ファイルが出力される */
  @Test
  fun genCommandWithValidOptionsProducesFilesAndExitCodeZero() {
    val schemaFile = Files.createTempFile("osc-gen-schema-", ".yaml")
    val outDir = Files.createTempDirectory("osc-gen-out-")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(schemaFile, testSchemaYaml())
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode: Int = runBlocking {
        adapter.execute(
            listOf(
                "gen",
                "--schema",
                schemaFile.toAbsolutePath().toString(),
                "--package",
                "com.example.generated",
                "--out",
                outDir.toAbsolutePath().toString(),
            ),
        )
      }

      assertEquals(0, exitCode)
      val output = outBuffer.toString()
      assertTrue(output.contains("gen complete:"))
      assertTrue(output.contains("file(s)"))
      assertEquals("", errBuffer.toString())

      // 出力ディレクトリにファイルが生成されていることを確認
      val generatedFiles =
          Files.walk(outDir).use { stream -> stream.filter { Files.isRegularFile(it) }.count() }
      assertTrue(generatedFiles > 0, "At least one file should be generated")
    } finally {
      // 生成ファイルのクリーンアップ
      deleteRecursively(outDir)
      schemaFile.deleteIfExists()
    }
  }

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  /** --package 未指定で戻り値 1 になり、エラーメッセージに --package が含まれる */
  @Test
  fun genCommandWithoutPackageReturnsOneWithError() {
    val schemaFile = Files.createTempFile("osc-gen-schema-", ".yaml")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(schemaFile, testSchemaYaml())
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode: Int = runBlocking {
        adapter.execute(
            listOf(
                "gen",
                "--schema",
                schemaFile.toAbsolutePath().toString(),
            ),
        )
      }

      assertEquals(1, exitCode)
      assertTrue(errBuffer.toString().contains("--package"))
    } finally {
      schemaFile.deleteIfExists()
    }
  }

  /** 非対応の --lang java で戻り値 1 になり、"Unsupported --lang" エラーが出力される */
  @Test
  fun genCommandWithUnsupportedLangReturnsOneWithError() {
    val schemaFile = Files.createTempFile("osc-gen-schema-", ".yaml")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(schemaFile, testSchemaYaml())
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode: Int = runBlocking {
        adapter.execute(
            listOf(
                "gen",
                "--schema",
                schemaFile.toAbsolutePath().toString(),
                "--package",
                "com.example.generated",
                "--lang",
                "java",
            ),
        )
      }

      assertEquals(1, exitCode)
      assertTrue(errBuffer.toString().contains("Unsupported --lang"))
    } finally {
      schemaFile.deleteIfExists()
    }
  }

  /** 不明オプション --bogus で戻り値 1 になり、"Unknown option for gen" エラーが出力される */
  @Test
  fun genCommandWithUnknownOptionReturnsOneWithError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking { adapter.execute(listOf("gen", "--bogus")) }

    assertEquals(1, exitCode)
    assertTrue(errBuffer.toString().contains("Unknown option for gen"))
  }

  /**
   * ディレクトリ配下のファイルとサブディレクトリを再帰的に削除する。
   *
   * @param dir 削除対象のディレクトリパス
   */
  private fun deleteRecursively(dir: java.nio.file.Path) {
    if (Files.exists(dir)) {
      Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }
}
