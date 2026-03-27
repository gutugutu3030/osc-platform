package com.oscplatform.core.schema.loader

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SchemaPathResolver] のパス解決ロジックを検証するテスト。
 *
 * 明示パス指定・自動探索・優先順位・異常系（ファイル不在等）を網羅する。
 */
class SchemaPathResolverTest {

  /** `.kts` と `.yaml` の両方が存在する場合に `.kts` が優先され、警告が発行されることを検証する。 */
  @Test
  fun resolvePrefersKtsAndWarnsWhenKtsAndYamlBothExist() {
    val cwd = Files.createTempDirectory("schema-resolver-")
    val kts = cwd.resolve("schema.kts")
    val yaml = cwd.resolve("schema.yaml")
    val warnings = mutableListOf<String>()

    try {
      kts.writeText("// schema script")
      yaml.writeText("messages: []")

      val resolved =
          SchemaPathResolver.resolve(
              explicitPath = null, warn = { msg -> warnings += msg }, cwd = cwd)

      assertEquals(kts.toAbsolutePath().normalize(), resolved)
      assertEquals(1, warnings.size)
      assertTrue(warnings.single().contains("schema.kts"))
      assertTrue(warnings.single().contains("schema.yaml"))
    } finally {
      kts.deleteIfExists()
      yaml.deleteIfExists()
      cwd.deleteIfExists()
    }
  }

  /** 明示パスが指定された場合、探索を行わずそのパスがそのまま返ることを検証する。 */
  @Test
  fun resolveReturnsExplicitPathWhenProvided() {
    val tmp = Files.createTempFile("explicit-schema-", ".yaml")
    try {
      tmp.writeText("messages: []")

      val resolved = SchemaPathResolver.resolve(explicitPath = tmp.toAbsolutePath().toString())
      assertEquals(tmp.toAbsolutePath().normalize(), resolved)
    } finally {
      tmp.deleteIfExists()
    }
  }

  /** YAML ファイルのみが存在するディレクトリで自動探索が YAML を返すことを検証する。 */
  @Test
  fun resolveReturnsYamlWhenOnlyYamlExists() {
    val cwd = Files.createTempDirectory("schema-yaml-only-")
    val yaml = cwd.resolve("schema.yaml")

    try {
      yaml.writeText("messages: []")

      val resolved = SchemaPathResolver.resolve(explicitPath = null, cwd = cwd)
      assertEquals(yaml.toAbsolutePath().normalize(), resolved)
    } finally {
      yaml.deleteIfExists()
      cwd.deleteIfExists()
    }
  }

  /** スキーマファイルが一つも存在しないディレクトリで [IllegalStateException] がスローされることを検証する。 */
  @Test
  fun resolveThrowsWhenNoCandidatesExist() {
    val cwd = Files.createTempDirectory("schema-empty-")

    try {
      val ex =
          assertFailsWith<IllegalStateException> {
            SchemaPathResolver.resolve(explicitPath = null, cwd = cwd)
          }
      assertTrue(
          ex.message?.contains("Schema not found") == true,
          "エラーメッセージに Schema not found を含むべき: ${ex.message}",
      )
    } finally {
      cwd.deleteIfExists()
    }
  }

  /** 存在しないファイルを明示パスに指定した場合に [IllegalArgumentException] がスローされることを検証する。 */
  @Test
  fun resolveThrowsWhenExplicitPathDoesNotExist() {
    val ex =
        assertFailsWith<IllegalArgumentException> {
          SchemaPathResolver.resolve(explicitPath = "/nonexistent/schema.yaml")
        }
    assertTrue(
        ex.message?.contains("Schema not found") == true,
        "エラーメッセージに Schema not found を含むべき: ${ex.message}",
    )
  }
}
