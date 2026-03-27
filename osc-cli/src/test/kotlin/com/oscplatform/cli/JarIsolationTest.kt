package com.oscplatform.cli

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * osc-cli 配布 classpath のスモーク確認。
 *
 * このテストはビルドの依存構成が意図通りであることを確認するためのスモークテストである。 osc-cli は osc-adapter-webui に依存しているため、テスト classpath
 * から WebUiServer 等をロードできるはずである。 配布物や依存境界の厳密な保証は別レイヤー（CI/CD、Gradle dependency verification
 * 等）で担保する前提とする。
 */
class JarIsolationTest {

  /** osc-cli の classpath に WebUiServer クラスが含まれることを確認する。 */
  @Test
  fun distributionClasspathContainsWebUiServer() {
    val cls = Class.forName("com.oscplatform.adapter.webui.WebUiServer")
    assertNotNull(cls)
  }

  /** osc-cli の classpath に WebUiAdapter クラスが含まれることを確認する。 */
  @Test
  fun distributionClasspathContainsWebUiAdapter() {
    val cls = Class.forName("com.oscplatform.adapter.webui.WebUiAdapter")
    assertNotNull(cls)
  }
}
