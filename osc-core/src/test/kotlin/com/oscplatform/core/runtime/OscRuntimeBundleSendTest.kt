package com.oscplatform.core.runtime

import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.STRING
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscBundlePacket
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OscRuntime.sendBundle] および [OscTimeTag] ユーティリティを検証するテスト。
 *
 * sendBundle は複数の OSC メッセージを一つの [OscBundlePacket] として
 * アトミックに送信する。timeTag はデフォルトで IMMEDIATE(=1) 。
 */
class OscRuntimeBundleSendTest {

    // -------------------------------------------------------------------------
    // sendBundle: 正常系
    // -------------------------------------------------------------------------

    @Test
    fun sendBundleEmitsBundlePacketWithAllMessages(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)

        runtime.sendBundle(
            messages = listOf(
                "/light/color" to mapOf("r" to 255, "g" to 128, "b" to 0),
                "/device/flag" to mapOf("enabled" to true),
            ),
            target = OscTarget("127.0.0.1", 9000),
        )

        val bundle = assertIs<OscBundlePacket>(transport.sentPackets.single())
        assertEquals(OscTimeTag.IMMEDIATE, bundle.timeTag)
        assertEquals(2, bundle.elements.size)

        val colorMsg = assertIs<OscMessagePacket>(bundle.elements[0])
        assertEquals("/light/color", colorMsg.address)
        assertEquals(listOf(255, 128, 0), colorMsg.arguments)

        val flagMsg = assertIs<OscMessagePacket>(bundle.elements[1])
        assertEquals("/device/flag", flagMsg.address)
        assertEquals(listOf(true), flagMsg.arguments)
    }

    @Test
    fun sendBundleUsesProvidedTimeTag(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)
        val customTimeTag = 0x0000000180000000L  // 特定の NTP 値

        runtime.sendBundle(
            messages = listOf("/light/color" to mapOf("r" to 0, "g" to 0, "b" to 0)),
            target = OscTarget("127.0.0.1", 9000),
            timeTag = customTimeTag,
        )

        val bundle = assertIs<OscBundlePacket>(transport.sentPackets.single())
        assertEquals(customTimeTag, bundle.timeTag)
    }

    @Test
    fun sendBundleFlattensStructuredArgs(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)

        runtime.sendBundle(
            messages = listOf(
                "/sensor/values" to mapOf(
                    "values" to listOf(10, 20, 30),
                ),
            ),
            target = OscTarget("127.0.0.1", 9000),
        )

        val bundle = assertIs<OscBundlePacket>(transport.sentPackets.single())
        val msg = assertIs<OscMessagePacket>(bundle.elements.single())
        assertEquals(listOf(10, 20, 30), msg.arguments)
    }

    @Test
    fun sendBundleResolvesMessageByName(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)

        runtime.sendBundle(
            messages = listOf("set_light_color" to mapOf("r" to 1, "g" to 2, "b" to 3)),
            target = OscTarget("127.0.0.1", 9000),
        )

        val bundle = assertIs<OscBundlePacket>(transport.sentPackets.single())
        assertEquals("/light/color", assertIs<OscMessagePacket>(bundle.elements.single()).address)
    }

    // -------------------------------------------------------------------------
    // sendBundle: 異常系
    // -------------------------------------------------------------------------

    @Test
    fun sendBundleRejectsEmptyList(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)

        val ex = assertFailsWith<IllegalArgumentException> {
            runtime.sendBundle(
                messages = emptyList(),
                target = OscTarget("127.0.0.1", 9000),
            )
        }
        assertTrue(ex.message?.contains("at least one message") == true)
    }

    @Test
    fun sendBundleRejectsUnknownMessageRef(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)

        assertFailsWith<IllegalArgumentException> {
            runtime.sendBundle(
                messages = listOf("/unknown/path" to emptyMap()),
                target = OscTarget("127.0.0.1", 9000),
            )
        }
    }

    @Test
    fun sendBundleRejectsUnknownArgInOneMessage(): Unit = runBlocking {
        val transport = FakeBundleTransport()
        val runtime = OscRuntime(schema = sceneSchema(), transport = transport)

        assertFailsWith<IllegalArgumentException> {
            runtime.sendBundle(
                messages = listOf(
                    "/light/color" to mapOf("r" to 255, "g" to 128, "b" to 0, "alpha" to 255),
                ),
                target = OscTarget("127.0.0.1", 9000),
            )
        }
    }

    // -------------------------------------------------------------------------
    // OscTimeTag ユーティリティ
    // -------------------------------------------------------------------------

    @Test
    fun oscTimeTagImmediateIsOne() {
        assertEquals(1L, OscTimeTag.IMMEDIATE)
    }

    @Test
    fun oscTimeTagFromEpochMillisProducesHigherBitForSeconds() {
        // 0ms (Unix epoch) → NTP秒フィールドは 2_208_988_800
        val tag = OscTimeTag.fromEpochMillis(0L)
        val seconds = tag ushr 32
        assertEquals(2_208_988_800L, seconds)
    }

    @Test
    fun oscTimeTagFromEpochMillisIncorporatesFractions() {
        // 500ms → fraction ≈ 0x8000_0000
        val tag = OscTimeTag.fromEpochMillis(500L)
        val fraction = tag and 0xFFFF_FFFFL
        // 500ms / 1000ms * 2^32 ≈ 2_147_483_648 (0x80000000)
        assertTrue(fraction in 0x7FFF_0000L..0x8001_0000L, "fraction=$fraction")
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    private fun sceneSchema() = oscSchema {
        message("/light/color") {
            name("set_light_color")
            scalar("r", INT)
            scalar("g", INT)
            scalar("b", INT)
        }
        message("/device/flag") {
            scalar("enabled", BOOL)
        }
        message("/sensor/values") {
            array("values", length = 3) {
                scalar(INT)
            }
        }
    }
}

private class FakeBundleTransport : OscTransport {
    val sentPackets = mutableListOf<OscPacket>()
    private val _flow = MutableSharedFlow<OscPacket>(extraBufferCapacity = 64)
    override val incomingPackets: Flow<OscPacket> = _flow

    override suspend fun start() {}
    override suspend fun stop() {}

    override suspend fun send(packet: OscPacket, target: OscTarget) {
        sentPackets += packet
    }
}
