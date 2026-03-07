package com.oscplatform.core.schema.loader

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaPathResolverTest {

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
}
