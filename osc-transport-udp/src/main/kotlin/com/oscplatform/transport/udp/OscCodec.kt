package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscBundlePacket
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object OscCodec {
    fun encode(packet: OscPacket): ByteArray {
        return when (packet) {
            is OscMessagePacket -> encodeMessage(packet)
            is OscBundlePacket -> encodeBundle(packet)
        }
    }

    fun decode(bytes: ByteArray): OscPacket {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val marker = peekOscString(buffer)
        return if (marker == "#bundle") {
            decodeBundle(buffer)
        } else {
            decodeMessage(buffer)
        }
    }

    private fun encodeMessage(packet: OscMessagePacket): ByteArray {
        val payload = mutableListOf<ByteArray>()
        payload += encodeOscString(packet.address)

        val typeTag = buildString {
            append(',')
            packet.arguments.forEach { arg ->
                append(
                    when (arg) {
                        is Int -> 'i'
                        is Float, is Double -> 'f'
                        is String -> 's'
                        is Boolean -> if (arg) 'T' else 'F'
                        is ByteArray -> 'b'
                        else -> error("Unsupported OSC argument type: ${arg?.let { it::class.simpleName } ?: "null"}")
                    },
                )
            }
        }
        payload += encodeOscString(typeTag)

        packet.arguments.forEach { arg ->
            val bytes: ByteArray? = when (arg) {
                is Int -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(arg).array()
                is Float -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(arg).array()
                is Double -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(arg.toFloat()).array()
                is String -> encodeOscString(arg)
                is Boolean -> null  // bool は type tag のみ、データバイトなし
                is ByteArray -> encodeOscBlob(arg)
                else -> error("Unsupported OSC argument type: ${arg?.let { it::class.simpleName } ?: "null"}")
            }
            if (bytes != null) payload += bytes
        }

        return join(payload)
    }

    private fun decodeMessage(buffer: ByteBuffer): OscMessagePacket {
        val address = readOscString(buffer)
        val typeTag = readOscString(buffer)
        require(typeTag.startsWith(',')) { "Invalid OSC type tag: $typeTag" }

        val args = mutableListOf<Any?>()
        typeTag.drop(1).forEach { tag ->
            val value: Any? = when (tag) {
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

    private fun encodeOscBlob(value: ByteArray): ByteArray {
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.size).array()
        val paddedData = ByteArray(paddedSize(value.size))
        value.copyInto(paddedData)
        return join(listOf(sizeBytes, paddedData))
    }

    private fun decodeOscBlob(buffer: ByteBuffer): ByteArray {
        val size = buffer.int
        val bytes = ByteArray(size)
        buffer.get(bytes)
        val padding = paddingFor(size)
        if (padding > 0) buffer.position(buffer.position() + padding)
        return bytes
    }

    private fun encodeOscString(value: String): ByteArray {
        val stringBytes = value.toByteArray(Charsets.UTF_8)
        val totalLength = paddedSize(stringBytes.size + 1)
        val out = ByteArray(totalLength)
        stringBytes.copyInto(out)
        return out
    }

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

    private fun peekOscString(buffer: ByteBuffer): String {
        val duplicate = buffer.duplicate()
        duplicate.order(ByteOrder.BIG_ENDIAN)
        return readOscString(duplicate)
    }

    private fun paddedSize(rawSize: Int): Int {
        val padding = paddingFor(rawSize)
        return rawSize + padding
    }

    private fun paddingFor(rawSize: Int): Int {
        val remainder = rawSize % 4
        return if (remainder == 0) 0 else 4 - remainder
    }

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
