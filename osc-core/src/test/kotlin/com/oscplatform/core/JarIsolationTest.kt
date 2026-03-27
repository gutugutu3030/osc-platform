package com.oscplatform.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * osc-core テスト classpath のスモーク確認。
 *
 * このテストはビルドの依存構成が意図通りであることを確認するためのスモークテストである。 配布物や依存境界の厳密な保証は別レイヤー（CI/CD、Gradle dependency
 * verification 等）で担保する前提とする。
 */
class JarIsolationTest {

  @Test
  fun coreClasspathDoesNotContainWebUiServer() {
    assertFailsWith<ClassNotFoundException> {
      Class.forName("com.oscplatform.adapter.webui.WebUiServer")
    }
  }

  @Test
  fun coreClasspathDoesNotContainWebUiAdapter() {
    assertFailsWith<ClassNotFoundException> {
      Class.forName("com.oscplatform.adapter.webui.WebUiAdapter")
    }
  }

  @Test
  fun oscRuntimeEventIsAccessibleFromCore() {
    val cls = Class.forName("com.oscplatform.core.runtime.OscRuntimeEvent")
    assertNotNull(cls)
  }
}
