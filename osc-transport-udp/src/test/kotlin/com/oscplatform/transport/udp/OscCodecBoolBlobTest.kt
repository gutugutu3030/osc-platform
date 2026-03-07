package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscMessagePacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [OscCodec] における BOOL 型（T/F タグ）と BLOB 型（b タグ）の エンコード・デコード仕様を検証するテスト。
 *
 * OSC 仕様上 BOOL は type-tag のみでデータバイトを持たない。 BLOB は int32 サイズフィールド + 4バイト境界パディング済みデータで構成される。
 */
class OscCodecBoolBlobTest {

  // -------------------------------------------------------------------------
  // bool: T / F タグ
  // -------------------------------------------------------------------------

  /** true → type tag 'T'、デコード後も true に戻ること */
  @Test
  fun boolTrueEncodesAndDecodes() {
    val packet = OscMessagePacket(address = "/flag", arguments = listOf(true))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertEquals("/flag", decoded.address)
    assertEquals(1, decoded.arguments.size)
    assertTrue(decoded.arguments[0] as Boolean)
  }

  /** false → type tag 'F'、デコード後も false に戻ること */
  @Test
  fun boolFalseEncodesAndDecodes() {
    val packet = OscMessagePacket(address = "/flag", arguments = listOf(false))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertFalse(decoded.arguments[0] as Boolean)
  }

  @Test
  fun boolTypeTagContainsTOrF() {
    val truePacket = OscMessagePacket(address = "/a", arguments = listOf(true))
    val falsePacket = OscMessagePacket(address = "/a", arguments = listOf(false))
    val trueBytes = OscCodec.encode(truePacket)
    val falseBytes = OscCodec.encode(falsePacket)
    // type tag 文字列を探して T / F が含まれていることを確認
    assertTrue(trueBytes.toString(Charsets.UTF_8).contains('T'))
    assertTrue(falseBytes.toString(Charsets.UTF_8).contains('F'))
  }

  /** OSC 仕様の核心: bool は type tag のみでワイヤー上のデータバイトは 0 */
  @Test
  fun boolHasNoDataBytes() {
    // bool は type tag のみでデータバイトなし → int と比べて短い
    val boolPacket = OscMessagePacket(address = "/a", arguments = listOf(true))
    val intPacket = OscMessagePacket(address = "/a", arguments = listOf(42))
    val boolLen = OscCodec.encode(boolPacket).size
    val intLen = OscCodec.encode(intPacket).size
    assertTrue(boolLen < intLen, "bool ($boolLen bytes) は int ($intLen bytes) より短いはず")
  }

  @Test
  fun multipleBoolsEncodesAndDecodes() {
    val packet = OscMessagePacket(address = "/flags", arguments = listOf(true, false, true))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertEquals(listOf(true, false, true), decoded.arguments)
  }

  @Test
  fun mixedBoolAndScalarEncodesAndDecodes() {
    val packet =
        OscMessagePacket(
            address = "/mixed",
            arguments = listOf(1, true, "hello", false, 3.14f),
        )
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertEquals("/mixed", decoded.address)
    assertEquals(1, decoded.arguments[0])
    assertEquals(true, decoded.arguments[1])
    assertEquals("hello", decoded.arguments[2])
    assertEquals(false, decoded.arguments[3])
    assertEquals(3.14f, decoded.arguments[4])
  }

  // -------------------------------------------------------------------------
  // blob: b タグ
  // -------------------------------------------------------------------------

  /** 任意バイナリ: int32(size) + ペイロード + パディングのラウンドトリップ */
  @Test
  fun blobEncodesAndDecodes() {
    val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
    val packet = OscMessagePacket(address = "/data", arguments = listOf(data))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertContentEquals(data, decoded.arguments[0] as ByteArray)
  }

  /** サイズ 0 の blob は int32(0) のみ送出される */
  @Test
  fun emptyBlobEncodesAndDecodes() {
    val empty = ByteArray(0)
    val packet = OscMessagePacket(address = "/data", arguments = listOf(empty))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertContentEquals(empty, decoded.arguments[0] as ByteArray)
  }

  @Test
  fun blobSizeNonMultipleOf4PadsCorrectly() {
    // 3バイト → 4バイト境界にパディングされ後続引数がずれないことを確認
    val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
    val packet = OscMessagePacket(address = "/d", arguments = listOf(data, 99))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertContentEquals(data, decoded.arguments[0] as ByteArray)
    assertEquals(99, decoded.arguments[1])
  }

  @Test
  fun blobSizeExactMultipleOf4EncodesAndDecodes() {
    val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) // 8バイト = 4の倍数
    val packet = OscMessagePacket(address = "/d", arguments = listOf(data))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertContentEquals(data, decoded.arguments[0] as ByteArray)
  }

  @Test
  fun blobTypeTagContainsB() {
    val data = byteArrayOf(0x42)
    val packet = OscMessagePacket(address = "/b", arguments = listOf(data))
    val bytes = OscCodec.encode(packet)
    assertTrue(bytes.toString(Charsets.UTF_8).contains('b'))
  }

  @Test
  fun multipleBlobsEncodesAndDecodes() {
    val a = byteArrayOf(1, 2)
    val b = byteArrayOf(3, 4, 5)
    val packet = OscMessagePacket(address = "/blobs", arguments = listOf(a, b))
    val decoded = OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
    assertContentEquals(a, decoded.arguments[0] as ByteArray)
    assertContentEquals(b, decoded.arguments[1] as ByteArray)
  }
}
