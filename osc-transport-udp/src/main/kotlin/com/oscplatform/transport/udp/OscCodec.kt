package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscBundlePacket
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OSCプロトコルのエンコード・デコードを担当するコーデックオブジェクト。
 *
 * OSCメッセージおよびバンドルをバイト列との間で相互変換する。 バイトオーダーはOSC仕様に従いビッグエンディアンを使用する。
 */
internal object OscCodec {
  /**
   * [OscPacket] をOSCバイナリ形式のバイト配列にエンコードする。
   *
   * @param packet エンコード対象のOSCパケット（メッセージまたはバンドル）
   * @return OSCバイナリ形式のバイト配列
   */
  fun encode(packet: OscPacket): ByteArray {
    return when (packet) {
      is OscMessagePacket -> encodeMessage(packet)
      is OscBundlePacket -> encodeBundle(packet)
    }
  }

  /**
   * OSCバイナリ形式のバイト配列を [OscPacket] にデコードする。
   *
   * 先頭の文字列が `#bundle` であればバンドルとして、それ以外はメッセージとしてデコードする。
   *
   * @param bytes デコード対象のバイト配列
   * @return デコードされた [OscPacket]
   */
  fun decode(bytes: ByteArray): OscPacket {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val marker = peekOscString(buffer)
    return if (marker == "#bundle") {
      decodeBundle(buffer)
    } else {
      decodeMessage(buffer)
    }
  }

  /**
   * [OscMessagePacket] をOSCバイナリ形式のバイト配列にエンコードする。
   *
   * アドレスパターン、タイプタグ文字列、各引数のデータを順に結合して返す。
   *
   * @param packet エンコード対象のOSCメッセージパケット
   * @return エンコードされたバイト配列
   */
  private fun encodeMessage(packet: OscMessagePacket): ByteArray {
    val payload = mutableListOf<ByteArray>()
    payload += encodeOscString(packet.address)

    val typeTag = buildString {
      append(',')
      packet.arguments.forEach { arg ->
        append(
            when (arg) {
              is Int -> 'i'
              is Float,
              is Double -> 'f'
              is String -> 's'
              is Boolean -> if (arg) 'T' else 'F'
              is ByteArray -> 'b'
              else ->
                  error(
                      "Unsupported OSC argument type: ${arg?.let { it::class.simpleName } ?: "null"}")
            },
        )
      }
    }
    payload += encodeOscString(typeTag)

    packet.arguments.forEach { arg ->
      val bytes: ByteArray? =
          when (arg) {
            is Int -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(arg).array()
            is Float -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(arg).array()
            is Double ->
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(arg.toFloat()).array()
            is String -> encodeOscString(arg)
            is Boolean -> null // bool は type tag のみ、データバイトなし
            is ByteArray -> encodeOscBlob(arg)
            else ->
                error(
                    "Unsupported OSC argument type: ${arg?.let { it::class.simpleName } ?: "null"}")
          }
      if (bytes != null) payload += bytes
    }

    return join(payload)
  }

  /**
   * OSCバイナリ形式のバッファから [OscMessagePacket] をデコードする。
   *
   * アドレスパターンとタイプタグ文字列を読み取り、タイプタグに従って各引数をデコードする。
   *
   * @param buffer デコード元の [ByteBuffer]（読み取り位置が進められる）
   * @return デコードされた [OscMessagePacket]
   */
  private fun decodeMessage(buffer: ByteBuffer): OscMessagePacket {
    val address = readOscString(buffer)
    val typeTag = readOscString(buffer)
    require(typeTag.startsWith(',')) { "Invalid OSC type tag: $typeTag" }

    val args = mutableListOf<Any?>()
    typeTag.drop(1).forEach { tag ->
      val value: Any? =
          when (tag) {
            'i' -> buffer.int
            'f' -> buffer.float
            's' -> readOscString(buffer)
            'T' -> true
            'F' -> false
            'b' -> decodeOscBlob(buffer)
            else -> error("Unsupported OSC type tag: $tag")
          }
      args += value
    }

    return OscMessagePacket(address = address, arguments = args)
  }

  /**
   * [OscBundlePacket] をOSCバイナリ形式のバイト配列にエンコードする。
   *
   * `#bundle` マーカー、タイムタグ、各要素のサイズプレフィクス付きデータを順に結合して返す。
   *
   * @param bundle エンコード対象のOSCバンドルパケット
   * @return エンコードされたバイト配列
   */
  private fun encodeBundle(bundle: OscBundlePacket): ByteArray {
    val bytes = mutableListOf<ByteArray>()
    bytes += encodeOscString("#bundle")
    bytes += ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(bundle.timeTag).array()

    bundle.elements.forEach { element ->
      val encoded = encode(element)
      val size = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(encoded.size).array()
      bytes += size
      bytes += encoded
    }

    return join(bytes)
  }

