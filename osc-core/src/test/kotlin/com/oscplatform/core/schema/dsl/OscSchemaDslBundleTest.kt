package com.oscplatform.core.schema.dsl

import com.oscplatform.core.schema.OscSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [oscSchema] DSL の bundle{} ブロックによる [com.oscplatform.core.schema.OscBundleSpec]
 * の構築とバリデーションを検証するテスト。
 *
 * バンドルは複数のメッセージをアトミックに送信するためのグルーピング定義である。
 * 同一バンドル内で引数名が重複すると初期化時にエラーとなる。
 */
class OscSchemaDslBundleTest {

    // -------------------------------------------------------------------------
    // 正常系
    // -------------------------------------------------------------------------

    @Test
    fun bundleBlockCreatesOscBundleSpec() {
        val schema = oscSchema {
            message("/light/color") {
                scalar("r", INT)
                scalar("g", INT)
                scalar("b", INT)
            }
            message("/device/flag") {
                scalar("enabled", BOOL)
            }
            bundle("set_scene") {
                description("ライトとフラグをアトミックに設定")
                message("/light/color")
                message("/device/flag")
            }
        }

        assertEquals(1, schema.bundles.size)
        val bundle = schema.bundles.single()
        assertEquals("set_scene", bundle.name)
        assertEquals("ライトとフラグをアトミックに設定", bundle.description)
        assertEquals(listOf("/light/color", "/device/flag"), bundle.messageRefs)
    }

    @Test
    fun bundleCanReferenceByMessageName() {
        val schema = oscSchema {
            message("/light/color") {
                name("set_light_color")
                scalar("r", INT)
            }
            bundle("my_bundle") {
                message("set_light_color")
            }
        }

        assertEquals(listOf("set_light_color"), schema.bundles.single().messageRefs)
    }

    @Test
    fun multipleBundlesAreRegistered() {
        val schema = oscSchema {
            message("/a") { scalar("x", INT) }
            message("/b") { scalar("y", FLOAT) }
            message("/c") { scalar("z", STRING) }
            bundle("ab") {
                message("/a")
                message("/b")
            }
            bundle("bc") {
                message("/b")
                message("/c")
            }
        }

        assertEquals(2, schema.bundles.size)
        assertEquals("ab", schema.bundles[0].name)
        assertEquals("bc", schema.bundles[1].name)
    }

    @Test
    fun bundleDescriptionIsOptional() {
        val schema = oscSchema {
            message("/msg") { scalar("v", INT) }
            bundle("simple") {
                message("/msg")
            }
        }

        assertNull(schema.bundles.single().description)
    }

    @Test
    fun schemaNoBundlesHasEmptyList() {
        val schema = oscSchema {
            message("/msg") { scalar("v", INT) }
        }
        assertTrue(schema.bundles.isEmpty())
    }

    @Test
    fun findBundleReturnsByName() {
        val schema = oscSchema {
            message("/a") { scalar("x", INT) }
            bundle("my_bundle") { message("/a") }
        }

        val found = schema.findBundle("my_bundle")
        assertEquals("my_bundle", found?.name)
    }

    // -------------------------------------------------------------------------
    // 異常系
    // -------------------------------------------------------------------------

    @Test
    fun rejectsUnknownMessageRefInBundle() {
        assertFailsWith<IllegalArgumentException> {
            oscSchema {
                message("/a") { scalar("x", INT) }
                bundle("bad") {
                    message("/unknown")
                }
            }
        }
    }

    @Test
    fun rejectsDuplicateBundleNames() {
        assertFailsWith<IllegalArgumentException> {
            oscSchema {
                message("/a") { scalar("x", INT) }
                bundle("dup") { message("/a") }
                bundle("dup") { message("/a") }
            }
        }
    }

    @Test
    fun rejectsBundleWithArgNameCollision() {
        val ex = assertFailsWith<IllegalArgumentException> {
            oscSchema {
                message("/a") { scalar("value", INT) }
                message("/b") { scalar("value", FLOAT) }
                bundle("collision") {
                    message("/a")
                    message("/b")
                }
            }
        }
        assertTrue(ex.message?.contains("collision") == true || ex.message?.contains("value") == true)
    }

    @Test
    fun rejectsBundleWithNoMessages() {
        assertFailsWith<IllegalArgumentException> {
            oscSchema {
                message("/a") { scalar("x", INT) }
                bundle("empty") {
                    // no message() calls
                }
            }
        }
    }
}
