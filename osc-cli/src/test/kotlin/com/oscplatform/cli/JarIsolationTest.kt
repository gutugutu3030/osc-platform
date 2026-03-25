package com.oscplatform.cli

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * osc-cli の配布 classpath に webserver 関連クラスが含まれることを確認するテスト。
 *
 * osc-cli は osc-adapter-webui に依存しているため、 osc-cli のテスト classpath から WebUiServer クラスをロードできるはずである。
 */
class JarIsolationTest {

  @Test
  fun distributionClasspathContainsWebUiServer() {
    val cls = Class.forName("com.oscplatform.adapter.webui.WebUiServer")
    assertNotNull(cls)
  }

  @Test
  fun distributionClasspathContainsWebUiAdapter() {
    val cls = Class.forName("com.oscplatform.adapter.webui.WebUiAdapter")
    assertNotNull(cls)
  }
}
