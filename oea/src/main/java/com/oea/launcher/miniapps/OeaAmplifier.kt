package com.oea.launcher.miniapps

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.abs

/**
 * OEA's hearing aid for the Console: the microphone in, the earphones out.
 *
 * There is no magic in it. A 16-bit PCM stream is read from the mic, scaled and
 * soft-clipped, then written straight to an output stream — one buffer at a time on
 * its own high-priority thread. That is the entire trick, and it is the same trick the
 * platform's own hearing-assistance features use.
 *
 * Two things worth saying out loud, because both surprise people:
 *
 *  - Loudspeakers plus microphone plus gain equals a squeal. Headphones, or keep the
 *    phone away from the speaker. The limiter here only stops the sample from
 *    wrapping into crackle; it cannot stop an acoustic feedback loop.
 *  - Android mutes the microphone for apps that are not in front. So the amplifier
 *    lives while OEA's screen is up and stops politely the moment it is not; it is
 *    not a background service and OEA does not claim otherwise.
 */
internal object OeaAmplifier {

    private const val SAMPLE_RATE = 48000
    private const val MAX_GAIN = 6f

    @Volatile
    private var running = false

    /** Loudness, 1× to 6×. Live: the loop reads it on every buffer. */
    @Volatile
    var gain: Float = 2f
        set(v) {
            field = v.coerceIn(0.5f, MAX_GAIN)
        }

    @Volatile
    var lastError: String? = null
        private set

    /** Measured round trip, in milliseconds, once the stream is moving. */
    @Volatile
    var latencyMs: Int = 0
        private set

    val isRunning: Boolean get() = running

    fun start(context: Context, onResult: (Boolean, String) -> Unit) {
        if (running) {
            onResult(true, "Already amplifying")
            return
        }
        val inMask = AudioFormat.CHANNEL_IN_MONO
        val outMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minIn = runCatching { AudioRecord.getMinBufferSize(SAMPLE_RATE, inMask, encoding) }.getOrDefault(0)
        val minOut = runCatching { AudioTrack.getMinBufferSize(SAMPLE_RATE, outMask, encoding) }.getOrDefault(0)
        if (minIn <= 0 || minOut <= 0) {
            val message = "This phone will not give OEA a 48 kHz mono stream to work with"
            lastError = message
            onResult(false, message)
            return
        }
        // One buffer both sides can live with, doubled so the loop is never chasing it.
        val frameCount = maxOf(minIn, minOut) / 2 * 2
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, inMask, encoding, frameCount * 2)
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            val message = "The microphone is busy, or it was refused"
            lastError = message
            onResult(false, message)
            return
        }
        // The builder, not a constructor: AudioTrack's public constructors are a
        // deprecated six-argument form and an undocumented shape, and the builder is
        // the API that is actually meant to be called.
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(encoding)
                        .setChannelMask(outMask)
                        .build()
                )
                .setBufferSizeInBytes(frameCount * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { record.release() }
            runCatching { track?.release() }
            if (track?.state != AudioTrack.STATE_INITIALIZED) runCatching { track?.stop() }
            val message = "This phone will not open an output stream for OEA"
            lastError = message
            onResult(false, message)
            return
        }

        lastError = null
        running = true
        Thread {
            val buffer = ShortArray(frameCount)
            var squealGuard = 0
            try {
                record.startRecording()
                track.play()
                // getLatency() is already milliseconds.
                latencyMs = runCatching { track.getLatency() }.getOrDefault(0)
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION) break
                        continue
                    }
                    val g = gain
                    var loud = false
                    for (index in 0 until read) {
                        val scaled = buffer[index] * g
                        // Soft clip: past the top of the range a sample wraps into a
                        // crack, so it is pushed against the ceiling instead.
                        val shaped = when {
                            scaled > 32700f -> 32700f
                            scaled < -32700f -> -32700f
                            else -> scaled
                        }
                        if (abs(shaped) > 30000f) loud = true
                        buffer[index] = shaped.toInt().toShort()
                    }
                    // A held squeal is a full-scale wave that never stops: pull the
                    // gain down a notch until the loop breaks, then let it climb back.
                    squealGuard = if (loud) squealGuard + 1 else (squealGuard - 2).coerceAtLeast(0)
                    if (squealGuard > 40) {
                        gain = (gain * 0.85f).coerceAtLeast(0.5f)
                        squealGuard = 0
                    }
                    track.write(buffer, 0, read)
                }
            } catch (_: Throwable) {
                // A stream torn away mid-loop is the phone taking the mic back.
            } finally {
                running = false
                runCatching { record.stop() }
                runCatching { track.stop() }
                runCatching { record.release() }
                runCatching { track.release() }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        onResult(true, "Microphone to earphones — keep the phone off the speaker")
    }

    fun stop() {
        running = false
    }
}
