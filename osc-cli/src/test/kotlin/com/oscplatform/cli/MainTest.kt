package com.oscplatform.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class MainTest {

  @Test
  fun buildTopLevelUsageIncludesCliAndMcpCommands() {
    val usage = buildTopLevelUsage()

    assertTrue(usage.contains("osc run"))
    assertTrue(usage.contains("--webui"))
    assertTrue(usage.contains("osc list"))
    assertTrue(usage.contains("osc validate"))
    assertTrue(usage.contains("osc gen"))
    assertTrue(usage.contains("osc mcp"))
    assertTrue(usage.contains("osc webui"))
    assertTrue(usage.contains("osc editor"))
    assertTrue(usage.contains("osc version"))
    assertTrue(usage.contains("osc --version"))
    assertTrue(usage.contains("osc help"))
  }
}