  /**
   * OSCバイナリ形式のバッファから [OscBundlePacket] をデコードする。
   *
   * `#bundle` マーカーとタイムタグを読み取った後、残りのバッファから各要素を再帰的にデコードする。
   *
   * @param buffer デコード元の [ByteBuffer]（読み取り位置が進められる）
   * @return デコードされた [OscBundlePacket]
   */
  private fun decodeBundle(buffer: ByteBuffer): OscBundlePacket {
    val marker = readOscString(buffer)
    require(marker == "#bundle") { "Invalid OSC bundle marker: $marker" }
    val timeTag = buffer.long
    val elements = mutableListOf<OscPacket>()
    while (buffer.hasRemaining()) {
      val size = buffer.int
      val chunk = ByteArray(size)
      buffer.get(chunk)
      elements += decode(chunk)
    }
    return OscBundlePacket(timeTag = timeTag, elements = elements)
  }

  /**
   * バイト配列をOSC blob形式にエンコードする。
   *
   * 4バイトのサイズプレフィクスに続き、4バイト境界までパディングされたデータを返す。
   *
   * @param value エンコード対象のバイト配列
   * @return OSC blob形式にエンコードされたバイト配列
   */
  private fun encodeOscBlob(value: ByteArray): ByteArray {
    val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.size).array()
    val paddedData = ByteArray(paddedSize(value.size))
    value.copyInto(paddedData)
    return join(listOf(sizeBytes, paddedData))
  }

  /**
   * バッファからOSC blob形式のデータをデコードする。
   *
   * 4バイトのサイズを読み取り、その長さ分のデータを抽出した後、パディングをスキップする。
   *
   * @param buffer デコード元の [ByteBuffer]（読み取り位置が進められる）
   * @return デコードされたバイト配列
   */
  private fun decodeOscBlob(buffer: ByteBuffer): ByteArray {
    val size = buffer.int
    val bytes = ByteArray(size)
    buffer.get(bytes)
    val padding = paddingFor(size)
    if (padding > 0) buffer.position(buffer.position() + padding)
    return bytes
  }

  /**
   * 文字列をOSC文字列形式にエンコードする。
   *
   * UTF-8バイト列にヌル終端を付加し、4バイト境界までゼロパディングする。
   *
   * @param value エンコード対象の文字列
   * @return OSC文字列形式にエンコードされたバイト配列
   */
  private fun encodeOscString(value: String): ByteArray {
    val stringBytes = value.toByteArray(Charsets.UTF_8)
    val totalLength = paddedSize(stringBytes.size + 1)
    val out = ByteArray(totalLength)
    stringBytes.copyInto(out)
    return out
  }

  /**
   * バッファからOSC文字列を読み取る。
   *
   * ヌル終端までのバイト列をUTF-8文字列としてデコードし、4バイト境界までパディングをスキップする。
   *
   * @param buffer 読み取り元の [ByteBuffer]（読み取り位置が進められる）
   * @return デコードされた文字列
   * @throws IllegalArgumentException 文字列がヌル終端されていない場合
   */
  private fun readOscString(buffer: ByteBuffer): String {
    val start = buffer.position()
    var end = start
    while (end < buffer.limit() && buffer.get(end).toInt() != 0) {
      end++
    }
    require(end < buffer.limit()) { "OSC string is not null-terminated" }

    val size = end - start
    val bytes = ByteArray(size)
    buffer.get(bytes)
    buffer.get()

    val consumed = size + 1
    val padding = paddingFor(consumed)
    if (padding > 0) {
      buffer.position(buffer.position() + padding)
    }

    return bytes.toString(Charsets.UTF_8)
  }

  /**
   * バッファの現在位置を変更せずにOSC文字列を先読みする。
   *
   * バッファを複製して [readOscString] を呼び出すことで、元のバッファの読み取り位置は保持される。
   *
   * @param buffer 先読み元の [ByteBuffer]（読み取り位置は変更されない）
   * @return 先読みされた文字列
   */
  private fun peekOscString(buffer: ByteBuffer): String {
    val duplicate = buffer.duplicate()
    duplicate.order(ByteOrder.BIG_ENDIAN)
    return readOscString(duplicate)
  }

  /**
   * 指定されたサイズを4バイト境界に切り上げたサイズを返す。
   *
   * @param rawSize 元のサイズ（バイト数）
   * @return 4バイト境界に切り上げたサイズ
   */
  private fun paddedSize(rawSize: Int): Int {
    val padding = paddingFor(rawSize)
    return rawSize + padding
  }

  /**
   * 指定されたサイズを4バイト境界に揃えるために必要なパディングバイト数を返す。
   *
   * @param rawSize 元のサイズ（バイト数）
   * @return 必要なパディングバイト数（0〜3）
   */
  private fun paddingFor(rawSize: Int): Int {
    val remainder = rawSize % 4
    return if (remainder == 0) 0 else 4 - remainder
  }

  /**
   * 複数のバイト配列を順番に結合して1つのバイト配列にする。
   *
   * @param parts 結合対象のバイト配列のリスト
   * @return 結合されたバイト配列
   */
  private fun join(parts: List<ByteArray>): ByteArray {
    val totalSize = parts.sumOf { it.size }
    val out = ByteArray(totalSize)
    var offset = 0
    parts.forEach { bytes ->
      bytes.copyInto(out, destinationOffset = offset)
      offset += bytes.size
    }
    return out
  }
}
