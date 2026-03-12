package com.oscplatform.core.runtime

import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** [OscRuntime.on] の schema-first 登録を検証するテスト。 */
class OscRuntimeHandlerRegistrationTest {

  @Test
  fun onRegistersByMessageSpec(): Unit = runBlocking {
    val schema = lightSchema()
    val transport = FakeOnTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val received = CompletableDeferred<OscRuntimeEvent.Received>()

    val spec = schema.resolveMessage("light.color") ?: error("Missing message: light.color")
    runtime.on(spec) { event -> received.complete(event) }

    runtime.start()
    try {
      transport.emit(OscMessagePacket(address = "/light/color", arguments = listOf(1, 2, 3)))
      val event = withTimeout(1000) { received.await() }
      assertEquals("/light/color", event.spec.path)
      assertEquals(1, event.namedArgs["r"])
    } finally {
      runtime.stop()
    }
  }

  @Test
  fun onRegistersByResolvedMessageSpec(): Unit = runBlocking {
    val schema = lightSchema()
    val transport = FakeOnTransport()
    val runtime = OscRuntime(schema = schema, transport = transport)
    val received = CompletableDeferred<OscRuntimeEvent.Received>()

    val spec = schema.resolveMessage("light.color") ?: error("Missing message: light.color")
    runtime.on(spec) { event -> received.complete(event) }

    runtime.start()
    try {
      transport.emit(OscMessagePacket(address = "/light/color", arguments = listOf(10, 20, 30)))
      val event = withTimeout(1000) { received.await() }
      assertEquals(20, event.namedArgs["g"])
    } finally {
      runtime.stop()
    }
  }

  @Test
  fun onRejectsUnknownMessageSpecPath() {
    val runtime = OscRuntime(schema = lightSchema(), transport = FakeOnTransport())

    val ex =
        assertFailsWith<IllegalArgumentException> {
          runtime.on(
              OscMessageSpec(
                  path = "/light/unknown",
                  name = "light.unknown",
                  description = null,
                  args = emptyList(),
              ),
          ) { _ ->
          }
        }

    assertEquals(true, ex.message?.contains("Unknown message spec path"))
  }

  @Test
  fun onRejectsMismatchedMessageSpecIdentity() {
    val runtime = OscRuntime(schema = lightSchema(), transport = FakeOnTransport())

    val ex =
        assertFailsWith<IllegalArgumentException> {
          runtime.on(
              OscMessageSpec(
                  path = "/light/color",
                  name = "wrong.name",
                  description = null,
                  args = emptyList(),
              ),
          ) { _ ->
          }
        }

    assertEquals(true, ex.message?.contains("Unknown message spec identity"))
  }

  private fun lightSchema() = oscSchema {
    message("/light/color") {
      scalar("r", INT)
      scalar("g", INT)
      scalar("b", INT)
    }
  }
}

private class FakeOnTransport : OscTransport {
  override val incomingPackets: MutableSharedFlow<OscPacket> =
      MutableSharedFlow(replay = 1, extraBufferCapacity = 16)

  override suspend fun start() {}

  override suspend fun stop() {}

  override suspend fun send(packet: OscPacket, target: OscTarget) {}

  suspend fun emit(packet: OscPacket) {
    incomingPackets.emit(packet)
  }
}
