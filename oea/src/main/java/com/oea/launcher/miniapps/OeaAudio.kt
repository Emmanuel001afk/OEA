package com.oea.launcher.miniapps

import kotlin.math.roundToInt

/**
 * The audio maths behind writing subtitles from a soundtrack.
 *
 * A recognizer handed a file descriptor wants one thing: 16 kHz, one channel,
 * 16-bit PCM. A film's soundtrack is 48 kHz, two channels, in whatever codec the
 * disc used. Somebody has to bridge that, and it should not be a black box,
 * because when a cue lands a second late the difference between "the decoder"
 * and "the sample rate" is the whole bug.
 *
 * So the parts that can be wrong in an interesting way are here, pure and
 * testable: mixing to one channel, resampling without drifting across chunk
 * boundaries, and writing a WAV header. Reading the codec belongs to
 * [OeaAutoCaption]; finding where somebody is talking belongs to
 * [OeaSpeechRegions].
 */
object OeaAudio {

    /** Every recognizer takes this when it is handed audio instead of a mic. */
    const val CAPTION_RATE = 16_000

    /** Bytes per second of caption audio: 16-bit, one channel. */
    const val CAPTION_BYTES_PER_SECOND = CAPTION_RATE * 2

    /** How many bytes [milliseconds] of caption audio takes. */
    fun bytesFor(milliseconds: Long): Long = milliseconds * CAPTION_BYTES_PER_SECOND / 1000L

    /** The sample a byte offset lands on, rounding down to a whole frame. */
    fun samplesFor(bytes: Long): Int = (bytes / 2L).toInt()

    /**
     * Interleaved stereo (or 5.1) down to one channel, by averaging.
     *
     * Averaging rather than taking the left channel: a film that puts the
     * dialogue centre and the music wide sums differently, and dropping a
     * channel can drop the words with it.
     */
    fun mono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1 || interleaved.isEmpty()) return interleaved
        val frames = interleaved.size / channels
        if (frames <= 0) return ShortArray(0)
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            val at = frame * channels
            var sum = 0
            for (channel in 0 until channels) sum += interleaved[at + channel].toInt()
            out[frame] = (sum / channels).toShort()
        }
        return out
    }

    /**
     * Linear resampling that keeps its place between chunks.
     *
     * The naive version — resample each buffer on its own — loses or repeats the
     * fraction of a sample at every boundary. At 48 kHz to 16 kHz that is 2000
     * boundaries in a ten-minute stretch, and the cues walk away from the
     * picture by a second or more. The carry is the point of this class.
     */
    class Resampler(val fromRate: Int, val toRate: Int = CAPTION_RATE) {

        init {
            require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        }

        /** Input samples consumed per output sample. */
        private val step = fromRate.toDouble() / toRate.toDouble()

        private var previous = 0.0
        private var started = false
        private var position = 0.0

        /** Resamples one chunk. Feed chunks in order; do not skip any. */
        fun push(input: ShortArray): ShortArray {
            if (input.isEmpty()) return ShortArray(0)
            if (!started) {
                previous = input[0].toDouble()
                started = true
            }
            // One extra sample of room for the carry and for rounding.
            val capacity = ((input.size - position) / step).toInt() + 2
            val out = ShortArray(capacity.coerceAtLeast(1))
            var at = position
            var count = 0
            while (at < input.size && count < out.size) {
                val index = at.toInt()
                val left = if (index == 0) previous else input[index - 1].toDouble()
                val right = input[index].toDouble()
                val value = left + (right - left) * (at - index)
                out[count] = value.roundToInt().coerceIn(-32_768, 32_767).toShort()
                count++
                at += step
            }
            position = at - input.size
            previous = input[input.size - 1].toDouble()
            return if (count == out.size) out else out.copyOf(count)
        }
    }

    /** 16-bit little-endian bytes, the order a pipe and a WAV file both want. */
    fun bytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            val bits = value.toInt()
            out[index * 2] = (bits and 0xFF).toByte()
            out[index * 2 + 1] = ((bits shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The 44-byte canonical WAV header for mono/stereo 16-bit PCM. */
    fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int = 1): ByteArray {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        val header = ByteArray(44)
        fun ascii(at: Int, text: String) {
            text.forEachIndexed { index, ch -> header[at + index] = ch.code.toByte() }
        }
        fun le32(at: Int, value: Int) {
            header[at] = (value and 0xFF).toByte()
            header[at + 1] = ((value shr 8) and 0xFF).toByte()
            header[at + 2] = ((value shr 16) and 0xFF).toByte()
            header[at + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun le16(at: Int, value: Int) {
            header[at] = (value and 0xFF).toByte()
            header[at + 1] = ((value shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)              // PCM
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, 16)             // bits per sample
        ascii(36, "data")
        le32(40, dataBytes)
        return header
    }

    /** True when [samples] carry nothing anyone could recognise. */
    fun isSilent(samples: ShortArray, floor: Int = 60): Boolean {
        for (sample in samples) {
            if (sample > floor || sample < -floor) return false
        }
        return true
    }
}
