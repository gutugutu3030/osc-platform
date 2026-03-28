package com.oscplatform.transport.udp

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [OscCodec] に対して不正なバイト列を与えた場合のエラーハンドリングを検証するテスト。
 *
 * OSC ワイヤーフォーマットの各要素（アドレス、タイプタグ、バンドルヘッダ、パケット長）が 壊れている場合に適切な例外が発生することを確認する。
 */
class OscCodecMalformedPacketTest {

  // -------------------------------------------------------------------------
  // ヘルパー: OSC 文字列をワイヤーフォーマットのバイト列に変換する
  // -------------------------------------------------------------------------

  /**
   * 文字列を OSC ワイヤーフォーマットのバイト列に変換する。
   *
   * UTF-8 バイト列にヌル終端を付加し、4 バイト境界までゼロパディングする。
   *
   * @param s 変換対象の文字列
   * @return OSC 文字列形式のバイト配列
   */
  private fun oscString(s: String): ByteArray {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val totalLength = ((bytes.size + 1 + 3) / 4) * 4
    val out = ByteArray(totalLength)
    bytes.copyInto(out)
    return out
  }

  // -------------------------------------------------------------------------
  // 不正なタイプタグ
  // -------------------------------------------------------------------------

  /** サポートされていないタイプタグ文字 'z' を含むパケットをデコードすると "Unsupported OSC type tag" メッセージの例外が発生すること。 */
  @Test
  fun decodeWithUnsupportedTypeTagThrows() {
    // 有効なアドレス "/a" + 不正なタイプタグ ",z"
    val address = oscString("/a")
    val typeTag = oscString(",z")
    val packet = address + typeTag

    val ex = assertFailsWith<IllegalStateException> { OscCodec.decode(packet) }
    assertTrue(
        ex.message!!.contains("Unsupported OSC type tag"),
        "例外メッセージに 'Unsupported OSC type tag' が含まれること: ${ex.message}",
    )
  }

  /**
   * 複数のタイプタグのうち 2 番目が不正な場合でも例外が発生すること。
   *
   * 最初の 'i' は有効だが 'z' で失敗する。
   */
  @Test
  fun decodeWithSecondUnsupportedTypeTagThrows() {
    // アドレス "/a" + タイプタグ ",iz" + int32(42)
    val address = oscString("/a")
    val typeTag = oscString(",iz")
    val intBytes =
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(42).array()
    val packet = address + typeTag + intBytes

    val ex = assertFailsWith<IllegalStateException> { OscCodec.decode(packet) }
    assertTrue(
        ex.message!!.contains("Unsupported OSC type tag"),
        "例外メッセージに 'Unsupported OSC type tag' が含まれること: ${ex.message}",
    )
  }

  // -------------------------------------------------------------------------
  // ヌル終端されていない文字列
  // -------------------------------------------------------------------------

  /**
   * ヌルバイトを含まないバッファをデコードすると "not null-terminated" メッセージの例外が発生すること。
   *
   * アドレス部分にヌル終端がない不正バイト列を作成して検証する。
   */
  @Test
  fun decodeNonNullTerminatedStringThrows() {
    // ヌル終端なしの 4 バイト（全て非ゼロ）
    val malformed = byteArrayOf(0x2F, 0x61, 0x62, 0x63) // "/abc" without null terminator

    val ex = assertFailsWith<IllegalArgumentException> { OscCodec.decode(malformed) }
    assertTrue(
        ex.message!!.contains("not null-terminated"),
        "例外メッセージに 'not null-terminated' が含まれること: ${ex.message}",
    )
  }

  /** アドレスは有効だがタイプタグ部分にヌル終端がない場合も "not null-terminated" 例外が発生すること。 */
  @Test
  fun decodeNonNullTerminatedTypeTagThrows() {
    // 有効なアドレス + タイプタグ部分がヌル終端なし
    val address = oscString("/a")
    // ヌルなしで ',' だけのバイト列（バッファ末端まで非ゼロ）
    val malformedTypeTag = byteArrayOf(0x2C, 0x69, 0x69, 0x69) // ",iii" no null

    val packet = address + malformedTypeTag

    val ex = assertFailsWith<IllegalArgumentException> { OscCodec.decode(packet) }
    assertTrue(
        ex.message!!.contains("not null-terminated"),
        "例外メッセージに 'not null-terminated' が含まれること: ${ex.message}",
    )
  }

  // -------------------------------------------------------------------------
  // 不正なバンドルヘッダ
  // -------------------------------------------------------------------------

  /**
   * "#bundle" ではない文字列で始まるバイト列を直接バンドルとしてデコードしようとすると、 メッセージとして解釈されるため type tag の require でエラーになること。
   *
   * デコーダは先頭が "#bundle" でなければメッセージパスを通るため、 適切な形式でなければ例外が発生する。
   */
  @Test
  fun decodeBundleWithInvalidMarkerThrows() {
    // "#bundle" ではない文字列 + タイムタグ相当のバイト列
    val fakeMarker = oscString("#notbundle")
    val timeTag = ByteArray(8) // ダミータイムタグ

    val packet = fakeMarker + timeTag

    // "#notbundle" はメッセージアドレスとして解釈される
    // タイムタグ部分がタイプタグとして読まれ require で失敗する
    assertFailsWith<Exception> { OscCodec.decode(packet) }
  }

  // -------------------------------------------------------------------------
  // 切り詰められたパケット
  // -------------------------------------------------------------------------

  /** 有効なアドレスとタイプタグを持つがデータ部分が不足するパケットをデコードすると バッファアンダーフロー例外が発生すること。 */
  @Test
  fun decodeTruncatedPacketWithMissingDataThrows() {
    // アドレス "/a" + タイプタグ ",i" だが int32 データがない
    val address = oscString("/a")
    val typeTag = oscString(",i")
    val truncated = address + typeTag // int のデータバイトなし

    assertFailsWith<Exception> { OscCodec.decode(truncated) }
  }

  /** 空のバイト配列をデコードすると例外が発生すること。 */
  @Test
  fun decodeEmptyBytesThrows() {
    assertFailsWith<Exception> { OscCodec.decode(ByteArray(0)) }
  }

  /** 1 バイトだけの極端に短いバイト配列をデコードすると例外が発生すること。 */
  @Test
  fun decodeSingleByteThrows() {
    assertFailsWith<Exception> { OscCodec.decode(byteArrayOf(0x2F)) } // '/' のみ
  }
}
