package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.OscSchema
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YAML スキーマの bundles: セクションを [YamlSchemaLoader] が
 * 正しくパースし、[com.oscplatform.core.schema.OscBundleSpec] を構築することを検証するテスト。
 *
 * バンドル内の参照メッセージは OSC パス ("/light/color") またはメッセージ名 ("set_light_color")
 * のどちらでも指定できる。不正な参照や重複名・引数名コリジョンは初期化時に拒否される。
 */
class YamlSchemaLoaderBundleTest {
  private val loader = YamlSchemaLoader()

  // -------------------------------------------------------------------------
  // 正常系
  // -------------------------------------------------------------------------

  @Test
  fun loadsBundleWithMultipleMessageRefs() {
    val yaml =
        """
            messages:
              - path: /light/color
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
              - path: /device/flag
                args:
                  - name: enabled
                    kind: scalar
                    type: bool
            bundles:
              - name: set_scene
                description: ライトとフラグをアトミックに設定
                messages:
                  - ref: /light/color
                  - ref: /device/flag
        """
            .trimIndent()

    val schema = loadFromString(yaml)

    assertEquals(1, schema.bundles.size)
    val bundle = schema.bundles.single()
    assertEquals("set_scene", bundle.name)
    assertEquals("ライトとフラグをアトミックに設定", bundle.description)
    assertEquals(listOf("/light/color", "/device/flag"), bundle.messageRefs)
  }

  @Test
  fun loadsMultipleBundles() {
    val yaml =
        """
            messages:
              - path: /a
                args:
                  - name: x
                    kind: scalar
                    type: int
              - path: /b
                args:
                  - name: y
                    kind: scalar
                    type: float
              - path: /c
                args:
                  - name: z
                    kind: scalar
                    type: string
            bundles:
              - name: ab
                messages:
                  - ref: /a
                  - ref: /b
              - name: bc
                messages:
                  - ref: /b
                  - ref: /c
        """
            .trimIndent()

    val schema = loadFromString(yaml)
    assertEquals(2, schema.bundles.size)
    assertEquals("ab", schema.bundles[0].name)
    assertEquals("bc", schema.bundles[1].name)
  }

  @Test
  fun loadsBundleWithNullDescription() {
    val yaml =
        """
            messages:
              - path: /msg
                args:
                  - name: v
                    kind: scalar
                    type: int
            bundles:
              - name: simple_bundle
                messages:
                  - ref: /msg
        """
            .trimIndent()

    val schema = loadFromString(yaml)
    assertNull(schema.bundles.single().description)
  }

  @Test
  fun loadsBundleByMessageName() {
    val yaml =
        """
            messages:
              - path: /light/color
                name: set_light_color
                args:
                  - name: r
                    kind: scalar
                    type: int
            bundles:
              - name: my_bundle
                messages:
                  - ref: set_light_color
        """
            .trimIndent()

    val schema = loadFromString(yaml)
    assertEquals(listOf("set_light_color"), schema.bundles.single().messageRefs)
    // OscSchema バリデーションで名前参照が解決されることを確認
    assertEquals(schema.bundles.single(), schema.findBundle("my_bundle"))
  }

  @Test
  fun schemaWithNoBundlesSectionHasEmptyBundles() {
    val yaml =
        """
            messages:
              - path: /msg
                args:
                  - name: v
                    kind: scalar
                    type: int
        """
            .trimIndent()

    val schema = loadFromString(yaml)
    assertTrue(schema.bundles.isEmpty())
  }

  @Test
  fun findBundleReturnsNullForUnknownName() {
    val yaml =
        """
            messages:
              - path: /msg
                args:
                  - name: v
                    kind: scalar
                    type: int
        """
            .trimIndent()

    val schema = loadFromString(yaml)
    assertNull(schema.findBundle("not_there"))
  }

  // -------------------------------------------------------------------------
  // 異常系
  // -------------------------------------------------------------------------

  @Test
  fun rejectsUnknownMessageRefInBundle() {
    val yaml =
        """
            messages:
              - path: /light/color
                args:
                  - name: r
                    kind: scalar
                    type: int
            bundles:
              - name: bad_bundle
                messages:
                  - ref: /unknown/path
        """
            .trimIndent()

    assertFailsWith<IllegalArgumentException> { loadFromString(yaml) }
  }

  @Test
  fun rejectsDuplicateBundleNames() {
    val yaml =
        """
            messages:
              - path: /a
                args:
                  - name: x
                    kind: scalar
                    type: int
            bundles:
              - name: dup
                messages:
                  - ref: /a
              - name: dup
                messages:
                  - ref: /a
        """
            .trimIndent()

    assertFailsWith<IllegalArgumentException> { loadFromString(yaml) }
  }

  @Test
  fun rejectsBundleWithArgNameCollision() {
    val yaml =
        """
            messages:
              - path: /a
                args:
                  - name: value
                    kind: scalar
                    type: int
              - path: /b
                args:
                  - name: value
                    kind: scalar
                    type: float
            bundles:
              - name: collision_bundle
                messages:
                  - ref: /a
                  - ref: /b
        """
            .trimIndent()

    val ex = assertFailsWith<IllegalArgumentException> { loadFromString(yaml) }
    assertTrue(ex.message?.contains("collision") == true || ex.message?.contains("value") == true)
  }

  @Test
  fun rejectsBundleWithNoMessages() {
    val yaml =
        """
            messages:
              - path: /a
                args:
                  - name: x
                    kind: scalar
                    type: int
            bundles:
              - name: empty_bundle
                messages: []
        """
            .trimIndent()

    assertFailsWith<IllegalArgumentException> { loadFromString(yaml) }
  }

  // -------------------------------------------------------------------------
  // ヘルパー
  // -------------------------------------------------------------------------

  private fun loadFromString(yaml: String): OscSchema {
    val tmp = Files.createTempFile("bundle-test", ".yaml")
    try {
      tmp.toFile().writeText(yaml)
      return loader.load(tmp)
    } finally {
      tmp.toFile().delete()
    }
  }
}
