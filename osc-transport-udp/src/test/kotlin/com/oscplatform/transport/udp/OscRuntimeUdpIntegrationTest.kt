package com.oscplatform.transport.udp

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.runtime.OscTimeTag
import com.oscplatform.core.schema.dsl.BLOB
import com.oscplatform.core.schema.dsl.BOOL
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.STRING
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [OscRuntime] + [UdpOscTransport] の実UDP送受信を通じた結合テスト。
 *
 * 送信側Runtimeと受信側Runtimeをそれぞれ別ポートで起動し、 スキーマ定義に基づいたメッセージの送信→受信→ハンドラ呼び出しまでを 実ネットワーク(ループバック)経由でE2E検証する。
 */
class OscRuntimeUdpIntegrationTest {

  // ------------------------------------------------------------------
  // 単一メッセージの送受信
  // ------------------------------------------------------------------

  @Test
  fun sendAndReceiveIntMessageViaRuntime(): Unit = runBlocking {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
    }

    val receiverPort = 19810
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val received = CompletableDeferred<OscRuntimeEvent.Received>()
    val spec = schema.resolveMessage("light.color") ?: error("Missing message")
    receiverRuntime.on(spec) { event -> received.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      senderRuntime.send(
          messageRef = "light.color",
          rawArgs = mapOf("r" to 255, "g" to 128, "b" to 0),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = withTimeout(2000) { received.await() }
      assertEquals("/light/color", event.spec.path)
      assertEquals(255, event.namedArgs["r"])
      assertEquals(128, event.namedArgs["g"])
      assertEquals(0, event.namedArgs["b"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  @Test
  fun sendAndReceiveFloatMessageViaRuntime(): Unit = runBlocking {
    val schema = oscSchema {
      message("/sensor/value") {
        scalar("x", FLOAT)
        scalar("y", FLOAT)
      }
    }

    val receiverPort = 19811
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val received = CompletableDeferred<OscRuntimeEvent.Received>()
    val spec = schema.resolveMessage("sensor.value") ?: error("Missing message")
    receiverRuntime.on(spec) { event -> received.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      senderRuntime.send(
          messageRef = "sensor.value",
          rawArgs = mapOf("x" to 1.5f, "y" to -3.14f),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = withTimeout(2000) { received.await() }
      assertEquals("/sensor/value", event.spec.path)
      assertEquals(1.5f, event.namedArgs["x"])
      assertEquals(-3.14f, event.namedArgs["y"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  @Test
  fun sendAndReceiveStringMessageViaRuntime(): Unit = runBlocking {
    val schema = oscSchema {
      message("/display/text") {
        scalar("label", STRING)
        scalar("value", STRING)
      }
    }

    val receiverPort = 19812
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val received = CompletableDeferred<OscRuntimeEvent.Received>()
    val spec = schema.resolveMessage("display.text") ?: error("Missing message")
    receiverRuntime.on(spec) { event -> received.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      senderRuntime.send(
          messageRef = "display.text",
          rawArgs = mapOf("label" to "temperature", "value" to "23.5°C"),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = withTimeout(2000) { received.await() }
      assertEquals("temperature", event.namedArgs["label"])
      assertEquals("23.5°C", event.namedArgs["value"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  @Test
  fun sendAndReceiveBoolMessageViaRuntime(): Unit = runBlocking {
    val schema = oscSchema { message("/device/toggle") { scalar("enabled", BOOL) } }

    val receiverPort = 19813
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val received = CompletableDeferred<OscRuntimeEvent.Received>()
    val spec = schema.resolveMessage("device.toggle") ?: error("Missing message")
    receiverRuntime.on(spec) { event -> received.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      senderRuntime.send(
          messageRef = "device.toggle",
          rawArgs = mapOf("enabled" to true),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = withTimeout(2000) { received.await() }
      assertEquals(true, event.namedArgs["enabled"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  @Test
  fun sendAndReceiveBlobMessageViaRuntime(): Unit = runBlocking {
    val schema = oscSchema {
      message("/data/payload") {
        scalar("tag", INT)
        scalar("body", BLOB)
      }
    }

    val receiverPort = 19814
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val received = CompletableDeferred<OscRuntimeEvent.Received>()
    val spec = schema.resolveMessage("data.payload") ?: error("Missing message")
    receiverRuntime.on(spec) { event -> received.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      val blob = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
      senderRuntime.send(
          messageRef = "data.payload",
          rawArgs = mapOf("tag" to 42, "body" to blob),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = withTimeout(2000) { received.await() }
      assertEquals(42, event.namedArgs["tag"])
      assertIs<ByteArray>(event.namedArgs["body"])
      assertContentEquals(blob, event.namedArgs["body"] as ByteArray)
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  // ------------------------------------------------------------------
  // 複合型メッセージ（複数型の混在）
  // ------------------------------------------------------------------

  @Test
  fun sendAndReceiveMixedTypesMessage(): Unit = runBlocking {
    val schema = oscSchema {
      message("/event/log") {
        scalar("code", INT)
        scalar("severity", FLOAT)
        scalar("message", STRING)
        scalar("critical", BOOL)
      }
    }

    val receiverPort = 19815
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val received = CompletableDeferred<OscRuntimeEvent.Received>()
    val spec = schema.resolveMessage("event.log") ?: error("Missing message")
    receiverRuntime.on(spec) { event -> received.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      senderRuntime.send(
          messageRef = "event.log",
          rawArgs =
              mapOf(
                  "code" to 500,
                  "severity" to 9.5f,
                  "message" to "Internal Error",
                  "critical" to true,
              ),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = withTimeout(2000) { received.await() }
      assertEquals(500, event.namedArgs["code"])
      assertEquals(9.5f, event.namedArgs["severity"])
      assertEquals("Internal Error", event.namedArgs["message"])
      assertEquals(true, event.namedArgs["critical"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  // ------------------------------------------------------------------
  // バンドル送受信
  // ------------------------------------------------------------------

  @Test
  fun sendAndReceiveBundleViaRuntime(): Unit = runBlocking {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
      message("/device/toggle") { scalar("enabled", BOOL) }
    }

    val receiverPort = 19816
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    receiverRuntime.start()
    senderRuntime.start()
    try {
      val events =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              receiverRuntime.events.filterIsInstance<OscRuntimeEvent.Received>().take(2).toList()
            }
          }

      senderRuntime.sendBundle(
          messages =
              listOf(
                  "/light/color" to mapOf("r" to 255, "g" to 0, "b" to 128),
                  "/device/toggle" to mapOf("enabled" to false),
              ),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
          timeTag = OscTimeTag.IMMEDIATE,
      )

      val receivedEvents = events.await()
      assertEquals(2, receivedEvents.size)

      val colorEvent = receivedEvents.first { it.spec.path == "/light/color" }
      assertEquals(255, colorEvent.namedArgs["r"])
      assertEquals(0, colorEvent.namedArgs["g"])
      assertEquals(128, colorEvent.namedArgs["b"])

      val toggleEvent = receivedEvents.first { it.spec.path == "/device/toggle" }
      assertEquals(false, toggleEvent.namedArgs["enabled"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  // ------------------------------------------------------------------
  // ハンドラ登録による受信（複数ハンドラ）
  // ------------------------------------------------------------------

  @Test
  fun multipleHandlersReceiveFromSameAddress(): Unit = runBlocking {
    val schema = oscSchema {
      message("/light/color") {
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
      }
    }

    val receiverPort = 19817
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val spec = schema.resolveMessage("light.color") ?: error("Missing message")
    val received1 = CompletableDeferred<OscRuntimeEvent.Received>()
    val received2 = CompletableDeferred<OscRuntimeEvent.Received>()
    receiverRuntime.on(spec) { event -> received1.complete(event) }
    receiverRuntime.on(spec) { event -> received2.complete(event) }

    receiverRuntime.start()
    senderRuntime.start()
    try {
      senderRuntime.send(
          messageRef = "light.color",
          rawArgs = mapOf("r" to 10, "g" to 20, "b" to 30),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event1 = withTimeout(2000) { received1.await() }
      val event2 = withTimeout(2000) { received2.await() }
      assertEquals(10, event1.namedArgs["r"])
      assertEquals(10, event2.namedArgs["r"])
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }

  // ------------------------------------------------------------------
  // 未定義パスはValidationErrorになること
  // ------------------------------------------------------------------

  @Test
  fun unknownPathEmitsValidationError(): Unit = runBlocking {
    val schema = oscSchema { message("/known/path") { scalar("v", INT) } }

    val receiverPort = 19818
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    // 送信側はスキーマ無しのraw transportで未定義パスを直接送る
    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)

    receiverRuntime.start()
    senderTransport.start()
    try {
      val error =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              receiverRuntime.events.filterIsInstance<OscRuntimeEvent.ValidationError>().first()
            }
          }

      senderTransport.send(
          packet =
              com.oscplatform.core.transport.OscMessagePacket(
                  address = "/unknown/path",
                  arguments = listOf(99),
              ),
          target = OscTarget(host = "127.0.0.1", port = receiverPort),
      )

      val event = error.await()
      assertTrue(event.reason.contains("Unknown"))
      assertEquals("/unknown/path", event.address)
    } finally {
      senderTransport.stop()
      receiverRuntime.stop()
    }
  }

  // ------------------------------------------------------------------
  // 連続メッセージの送受信
  // ------------------------------------------------------------------

  @Test
  fun sendAndReceiveMultipleConsecutiveMessages(): Unit = runBlocking {
    val schema = oscSchema { message("/counter/value") { scalar("n", INT) } }

    val receiverPort = 19819
    val receiverTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = receiverPort)
    val receiverRuntime = OscRuntime(schema = schema, transport = receiverTransport)

    val senderTransport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 0)
    val senderRuntime = OscRuntime(schema = schema, transport = senderTransport)

    val messageCount = 5

    receiverRuntime.start()
    senderRuntime.start()
    try {
      val events =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(3000) {
              receiverRuntime.events
                  .filterIsInstance<OscRuntimeEvent.Received>()
                  .take(messageCount)
                  .toList()
            }
          }

      repeat(messageCount) { i ->
        senderRuntime.send(
            messageRef = "counter.value",
            rawArgs = mapOf("n" to i),
            target = OscTarget(host = "127.0.0.1", port = receiverPort),
        )
      }

      val receivedEvents = events.await()
      assertEquals(messageCount, receivedEvents.size)
      val values = receivedEvents.map { it.namedArgs["n"] as Int }.sorted()
      assertEquals(listOf(0, 1, 2, 3, 4), values)
    } finally {
      senderRuntime.stop()
      receiverRuntime.stop()
    }
  }
}
