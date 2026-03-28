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
 * [CliAdapter] の schema 解決フローを検証するテスト。
 *
 * CLI コマンドが内部で呼び出す `resolveSchemaPath` の動作を、 明示指定・デフォルト解決・未検出エラーの3パターンで確認する。
 */
class CliAdapterSchemaResolutionTest {

  /**
   * `--schema` で明示指定したパスが正しく解決され、list コマンドが成功することを検証する。
   *
   * 正常系: 一時ファイルに書き出した YAML をパス指定し、exit code 0 で内容が表示される。
   */
  @Test
  fun explicitSchemaPathResolves() {
    val schemaYaml =
        """
        messages:
          - path: /test/explicit
            args:
              - name: v
                kind: scalar
                type: int
        """
            .trimIndent()

    val tmpSchema = Files.createTempFile("osc-schema-explicit-", ".yaml")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      Files.writeString(tmpSchema, schemaYaml)
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode: Int = runBlocking {
        adapter.execute(
            listOf("list", "--schema", tmpSchema.toAbsolutePath().toString()),
        )
      }

      assertEquals(0, exitCode, "明示 schema 指定の list が成功すること")
      assertTrue(
          outBuffer.toString().contains("/test/explicit"),
          "出力にメッセージパスが含まれること",
      )
      assertEquals("", errBuffer.toString(), "stderr が空であること")
    } finally {
      tmpSchema.deleteIfExists()
    }
  }

  /**
   * デフォルトのスキーマ解決でカレントディレクトリの schema.yaml が使用されることを検証する。
   *
   * 正常系: リポジトリルートの schema.yaml が存在する前提で、--schema なしの validate が成功する。
   */
  @Test
  fun defaultSchemaResolutionUsesWorkingDirectorySchema() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    // リポジトリルートに schema.yaml が存在するため、デフォルト解決が成功するはずである
    val schemaFile = java.nio.file.Path.of("schema.yaml")
    if (!Files.exists(schemaFile)) {
      // schema.yaml が存在しない環境ではスキップする
      return
    }

    val exitCode: Int = runBlocking { adapter.execute(listOf("validate")) }

    assertEquals(0, exitCode, "デフォルト schema 解決の validate が成功すること")
    assertTrue(
        outBuffer.toString().contains("schema valid"),
        "出力に 'schema valid' が含まれること",
    )
  }

  /**
   * 存在しないスキーマパスを指定した場合にエラーで終了することを検証する。
   *
   * 異常系: 存在しないパスを --schema に指定し、exit code 1 + stderr にエラーメッセージが出る。
   */
  @Test
  fun nonExistentSchemaPathReturnsError() {
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    val adapter =
        CliAdapter(
            out = PrintStream(outBuffer),
            err = PrintStream(errBuffer),
        )

    val exitCode: Int = runBlocking {
      adapter.execute(
          listOf("list", "--schema", "/tmp/nonexistent-schema-12345.yaml"),
      )
    }

    assertEquals(1, exitCode, "存在しない schema パスで exit code 1 となること")
    assertTrue(
        errBuffer.toString().contains("Schema not found") || errBuffer.toString().contains("error"),
        "stderr にエラーメッセージが含まれること: ${errBuffer}",
    )
  }

  /**
   * 存在しないディレクトリを指定した場合にもエラーで終了することを検証する。
   *
   * 異常系: ディレクトリパスを --schema に指定し、exit code 1 となる。
   */
  @Test
  fun directoryAsSchemaPathReturnsError() {
    val tmpDir = Files.createTempDirectory("osc-schema-dir-")
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(errBuffer),
          )

      val exitCode: Int = runBlocking {
        adapter.execute(
            listOf("list", "--schema", tmpDir.toAbsolutePath().toString()),
        )
      }

      assertEquals(1, exitCode, "ディレクトリ指定で exit code 1 となること")
      assertTrue(
          errBuffer.toString().contains("error") || errBuffer.toString().contains("Schema"),
          "stderr にエラーメッセージが含まれること: ${errBuffer}",
      )
    } finally {
      Files.deleteIfExists(tmpDir)
    }
  }
}
