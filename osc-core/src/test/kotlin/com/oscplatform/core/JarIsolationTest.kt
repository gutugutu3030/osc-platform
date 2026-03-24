package com.oscplatform.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * osc-core の classpath に webserver 関連クラスが含まれないことを確認するテスト。
 *
 * WebUiServer は osc-adapter-webui に属し、osc-core はそのモジュールに依存しないため、 osc-core のテスト classpath からは
 * WebUiServer クラスをロードできないはずである。
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
