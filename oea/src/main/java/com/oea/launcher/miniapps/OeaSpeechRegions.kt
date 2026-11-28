package com.oea.launcher.miniapps

import kotlin.math.sqrt

/**
 * Where in a soundtrack somebody is talking.
 *
 * This is the piece that makes captions line up. A recognizer will happily
 * return a paragraph for a stretch of audio and no idea where in that stretch
 * any of it was said — so if VIP asked one long session to transcribe a film, it
 * would have words and no clock, and every cue would land wherever it felt like.
 * Instead the sound is measured here, in twenty-millisecond frames: speech is
 * everything louder than the room, runs shorter than a cough are dropped, a
 * pause shorter than a breath does not break a sentence, and anything longer
 * than eight seconds is cut at its quietest moment. Each region is then
 * transcribed on its own, and its own bounds are the cue's timing.
 *
 * The clock therefore comes from the audio, not from the recognizer — which
 * means it is exact, testable without a phone, and the same on every device.
 */
object OeaSpeechRegions {

    /** One stretch of speech, in milliseconds from the start of the clip. */
    class Region(val startMs: Long, val endMs: Long) {
        val lengthMs: Long get() = endMs - startMs
        fun overlaps(start: Long, end: Long): Boolean = endMs > start && startMs < end
        override fun equals(other: Any?): Boolean =
            other is Region && other.startMs == startMs && other.endMs == endMs
        override fun hashCode(): Int = (startMs * 31 + endMs).hashCode()
        override fun toString(): String = "$startMs–$endMs"
    }

    /**
     * The thresholds the detector uses.
     *
     * [quietRms] is an absolute floor — quieter than about -43 dBFS is never a
     * voice, whatever the rest of the film is doing. [floorFactor] is the
     * relative one, which is what makes this work on a film with a loud score:
     * the tenth-percentile frame is the room tone of that particular clip, and
     * speech has to be clearly above its own film's floor.
     */
    class Settings(
        val frameMs: Int = 20,
        val quietRms: Double = 220.0,
        val floorFactor: Double = 2.4,
        val minimumMs: Long = 280,
        val mergeGapMs: Long = 360,
        val maximumMs: Long = 8_000,
        val padMs: Long = 140
    )

    /** One-shot helper for tests and for callers that already hold the audio. */
    fun find(samples: ShortArray, sampleRate: Int, settings: Settings = Settings()): List<Region> =
        Detector(sampleRate, settings).apply { push(samples) }.finish()

    /**
     * Streams audio in and remembers only frame energies, so a two-hour film
     * costs a couple of megabytes instead of its whole soundtrack in memory.
     */
    class Detector(val sampleRate: Int, val settings: Settings = Settings()) {

        private val frameSize = (sampleRate.toLong() * settings.frameMs / 1000L).toInt().coerceAtLeast(1)
        private val energies = ArrayList<Double>(8192)
        private val frame = ShortArray(frameSize)
        private var filled = 0
        private var frames = 0L

        /** Total audio measured so far. */
        val durationMs: Long get() = frames * settings.frameMs + filled * 1000L / sampleRate

        /** Feed mono 16-bit audio, in order. */
        fun push(samples: ShortArray) {
            var index = 0
            while (index < samples.size) {
                val take = minOf(samples.size - index, frameSize - filled)
                System.arraycopy(samples, index, frame, filled, take)
                filled += take
                index += take
                if (filled == frameSize) {
                    energies.add(rms(frame, frameSize))
                    frames++
                    filled = 0
                }
            }
        }

        /** Forget everything — used when a second pass must not double-count. */
        fun reset() {
            energies.clear()
            frames = 0
            filled = 0
        }

