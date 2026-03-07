package com.oscplatform.adapter.cli

import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [CliAdapter.execute] の `send` コマンドを実際のUDPソケット経由でE2E検証するテスト。
 *
 * ループバックUDPサーバーを立てて `osc send` がパケットを届けることを確認する。
 */
class CliAdapterSendIntegrationTest {

  @Test
  fun sendCommandDeliversIntArgToLoopbackReceiver(): Unit = runBlocking {
    val port = 19800
    val receiver = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    receiver.start()

    val schemaYaml =
        """
        messages:
          - path: /light/color
            description: set RGB
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
        """
            .trimIndent()

    val tmpSchema = Files.createTempFile("osc-test-schema-", ".yaml")
    try {
      Files.writeString(tmpSchema, schemaYaml)
      val schemaPath = tmpSchema.toAbsolutePath().toString()

      val outBuffer = ByteArrayOutputStream()
      val adapter =
          CliAdapter(
              out = PrintStream(outBuffer),
              err = PrintStream(ByteArrayOutputStream()),
          )

      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              receiver.incomingPackets.filterIsInstance<OscMessagePacket>().first()
            }
          }

      val exitCode =
          adapter.execute(
              listOf(
                  "send",
                  "light.color",
                  "--schema",
                  schemaPath,
                  "--host",
                  "127.0.0.1",
                  "--port",
                  port.toString(),
                  "--r",
                  "255",
                  "--g",
                  "128",
                  "--b",
                  "0",
              ),
          )

      assertEquals(0, exitCode)
      assertTrue(outBuffer.toString().contains("sent"))

      val received = deferred.await()
      assertEquals("/light/color", received.address)
      assertEquals(listOf(255, 128, 0), received.arguments)
    } finally {
      Files.deleteIfExists(tmpSchema)
      receiver.stop()
    }
  }

  @Test
  fun sendCommandDeliversBoolArgToLoopbackReceiver(): Unit = runBlocking {
    val port = 19801
    val receiver = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    receiver.start()

    val schemaYaml =
        """
        messages:
          - path: /device/flag
            args:
              - name: enabled
                kind: scalar
                type: bool
        """
            .trimIndent()

    val tmpSchema = Files.createTempFile("osc-test-schema-", ".yaml")
    try {
      Files.writeString(tmpSchema, schemaYaml)
      val schemaPath = tmpSchema.toAbsolutePath().toString()

      val adapter =
          CliAdapter(
              out = PrintStream(ByteArrayOutputStream()),
              err = PrintStream(ByteArrayOutputStream()),
          )

      val deferred2 =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              receiver.incomingPackets.filterIsInstance<OscMessagePacket>().first()
            }
          }

      val exitCode =
          adapter.execute(
              listOf(
                  "send",
                  "device.flag",
                  "--schema",
                  schemaPath,
                  "--host",
                  "127.0.0.1",
                  "--port",
                  port.toString(),
                  "--enabled",
                  "true",
              ),
          )

      assertEquals(0, exitCode)

      val received = deferred2.await()
      assertEquals("/device/flag", received.address)
      assertEquals(true, received.arguments.single())
    } finally {
      Files.deleteIfExists(tmpSchema)
      receiver.stop()
    }
  }
}
