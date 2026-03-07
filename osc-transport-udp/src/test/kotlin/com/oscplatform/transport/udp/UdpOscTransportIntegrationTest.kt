package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [UdpOscTransport] の実ソケットを使った送受信統合テスト。
 *
 * ループバックアドレスに bind したトランスポートに対してパケットを送信し、 [OscTransport.incomingPackets] にそのパケットが届くことを検証する。
 */
class UdpOscTransportIntegrationTest {

  @Test
  fun sendAndReceiveIntMessageViaLoopback(): Unit = runBlocking {
    val port = 19700
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    transport.start()
    try {
      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              transport.incomingPackets.filterIsInstance<OscMessagePacket>().first()
            }
          }

      val sent = OscMessagePacket(address = "/test/int", arguments = listOf(42))
      transport.send(sent, OscTarget(host = "127.0.0.1", port = port))

      val received = deferred.await()
      assertEquals("/test/int", received.address)
      assertEquals(listOf(42), received.arguments)
    } finally {
      transport.stop()
    }
  }

  @Test
  fun sendAndReceiveMultipleArgsViaLoopback(): Unit = runBlocking {
    val port = 19701
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    transport.start()
    try {
      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              transport.incomingPackets.filterIsInstance<OscMessagePacket>().first()
            }
          }

      val sent =
          OscMessagePacket(
              address = "/test/multi",
              arguments = listOf(1, 2.5f, "hello"),
          )
      transport.send(sent, OscTarget(host = "127.0.0.1", port = port))

      val received = deferred.await()
      assertEquals("/test/multi", received.address)
      assertEquals(3, received.arguments.size)
      assertEquals(1, received.arguments[0])
      assertEquals(2.5f, received.arguments[1])
      assertEquals("hello", received.arguments[2])
    } finally {
      transport.stop()
    }
  }

  @Test
  fun sendAndReceiveBoolAndBlobViaLoopback(): Unit = runBlocking {
    val port = 19702
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    transport.start()
    try {
      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) {
              transport.incomingPackets.filterIsInstance<OscMessagePacket>().first()
            }
          }

      val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
      val sent =
          OscMessagePacket(
              address = "/test/blob",
              arguments = listOf(true, payload),
          )
      transport.send(sent, OscTarget(host = "127.0.0.1", port = port))

      val received = deferred.await()
      assertEquals("/test/blob", received.address)
      assertEquals(true, received.arguments[0])
      val receivedBlob = received.arguments[1] as ByteArray
      assertEquals(payload.toList(), receivedBlob.toList())
    } finally {
      transport.stop()
    }
  }

  @Test
  fun consecutiveErrorsAreReportedViaErrorsFlow(): Unit = runBlocking {
    val port = 19703
    val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = port)
    transport.start()
    try {
      val deferred =
          async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2000) { transport.errors.first() }
          }

      // 壊れたバイト列（不完全なOSCパケット）を直接UDP送信してparse errorを誘発
      val javaSocket = java.net.DatagramSocket()
      val garbage = byteArrayOf(0x00, 0x01, 0x02)
      val datagram =
          java.net.DatagramPacket(
              garbage, garbage.size, java.net.InetAddress.getByName("127.0.0.1"), port)
      javaSocket.send(datagram)
      javaSocket.close()

      val error = deferred.await()
      assertEquals(1, error.consecutiveCount)
    } finally {
      transport.stop()
    }
  }
}
