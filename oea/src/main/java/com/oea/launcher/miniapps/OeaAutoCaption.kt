package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Writes subtitles for a clip that has none, from the sound of the clip.
 *
 * The division of labour is deliberate. [OeaSpeechRegions] decides *when*
 * somebody speaks, by measuring the audio; [OeaCaptionSession] asks the phone's
 * own recognizer *what* was said, one measured stretch at a time; [OeaCaptions]
 * makes the answer presentable. Nothing about the timing depends on the
 * recognizer — which is what makes generated cues line up with the picture
 * instead of drifting behind it.
 *
 * Two honest limits are built into the shape of this class rather than hidden
 * in a comment. Handing a recognizer audio instead of a microphone needs
 * Android 13 (`EXTRA_AUDIO_SOURCE`), so on anything older this refuses and says
 * why. And the words are only as good as the recognizer installed on the phone:
 * with an offline language pack that is a decent transcript, with an online one
 * it is a better transcript and the audio leaves the device — which the sheet
 * says out loud before it starts.
 */
class OeaAutoCaption(
    private val activity: Activity,
    private val clip: Uri,
    private val languageTag: String
) {

    /** How far along the run is, for a sheet that must not look frozen. */
    data class Progress(
        val index: Int,
        val total: Int,
        val heard: Int,
        val line: String,
        val fraction: Float
    )

    /** What came of it. */
    sealed class Outcome {
        class Done(val cues: List<OeaSubtitles.Cue>, val onDevice: Boolean, val heard: Int) : Outcome()
        class Failed(val reason: String) : Outcome()
        object Cancelled : Outcome()
    }

    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val audio = AtomicReference<File?>(null)

    /** Which errors the recognizer reported, so a refusal can be named. */
    private val refusals = ConcurrentLinkedQueue<Int>()
    private var worker: Thread? = null
    private var session: OeaCaptionSession? = null
    private var progressSink: ((Progress) -> Unit)? = null
    private var doneSink: ((Outcome) -> Unit)? = null

    /** True while a run is live, so the player can offer "Stop" instead of "Start". */
    val running: Boolean get() = worker?.isAlive == true && !stopped.get()

    /**
     * Starts the run on its own thread.
     *
     * [fromMs] and [toMs] bound what is transcribed, so "from here, for twenty
     * minutes" is a request this can answer instead of an all-or-nothing film.
     */
    fun start(
        fromMs: Long = 0L,
        toMs: Long = Long.MAX_VALUE,
        onProgress: (Progress) -> Unit,
        onDone: (Outcome) -> Unit
    ) {
        if (running) return
        stopped.set(false)
        progressSink = onProgress
        doneSink = onDone
        val thread = Thread({ run(fromMs, toMs) }, "OeaVipCaption")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    /**
     * Stops the run and throws away its audio.
     *
     * Safe to call from any thread and any number of times: the worker checks
     * the flag between regions, and the live session is cancelled with it.
     */
    fun stop() {
        stopped.set(true)
        main.post { runCatching { session?.cancel() } }
    }

    // -------------------------------------------------------------------- worker

    private fun run(fromMs: Long, toMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            finish(Outcome.Failed("Writing subtitles from the sound needs Android 13 or newer."))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            finish(Outcome.Failed("This phone has no speech recognizer installed."))
            return
        }
        val temp = File(activity.cacheDir, "vip-caption-${System.nanoTime()}.pcm")
        audio.set(temp)
        try {
            report(Progress(0, 0, 0, "Listening to the sound", 0.02f))
            val detector = OeaSpeechRegions.Detector(OeaAudio.CAPTION_RATE)
            val bytes = decode { pcm -> detector.push(pcm) }
            if (stopped.get()) {
                finish(Outcome.Cancelled)
                return
            }
            if (bytes <= 0L) {
                finish(Outcome.Failed("This clip has no sound to read."))
                return
            }
            val clipMs = bytes * 1000L / OeaAudio.CAPTION_BYTES_PER_SECOND
            val regions = detector.finish()
                .filter { it.overlaps(fromMs, minOf(toMs, clipMs)) }
                .take(MAX_REGIONS)
            if (regions.isEmpty()) {
                finish(Outcome.Failed("No speech was found in that part of the clip."))
                return
            }
            val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            val told = transcribe(regions, temp, onDevice)
            if (stopped.get()) {
                finish(Outcome.Cancelled)
                return
            }
            val cues = OeaCaptions.cues(regions, told, clipMs, minOf(toMs, clipMs))
            val heard = told.count { !it.isNullOrBlank() }
            if (cues.isEmpty()) {
                finish(
                    Outcome.Failed(
                        when {
                            refusals.contains(ERROR_NO_PERMISSION) ->
                                "The recognizer refused to run: it wants the microphone permission even though VIP hands it the clip's own audio. Grant it in OEA's app permissions and try again."
                            onDevice ->
                                "The phone's offline recognizer found no words. Its language pack may not be installed for this language."
                            else -> "The recognizer heard nothing it could transcribe."
                        }
                    )
                )
                return
            }
            finish(Outcome.Done(cues, onDevice, heard))
        } catch (error: Throwable) {
            finish(Outcome.Failed("The soundtrack could not be read: ${error.message ?: "unknown reason"}"))
        } finally {
            runCatching { temp.delete() }
            audio.set(null)
        }
    }

    /**
     * One recognizer session per measured region, in order.
     *
     * Sessions are created and destroyed for each region rather than reused:
     * a recognizer that is finishing one utterance while being handed the next
     * answers ERROR_RECOGNIZER_BUSY, and a busy error in the middle of a film is
     * a cue that silently disappears.
     */
    private fun transcribe(
        regions: List<OeaSpeechRegions.Region>,
        audioFile: File,
        onDevice: Boolean
    ): List<String?> {
        val out = ArrayList<String?>(regions.size)
        RandomAccessFile(audioFile, "r").use { reader ->
            regions.forEachIndexed { index, region ->
                if (stopped.get()) return@forEachIndexed
                val from = region.startMs * OeaAudio.CAPTION_BYTES_PER_SECOND / 1000L
                val to = region.endMs * OeaAudio.CAPTION_BYTES_PER_SECOND / 1000L
                val available = (reader.length() - from).coerceAtLeast(0L)
                val length = minOf(to - from, available, MAX_BYTES_PER_REGION).toInt()
                if (length < 2) {
                    out.add(null)
                    return@forEachIndexed
                }
                val pcm = ByteArray(length)
                val read = runCatching {
                    reader.seek(from)
                    reader.readFully(pcm)
                    true
                }.getOrDefault(false)
                if (!read) {
                    out.add(null)
                    return@forEachIndexed
                }
                val heard = listen(pcm, region.lengthMs + SESSION_EXTRA_MS, onDevice)
                out.add(heard)
                report(
                    Progress(
                        index + 1,
                        regions.size,
                        out.count { !it.isNullOrBlank() },
                        heard?.take(80) ?: "…",
                        (index + 1).toFloat() / regions.size
                    )
                )
            }
        }
        return out
    }

    /** Runs one session and blocks the worker until it answers or gives up. */
    private fun listen(pcm: ByteArray, budgetMs: Long, onDevice: Boolean): String? {
        val latch = CountDownLatch(1)
        val heard = AtomicReference<String?>(null)
        val started = AtomicBoolean(false)
        main.post {
            val live = OeaCaptionSession(
                activity = activity,
                pcm = pcm,
                languageTag = languageTag,
                onDevice = onDevice,
                budgetMs = budgetMs
            ) { text, error ->
                heard.set(text)
                if (error != 0) refusals.add(error)
                session = null
                latch.countDown()
            }
            if (stopped.get()) {
                latch.countDown()
                return@post
            }
            session = live
            started.set(live.start())
            if (!started.get()) {
                session = null
                latch.countDown()
            }
        }
        val waited = runCatching { latch.await(budgetMs + SESSION_EXTRA_MS * 2, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!waited) main.post { runCatching { session?.cancel() } }
        return heard.get()
    }

    // ------------------------------------------------------------------- decoding

    /**
     * Decodes the clip's first audio track to 16 kHz mono 16-bit PCM, writing it
     * to the run's temporary file and handing each chunk to [sink].
     *
     * Returns the number of bytes written, which is the length of the audio the
     * rest of the run measures against — not the length of the container, which
     * can be a few frames longer than its sound.
     */
    private fun decode(sink: (ShortArray) -> Unit): Long {
        val target = audio.get() ?: return 0L
        var written = 0L
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(activity, clip, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = runCatching { candidate.getString(MediaFormat.KEY_MIME) }.getOrNull()
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            val chosen = format ?: return 0L
            if (trackIndex < 0) return 0L
            extractor.selectTrack(trackIndex)
            val mime = chosen.getString(MediaFormat.KEY_MIME) ?: return 0L
            // A local val for the decoder: a captured `var` cannot be smart cast,
            // and every call below would need a null check that cannot fail.
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(chosen, null, null, 0)
            decoder.start()

            var resampler = OeaAudio.Resampler(rateOf(chosen), OeaAudio.CAPTION_RATE)
            var channels = channelsOf(chosen)
            var floating = isFloating(chosen)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + MAX_DECODE_MS
            target.outputStream().use { file ->
                while (!outputDone && !stopped.get() && System.currentTimeMillis() < deadline) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inputIndex)
                            val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = decoder.dequeueOutputBuffer(info, INPUT_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = decoder.outputFormat
                            resampler = OeaAudio.Resampler(rateOf(out), OeaAudio.CAPTION_RATE)
                            channels = channelsOf(out)
                            floating = isFloating(out)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val pcm = if (floating) floatsOf(buffer, info.size) else shortsOf(buffer, info.size)
                                val mono = OeaAudio.mono(pcm, channels)
                                val caption = resampler.push(mono)
                                if (caption.isNotEmpty()) {
                                    sink(caption)
                                    val bytes = OeaAudio.bytes(caption)
                                    file.write(bytes)
                                    written += bytes.size
                                }
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            return written
        } catch (error: Throwable) {
            return written
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun rateOf(format: MediaFormat): Int =
        runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(OeaAudio.CAPTION_RATE)
            .coerceAtLeast(8_000)

    private fun channelsOf(format: MediaFormat): Int =
        runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1).coerceAtLeast(1)

    private fun isFloating(format: MediaFormat): Boolean = runCatching {
        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    }.getOrDefault(false)

    private fun shortsOf(buffer: java.nio.ByteBuffer, size: Int): ShortArray {
        val out = ShortArray(size / 2)
        runCatching {
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(out)
        }
        return out
    }

    private fun floatsOf(buffer: java.nio.ByteBuffer, size: Int): ShortArray {
        val floats = FloatArray(size / 4)
        runCatching { buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats) }
        return ShortArray(floats.size) { (floats[it] * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort() }
    }

    // ------------------------------------------------------------------- plumbing

    private fun report(progress: Progress) {
        val sink = progressSink ?: return
        main.post { runCatching { sink(progress) } }
    }

    private fun finish(outcome: Outcome) {
        val sink = doneSink ?: return
        main.post {
            session = null
            runCatching { sink(outcome) }
        }
    }

    companion object {
        /** A cue per region, and a region is at most eight seconds of speech. */
        private const val MAX_REGIONS = 6_000

        /** Nothing in a measured region can be longer than this, but be safe. */
        private const val MAX_BYTES_PER_REGION = OeaAudio.CAPTION_BYTES_PER_SECOND.toLong() * 30

        /** How long a session may take beyond the length of the audio it was given. */
        private const val SESSION_EXTRA_MS = 12_000L

        /** A film longer than this is not a transcription job, it is a night. */
        private const val MAX_DECODE_MS = 45 * 60 * 1000L

        private const val INPUT_TIMEOUT_US = 10_000L

        /** SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS. */
        private const val ERROR_NO_PERMISSION = 9
    }
}

/**
 * One recognizer session, over one stretch of audio, on the main thread.
 *
 * The audio is written into a pipe rather than a file so nothing has to be
 * flushed and reopened between regions; closing the write end is the recognizer's
 * signal that the utterance is over, which is the only "end of input" this API
 * offers.
 */
private class OeaCaptionSession(
    private val activity: Activity,
    private val pcm: ByteArray,
    private val languageTag: String,
    private val onDevice: Boolean,
    private val budgetMs: Long,
    private val completed: (String?, Int) -> Unit
) : RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var engine: SpeechRecognizer? = null
    private var read: ParcelFileDescriptor? = null
    private var timeout: Runnable? = null

    /** Returns false when the phone cannot start a session at all. */
    fun start(): Boolean {
        val recognizer = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                SpeechRecognizer.createSpeechRecognizer(activity)
            }
        }.getOrNull() ?: return false
        engine = recognizer
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        if (pipe == null) {
            runCatching { recognizer.destroy() }
            engine = null
            return false
        }
        val input = pipe[0]
        val output = pipe[1]
        read = input
        recognizer.setRecognitionListener(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            if (languageTag.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // Android 13: this is what tells a recognizer to read our audio
            // instead of opening the microphone.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, OeaAudio.CAPTION_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val started = runCatching { recognizer.startListening(intent) }.isSuccess
        if (!started) {
            finish(null)
            return false
        }
        val feeder = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    stream.write(pcm)
                    stream.flush()
                }
            }
        }, "OeaVipCaptionAudio")
        feeder.isDaemon = true
        feeder.start()
        val guard = Runnable { finish(null) }
        timeout = guard
        main.postDelayed(guard, budgetMs)
        return true
    }

    /** Called from the worker when the run is stopped, or when it is late. */
    fun cancel() {
        runCatching { engine?.cancel() }
        finish(null)
    }

    private fun finish(text: String?, error: Int = 0) {
        if (!closed.compareAndSet(false, true)) return
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        runCatching { engine?.destroy() }
        engine = null
        runCatching { read?.close() }
        read = null
        completed(text, error)
    }

    private fun best(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    override fun onResults(results: Bundle?) = finish(best(results))

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onError(error: Int) = finish(null, error)

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
