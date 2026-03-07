package com.oscplatform.core.runtime

import com.oscplatform.core.schema.dsl.BLOB
import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [OscRuntime] の BOOL / BLOB 型対応を検証するテスト。
 * - BOOL: Boolean / Int(0≠1) / String("true"/"false"/"yes") からの変換と送受信
 * - BLOB: ByteArray / Base64 文字列からの変換と送受信
 * - OscType.fromToken() の YAML トークン認識も画䏯する
 */
class OscRuntimeBoolBlobTest {

  // -------------------------------------------------------------------------
  // BOOL 型: send
  // -------------------------------------------------------------------------

  @Test
  fun sendWithBoolArgFlattensToBooleanValue(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)

    runtime.send(
        messageRef = "/device/flag",
        rawArgs = mapOf("enabled" to true),
        target = OscTarget("127.0.0.1", 9000),
    )

    val packet = transport.sentMessages.single()
    assertEquals("/device/flag", packet.address)
    assertEquals(listOf(true), packet.arguments)
  }

  @Test
  fun sendConvertsBoolFromInt(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)

    runtime.send(
        messageRef = "/device/flag",
        rawArgs = mapOf("enabled" to 1),
        target = OscTarget("127.0.0.1", 9000),
    )

    assertEquals(true, transport.sentMessages.single().arguments.single())
  }

  @Test
  fun sendConvertsBoolFromZeroInt(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)

    runtime.send(
        messageRef = "/device/flag",
        rawArgs = mapOf("enabled" to 0),
        target = OscTarget("127.0.0.1", 9000),
    )

    assertEquals(false, transport.sentMessages.single().arguments.single())
  }

  @Test
  fun sendConvertsBoolFromStringTrue(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)

    runtime.send(
        messageRef = "/device/flag",
        rawArgs = mapOf("enabled" to "true"),
        target = OscTarget("127.0.0.1", 9000),
    )

    assertEquals(true, transport.sentMessages.single().arguments.single())
  }

  @Test
  fun sendConvertsBoolFromStringFalse(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)

    runtime.send(
        messageRef = "/device/flag",
        rawArgs = mapOf("enabled" to "false"),
        target = OscTarget("127.0.0.1", 9000),
    )

    assertEquals(false, transport.sentMessages.single().arguments.single())
  }

  @Test
  fun sendRejectsInvalidBoolValue(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)

    assertFailsWith<IllegalArgumentException> {
      runtime.send(
          messageRef = "/device/flag",
          rawArgs = mapOf("enabled" to listOf(1, 2)),
          target = OscTarget("127.0.0.1", 9000),
      )
    }
  }

  // -------------------------------------------------------------------------
  // BOOL 型: receive (unflatten)
  // -------------------------------------------------------------------------

  @Test
  fun receiveUnflattensBoolArg(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = flagSchema(), transport = transport)
    runtime.start()

    try {
      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1000) {
              runtime.events.filterIsInstance<OscRuntimeEvent.Received>().first()
            }
          }

      transport.emit(OscMessagePacket(address = "/device/flag", arguments = listOf(true)))

      val event = deferred.await()
      assertEquals(true, event.namedArgs["enabled"])
    } finally {
      runtime.stop()
    }
  }

  // -------------------------------------------------------------------------
  // BLOB 型: send
  // -------------------------------------------------------------------------

  @Test
  fun sendWithBlobArgFlattensToByteArray(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = dataSchema(), transport = transport)
    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

    runtime.send(
        messageRef = "/device/data",
        rawArgs = mapOf("payload" to payload),
        target = OscTarget("127.0.0.1", 9000),
    )

    val packet = transport.sentMessages.single()
    assertContentEquals(payload, packet.arguments.single() as ByteArray)
  }

  @Test
  fun sendConvertsBlobFromBase64String(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = dataSchema(), transport = transport)
    val original = byteArrayOf(1, 2, 3, 4)
    val base64 = Base64.getEncoder().encodeToString(original)

    runtime.send(
        messageRef = "/device/data",
        rawArgs = mapOf("payload" to base64),
        target = OscTarget("127.0.0.1", 9000),
    )

    assertContentEquals(original, transport.sentMessages.single().arguments.single() as ByteArray)
  }

  @Test
  fun sendRejectsInvalidBlobValue(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = dataSchema(), transport = transport)

    assertFailsWith<IllegalArgumentException> {
      runtime.send(
          messageRef = "/device/data",
          rawArgs = mapOf("payload" to 12345),
          target = OscTarget("127.0.0.1", 9000),
      )
    }
  }

  // -------------------------------------------------------------------------
  // BLOB 型: receive (unflatten)
  // -------------------------------------------------------------------------

  @Test
  fun receiveUnflattensBlobArg(): Unit = runBlocking {
    val transport = FakeBoolBlobTransport()
    val runtime = OscRuntime(schema = dataSchema(), transport = transport)
    runtime.start()

    try {
      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1000) {
              runtime.events.filterIsInstance<OscRuntimeEvent.Received>().first()
            }
          }

      val expected = byteArrayOf(0x10, 0x20, 0x30)
      transport.emit(OscMessagePacket(address = "/device/data", arguments = listOf(expected)))

      val event = deferred.await()
      assertContentEquals(expected, event.namedArgs["payload"] as ByteArray)
    } finally {
      runtime.stop()
    }
  }

  // -------------------------------------------------------------------------
  // YAML スキーマトークン from/to
  // -------------------------------------------------------------------------

  @Test
  fun oscTypeFromTokenRecognizesBool() {
    assertEquals(
        com.oscplatform.core.schema.OscType.BOOL,
        com.oscplatform.core.schema.OscType.fromToken("bool"),
    )
    assertEquals(
        com.oscplatform.core.schema.OscType.BOOL,
        com.oscplatform.core.schema.OscType.fromToken("boolean"),
    )
  }

  @Test
  fun oscTypeFromTokenRecognizesBlob() {
    assertEquals(
        com.oscplatform.core.schema.OscType.BLOB,
        com.oscplatform.core.schema.OscType.fromToken("blob"),
    )
    assertEquals(
        com.oscplatform.core.schema.OscType.BLOB,
        com.oscplatform.core.schema.OscType.fromToken("bytes"),
    )
  }

  // -------------------------------------------------------------------------
  // ヘルパー
  // -------------------------------------------------------------------------

  private fun flagSchema() = oscSchema { message("/device/flag") { scalar("enabled", BOOL) } }

  private fun dataSchema() = oscSchema { message("/device/data") { scalar("payload", BLOB) } }
}

private class FakeBoolBlobTransport : OscTransport {
  val sentMessages = mutableListOf<OscMessagePacket>()
  private val _flow = MutableSharedFlow<OscPacket>(extraBufferCapacity = 64)
  override val incomingPackets: Flow<OscPacket> = _flow

  override suspend fun start() {}

  override suspend fun stop() {}

  override suspend fun send(packet: OscPacket, target: OscTarget) {
    if (packet is OscMessagePacket) sentMessages += packet
  }

  suspend fun emit(packet: OscPacket) = _flow.emit(packet)
}
