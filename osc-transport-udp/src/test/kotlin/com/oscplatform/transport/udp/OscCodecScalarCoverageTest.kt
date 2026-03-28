package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscMessagePacket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [OscCodec] におけるスカラー型（Int, Float, String）のエンコード・デコードのカバレッジテスト。
 *
 * 正常値・境界値・混合引数を網羅し、ラウンドトリップの正しさを検証する。
 */
class OscCodecScalarCoverageTest {

  // -------------------------------------------------------------------------
  // ヘルパー: エンコード→デコードのラウンドトリップ
  // -------------------------------------------------------------------------

  /**
   * [OscMessagePacket] をエンコードしてデコードし、結果を返す。
   *
   * @param packet ラウンドトリップ対象のパケット
   * @return デコード後の [OscMessagePacket]
   */
  private fun roundTrip(packet: OscMessagePacket): OscMessagePacket {
    return OscCodec.decode(OscCodec.encode(packet)) as OscMessagePacket
  }

  // -------------------------------------------------------------------------
  // Int ラウンドトリップ
  // -------------------------------------------------------------------------

  /** 通常の正の整数値がラウンドトリップで保存されること。 */
  @Test
  fun intRoundTrip() {
    val packet = OscMessagePacket(address = "/int", arguments = listOf(42))
    val decoded = roundTrip(packet)
    assertEquals("/int", decoded.address)
    assertEquals(listOf(42), decoded.arguments)
  }

  /** 負の整数値がラウンドトリップで保存されること。 */
  @Test
  fun negativeIntRoundTrip() {
    val packet = OscMessagePacket(address = "/neg", arguments = listOf(-1))
    val decoded = roundTrip(packet)
    assertEquals(listOf(-1), decoded.arguments)
  }

  /** [Int.MAX_VALUE] がラウンドトリップで保存されること。 */
  @Test
  fun intMaxValueRoundTrip() {
    val packet = OscMessagePacket(address = "/max", arguments = listOf(Int.MAX_VALUE))
    val decoded = roundTrip(packet)
    assertEquals(listOf(Int.MAX_VALUE), decoded.arguments)
  }

  /** [Int.MIN_VALUE] がラウンドトリップで保存されること。 */
  @Test
  fun intMinValueRoundTrip() {
    val packet = OscMessagePacket(address = "/min", arguments = listOf(Int.MIN_VALUE))
    val decoded = roundTrip(packet)
    assertEquals(listOf(Int.MIN_VALUE), decoded.arguments)
  }

  /** ゼロがラウンドトリップで保存されること。 */
  @Test
  fun intZeroRoundTrip() {
    val packet = OscMessagePacket(address = "/zero", arguments = listOf(0))
    val decoded = roundTrip(packet)
    assertEquals(listOf(0), decoded.arguments)
  }

  // -------------------------------------------------------------------------
  // Float ラウンドトリップ
  // -------------------------------------------------------------------------

  /** 通常の浮動小数点値がラウンドトリップで保存されること。 */
  @Test
  fun floatRoundTrip() {
    val packet = OscMessagePacket(address = "/float", arguments = listOf(1.0f))
    val decoded = roundTrip(packet)
    assertEquals("/float", decoded.address)
    assertEquals(listOf(1.0f), decoded.arguments)
  }

  /** 小数部を持つ浮動小数点値（3.14f）がラウンドトリップで保存されること。 */
  @Test
  fun fractionalFloatRoundTrip() {
    val packet = OscMessagePacket(address = "/pi", arguments = listOf(3.14f))
    val decoded = roundTrip(packet)
    assertEquals(3.14f, decoded.arguments[0] as Float)
  }

  /** 負の浮動小数点値がラウンドトリップで保存されること。 */
  @Test
  fun negativeFloatRoundTrip() {
    val packet = OscMessagePacket(address = "/negf", arguments = listOf(-0.5f))
    val decoded = roundTrip(packet)
    assertEquals(-0.5f, decoded.arguments[0] as Float)
  }

  /** ゼロの浮動小数点値がラウンドトリップで保存されること。 */
  @Test
  fun floatZeroRoundTrip() {
    val packet = OscMessagePacket(address = "/zerof", arguments = listOf(0.0f))
    val decoded = roundTrip(packet)
    assertEquals(0.0f, decoded.arguments[0] as Float)
  }

  // -------------------------------------------------------------------------
  // String ラウンドトリップ
  // -------------------------------------------------------------------------

  /** 通常の文字列がラウンドトリップで保存されること。 */
  @Test
  fun stringRoundTrip() {
    val packet = OscMessagePacket(address = "/str", arguments = listOf("hello"))
    val decoded = roundTrip(packet)
    assertEquals("/str", decoded.address)
    assertEquals(listOf("hello"), decoded.arguments)
  }

  /** 空文字列がラウンドトリップで保存されること。 */
  @Test
  fun emptyStringRoundTrip() {
    val packet = OscMessagePacket(address = "/empty", arguments = listOf(""))
    val decoded = roundTrip(packet)
    assertEquals(listOf(""), decoded.arguments)
  }

  /** 4 バイト境界を超える長い文字列がラウンドトリップで保存されること。 */
  @Test
  fun longStringRoundTrip() {
    val longStr = "abcdefghijklmnopqrstuvwxyz0123456789"
    val packet = OscMessagePacket(address = "/long", arguments = listOf(longStr))
    val decoded = roundTrip(packet)
    assertEquals(listOf(longStr), decoded.arguments)
  }

  /** 日本語マルチバイト文字列がラウンドトリップで保存されること。 */
  @Test
  fun multiBytStringRoundTrip() {
    val jp = "こんにちは"
    val packet = OscMessagePacket(address = "/jp", arguments = listOf(jp))
    val decoded = roundTrip(packet)
    assertEquals(listOf(jp), decoded.arguments)
  }

  // -------------------------------------------------------------------------
  // 混合引数
  // -------------------------------------------------------------------------

  /** Int, Float, String の混合引数が 1 メッセージ内でラウンドトリップできること。 */
  @Test
  fun mixedScalarTypesRoundTrip() {
    val packet =
        OscMessagePacket(
            address = "/mixed",
            arguments = listOf(100, 2.5f, "world"),
        )
    val decoded = roundTrip(packet)
    assertEquals("/mixed", decoded.address)
    assertEquals(3, decoded.arguments.size)
    assertEquals(100, decoded.arguments[0])
    assertEquals(2.5f, decoded.arguments[1])
    assertEquals("world", decoded.arguments[2])
  }

  /** 同じ型の引数を複数持つメッセージがラウンドトリップで保存されること。 */
  @Test
  fun multipleIntsRoundTrip() {
    val packet =
        OscMessagePacket(
            address = "/ints",
            arguments = listOf(1, 2, 3, 4, 5),
        )
    val decoded = roundTrip(packet)
    assertEquals(listOf(1, 2, 3, 4, 5), decoded.arguments)
  }

  /** 引数なしのメッセージがラウンドトリップで保存されること。 */
  @Test
  fun noArgumentsRoundTrip() {
    val packet = OscMessagePacket(address = "/noargs", arguments = emptyList())
    val decoded = roundTrip(packet)
    assertEquals("/noargs", decoded.address)
    assertEquals(emptyList<Any?>(), decoded.arguments)
  }
}
