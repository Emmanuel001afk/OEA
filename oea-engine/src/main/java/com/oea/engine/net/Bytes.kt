package com.oea.engine.net

import com.oea.engine.math.MathX
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** Thrown when a received message is truncated or malformed; the message is dropped. */
class NetFormatException(message: String) : Exception(message)

/**
 * Compact binary writer for network messages: fixed-size integers (big
 * endian), LEB128 varints, zig-zag signed varints, length-prefixed UTF-8
 * strings and quantised floats.
 */
class ByteWriter(initial: Int = 64) {
    var buffer = ByteArray(initial)
        private set
    var size = 0
        private set

    fun reset(): ByteWriter { size = 0; return this }

    private fun ensure(n: Int) {
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
    }

    fun u8(v: Int): ByteWriter { ensure(1); buffer[size++] = v.toByte(); return this }
    fun bool(v: Boolean): ByteWriter = u8(if (v) 1 else 0)
    fun u16(v: Int): ByteWriter { ensure(2); buffer[size++] = (v shr 8).toByte(); buffer[size++] = v.toByte(); return this }
    fun i32(v: Int): ByteWriter {
        ensure(4)
        buffer[size++] = (v shr 24).toByte(); buffer[size++] = (v shr 16).toByte(); buffer[size++] = (v shr 8).toByte(); buffer[size++] = v.toByte()
        return this
    }
    fun i64(v: Long): ByteWriter { i32((v ushr 32).toInt()); return i32(v.toInt()) }
    fun f32(v: Float): ByteWriter = i32(java.lang.Float.floatToRawIntBits(v))

    /** Unsigned LEB128. */
    fun varint(v: Int): ByteWriter {
        var x = v
        while (x and 0x7F.inv() != 0) { u8((x and 0x7F) or 0x80); x = x ushr 7 }
        return u8(x)
    }

    fun varlong(v: Long): ByteWriter {
        var x = v
        while (x and 0x7FL.inv() != 0L) { u8(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        return u8(x.toInt())
    }

    /** Signed zig-zag varint. */
    fun svarint(v: Int): ByteWriter = varint((v shl 1) xor (v shr 31))

    fun string(s: String): ByteWriter {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        varint(b.size)
        return bytes(b)
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteWriter {
        ensure(len)
        System.arraycopy(b, off, buffer, size, len)
        size += len
        return this
    }

    /** [v] in [min, max] mapped to 16 bits (resolution (max-min)/65535). */
    fun q16(v: Float, min: Float, max: Float): ByteWriter =
        u16(((MathX.clamp(v, min, max) - min) / (max - min) * 65535f + 0.5f).toInt())

    /** [v] in [min, max] mapped to 8 bits. */
    fun q8(v: Float, min: Float, max: Float): ByteWriter =
        u8(((MathX.clamp(v, min, max) - min) / (max - min) * 255f + 0.5f).toInt())

    /** Angle in radians, 16 bits. */
    fun angle(rad: Float): ByteWriter = u16(((MathX.wrapAngle(rad) + MathX.PI_F) / MathX.TAU * 65535f + 0.5f).toInt() and 0xFFFF)

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Reader matching [ByteWriter]. Every read is bounds-checked. */
class ByteReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    val remaining get() = end - pos
    val position get() = pos

    private fun need(n: Int) { if (pos + n > end) throw NetFormatException("Message truncated") }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun bool(): Boolean = u8() != 0
    fun u16(): Int { need(2); val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2; return v }
    fun i32(): Int {
        need(4)
        val v = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }
    fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL)
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

    fun varint(): Int {
        var result = 0; var shift = 0
        while (shift < 35) {
            val b = u8()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw NetFormatException("Varint too long")
    }

    fun varlong(): Long {
        var result = 0L; var shift = 0
        while (shift < 70) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw NetFormatException("Varlong too long")
    }

    fun svarint(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }

    fun string(maxBytes: Int = 4096): String {
        val n = varint()
        if (n < 0 || n > maxBytes) throw NetFormatException("String too long ($n)")
        need(n)
        val s = String(data, pos, n, StandardCharsets.UTF_8)
        pos += n
        return s
    }

    fun bytes(n: Int): ByteArray { need(n); val b = data.copyOfRange(pos, pos + n); pos += n; return b }
    fun rest(): ByteArray = bytes(remaining)

    fun q16(min: Float, max: Float): Float = min + u16() / 65535f * (max - min)
    fun q8(min: Float, max: Float): Float = min + u8() / 255f * (max - min)
    fun angle(): Float = u16() / 65535f * MathX.TAU - MathX.PI_F
}

/** Codes, hashes and checksums for human-shareable connection codes. */
object Codes {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * RFC 4648 Base32 without padding. Upper-case letters and digits only:
     * survives messengers' auto-capitalisation and fits QR alphanumeric mode.
     */
    fun base32(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0; var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Lenient decoding: ignores spaces, dashes and line breaks; fixes common look-alikes (0→O, 1→I, 8→B). */
    fun base32Decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(text.length * 5 / 8 + 1)
        var buffer = 0; var bits = 0
        for (raw in text) {
            val c = when (val u = raw.uppercaseChar()) { '0' -> 'O'; '1' -> 'I'; '8' -> 'B'; else -> u }
            if (c == ' ' || c == '-' || c == '\n' || c == '\r' || c == '\t' || c == '.') continue
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun crc32(data: ByteArray, off: Int = 0, len: Int = data.size - off): Int {
        val c = CRC32()
        c.update(data, off, len)
        return c.value.toInt()
    }

    /** 32-bit FNV-1a, for compact game ids. */
    fun fnv1a(s: String): Int {
        var h = -0x7ee3623b
        for (b in s.toByteArray(StandardCharsets.UTF_8)) { h = h xor (b.toInt() and 0xFF); h *= 16777619 }
        return h
    }

    /** Groups a code for display ("ABCD EFGH ..."). Decoding ignores the spaces. */
    fun group(code: String, size: Int = 4): String = code.chunked(size).joinToString(" ")
}