        /**
         * The speech in what was fed, in order, padded by a hair so a cue does
         * not start on the consonant.
         */
        fun finish(): List<Region> {
            // A partial final frame still counts: the last line of a film is as
            // real as the first, and an eighth of a second of it is invisible.
            if (filled > 0) {
                energies.add(rms(frame, filled))
                frames++
                filled = 0
            }
            if (energies.isEmpty()) return emptyList()
            val threshold = threshold()
            val minimum = (settings.minimumMs / settings.frameMs).toInt().coerceAtLeast(1)
            val maximum = (settings.maximumMs / settings.frameMs).toInt().coerceAtLeast(minimum)
            val gap = (settings.mergeGapMs / settings.frameMs).toInt().coerceAtLeast(0)

            val runs = ArrayList<IntArray>(64)
            var open = -1
            for (index in energies.indices) {
                if (energies[index] >= threshold) {
                    if (open < 0) open = index
                } else if (open >= 0) {
                    runs.add(intArrayOf(open, index - 1))
                    open = -1
                }
            }
            if (open >= 0) runs.add(intArrayOf(open, energies.size - 1))

            // A pause inside a sentence is not the end of a cue: "I told you"
            // / beat / "to leave" is one line, and two cues 300 ms apart read
            // as a stutter.
            val merged = ArrayList<IntArray>(runs.size)
            runs.forEach { run ->
                val last = merged.lastOrNull()
                if (last != null && run[0] - last[1] - 1 <= gap) {
                    last[1] = run[1]
                } else {
                    merged.add(intArrayOf(run[0], run[1]))
                }
            }

            val regions = ArrayList<Region>(merged.size)
            merged.forEach { span ->
                if (span[1] - span[0] + 1 < minimum) return@forEach
                split(span[0], span[1], maximum).forEach { piece -> asRegion(piece)?.let(regions::add) }
            }
            regions.sortBy { it.startMs }
            // Padding must never make two cues overlap: a subtitle file with
            // two lines on screen at once is a renderer's argument, not a
            // transcript. The later region loses the overlap, never the words.
            val spaced = ArrayList<Region>(regions.size)
            var reached = 0L
            regions.forEach { region ->
                val start = maxOf(region.startMs, reached)
                if (region.endMs - start >= 120L) {
                    spaced.add(Region(start, region.endMs))
                    reached = region.endMs
                }
            }
            return spaced
        }

        /**
         * Above the room, and below the voices.
         *
         * The floor alone is not enough: a film whose quietest tenth is already
         * loud — a concert, an action scene, a clip with no silence in it at all
         * — would put the threshold above its own dialogue and find nothing.
         * Half the loud tenth is the safety net, and the absolute floor keeps a
         * whisper of tape hiss from becoming a cue.
         */
        private fun threshold(): Double {
            val sorted = energies.sorted()
            val floor = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.lastIndex)]
            val loud = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.lastIndex)]
            return maxOf(settings.quietRms, minOf(floor * settings.floorFactor, loud * 0.5))
        }

        /** Cuts a long run at its quietest frames until every piece fits. */
        private fun split(start: Int, end: Int, maximum: Int): List<IntArray> {
            val out = ArrayList<IntArray>(2)
            val work = ArrayDeque<IntArray>()
            work.addLast(intArrayOf(start, end))
            while (work.isNotEmpty()) {
                val span = work.removeLast()
                val length = span[1] - span[0] + 1
                if (length <= maximum) {
                    out.add(span)
                    continue
                }
                // Never cut within a quarter of either edge: a piece too short
                // to hold a sentence is not a cue, it is a fragment.
                val lowest = span[0] + length / 4
                val highest = span[1] - length / 4
                var cut = (span[0] + span[1]) / 2
                var quietest = Double.MAX_VALUE
                for (index in lowest..highest) {
                    if (energies[index] < quietest) {
                        quietest = energies[index]
                        cut = index
                    }
                }
                work.addLast(intArrayOf(span[0], cut))
                work.addLast(intArrayOf(cut + 1, span[1]))
            }
            out.sortBy { it[0] }
            return out
        }

        private fun asRegion(span: IntArray): Region? {
            val total = durationMs
            val start = (span[0].toLong() * settings.frameMs - settings.padMs).coerceAtLeast(0L)
            val end = ((span[1] + 1).toLong() * settings.frameMs + settings.padMs).coerceAtMost(total)
            if (end - start < 120L) return null
            return Region(start, end)
        }

        private fun rms(samples: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (index in 0 until count) {
                val value = samples[index].toDouble()
                sum += value * value
            }
            return sqrt(sum / count)
        }
    }
}
