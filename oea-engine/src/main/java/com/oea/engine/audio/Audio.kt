package com.oea.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.oea.engine.EngineSettings
import com.oea.engine.core.OeaGuard
import com.oea.engine.graphics.Camera
import com.oea.engine.math.MathX
import com.oea.engine.math.Rng
import com.oea.engine.math.Vec3
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A loaded sound effect. */
class Sound internal constructor(val name: String) {
    @Volatile internal var poolId = 0
    @Volatile var loaded = false
        internal set
}

enum class Wave { SINE, SQUARE, SAW, TRIANGLE, NOISE }

/**
 * A procedural sound: an oscillator with a pitch sweep, an envelope, optional
 * noise, vibrato and a low-pass filter. [layers] are mixed in after
 * [layerDelay] seconds, so rich effects (gunshots, arpeggios) are one recipe.
 */
data class SfxRecipe(
    val wave: Wave = Wave.SINE,
    val duration: Float = 0.2f,
    val freqStart: Float = 440f,
    val freqEnd: Float = 440f,
    val attack: Float = 0.005f,
    /** Exponential decay speed (1/s); 0 = sustain until the end. */
    val decay: Float = 8f,
    val volume: Float = 0.7f,
    val noise: Float = 0f,
    /** One-pole low-pass amount 0 (closed) .. 1 (open). */
    val lowpass: Float = 1f,
    val vibratoHz: Float = 0f,
    val vibratoDepth: Float = 0f,
    val duty: Float = 0.5f,
    val layerDelay: Float = 0f,
    val layers: List<SfxRecipe> = emptyList(),
)

/**
 * Sound effects (SoundPool) and music (MediaPlayer). Effects can come from
 * assets or from [SfxRecipe]s synthesised once into the app cache, so games
 * have good audio without shipping any files.
 */
class Audio internal constructor(private val context: Context, private val settings: EngineSettings) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(24)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val byPoolId = ConcurrentHashMap<Int, Sound>()
    private val worker = OeaGuard.pool("audio", 1, "oea-audio")
    private var music: MediaPlayer? = null
    private var musicName: String? = null
    @Volatile private var released = false
    private val tmp = Vec3()
    private val right = Vec3()

    var musicVolume = 0.45f
        set(v) { field = v.coerceIn(0f, 1f); music?.setVolume(field * settings.volume, field * settings.volume) }

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            byPoolId[sampleId]?.loaded = status == 0
        }
    }

    /** Synthesises [recipe] (cached on disk by content) and loads it. */
    fun load(name: String, recipe: SfxRecipe): Sound {
        val sound = Sound(name)
        worker.execute {
            try {
                val file = File(context.cacheDir, "oea-sfx/${name}-${recipe.hashCode().toUInt().toString(16)}.wav")
                if (!file.exists()) Synth.writeWav(file, Synth.render(recipe))
                if (released) return@execute
                val id = pool.load(file.absolutePath, 1)
                sound.poolId = id
                byPoolId[id] = sound
            } catch (t: Throwable) {
                Log.w(TAG, "Could not create sound $name", t)
            }
        }
        return sound
    }

    fun loadAsset(path: String): Sound {
        val sound = Sound(path)
        worker.execute {
            runCatching {
                context.assets.openFd(path).use { fd ->
                    val id = pool.load(fd, 1)
                    sound.poolId = id
                    byPoolId[id] = sound
                }
            }.onFailure { Log.w(TAG, "Missing sound asset $path", it) }
        }
        return sound
    }

    /** Plays [sound]; [pan] -1 (left) .. 1 (right); returns a stream id (0 if not played). */
    fun play(sound: Sound, volume: Float = 1f, pan: Float = 0f, pitch: Float = 1f, loop: Boolean = false, priority: Int = 1): Int {
        if (!settings.sound || !sound.loaded || released) return 0
        val v = (volume * settings.volume).coerceIn(0f, 1f)
        val p = MathX.clamp(pan, -1f, 1f)
        val angle = (p + 1f) * (PI.toFloat() / 4f)
        return pool.play(sound.poolId, v * cos(angle) * 1.41f, v * sin(angle) * 1.41f, priority, if (loop) -1 else 0, pitch.coerceIn(0.5f, 2f))
    }

    /** Positional playback relative to [listener]: distance attenuation and stereo panning. */
    fun play3D(sound: Sound, x: Float, y: Float, z: Float, listener: Camera, maxDistance: Float = 45f, volume: Float = 1f, pitch: Float = 1f): Int {
        tmp.set(x, y, z).sub(listener.position)
        val d = tmp.len()
        if (d > maxDistance) return 0
        val att = (1f - d / maxDistance).let { it * it }
        listener.right(right)
        val pan = if (d > 1e-3f) tmp.dot(right) / d else 0f
        return play(sound, volume * att, pan, pitch)
    }

    fun stop(streamId: Int) { if (streamId != 0) pool.stop(streamId) }
    fun setRate(streamId: Int, rate: Float) { if (streamId != 0) pool.setRate(streamId, rate.coerceIn(0.5f, 2f)) }
    fun setVolume(streamId: Int, volume: Float) { if (streamId != 0) { val v = volume * settings.volume; pool.setVolume(streamId, v, v) } }

    /** Starts looping [track] (synthesised once and cached). Replaces any playing music. */
    fun playMusic(name: String, track: MusicTrack) {
        if (!settings.music || released) return
        if (musicName == name && music != null) { runCatching { music?.start() }; return }
        stopMusic()
        musicName = name
        worker.execute {
            try {
                val file = File(context.cacheDir, "oea-music/${name}-${track.hashCode().toUInt().toString(16)}.wav")
                if (!file.exists()) Synth.writeWav(file, track.render())
                if (released || musicName != name) return@execute
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                mp.setDataSource(file.absolutePath)
                mp.isLooping = true
                mp.prepare()
                mp.setVolume(musicVolume * settings.volume, musicVolume * settings.volume)
                synchronized(this) {
                    if (released || musicName != name) { mp.release(); return@execute }
                    music = mp
                    mp.start()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Music unavailable", t)
            }
        }
    }

    fun stopMusic() {
        synchronized(this) {
            musicName = null
            runCatching { music?.stop() }
            runCatching { music?.release() }
            music = null
        }
    }

    internal fun pause() {
        pool.autoPause()
        synchronized(this) { runCatching { if (music?.isPlaying == true) music?.pause() } }
    }

    internal fun resume() {
        pool.autoResume()
        synchronized(this) { runCatching { if (settings.music) music?.start() } }
    }

    internal fun release() {
        released = true
        stopMusic()
        runCatching { pool.release() }
        worker.shutdown()
    }

    private companion object { const val TAG = "OeaAudio" }
}

/** Offline synthesiser behind [SfxRecipe] and [MusicTrack]. */
object Synth {
    const val RATE = 44100

    fun render(r: SfxRecipe): FloatArray {
        val total = totalDuration(r)
        val out = FloatArray((total * RATE).toInt().coerceAtLeast(1))
        mix(r, out, 0f, Rng(r.hashCode().toLong()))
        normalize(out, 0.95f)
        return out
    }

    private fun totalDuration(r: SfxRecipe): Float = maxOf(r.duration, r.layers.maxOfOrNull { r.layerDelay + totalDuration(it) } ?: 0f)

    private fun mix(r: SfxRecipe, out: FloatArray, offset: Float, rng: Rng) {
        val start = (offset * RATE).toInt()
        val n = (r.duration * RATE).toInt()
        var phase = 0.0
        var lp = 0f
        val ratio = if (r.freqStart > 0f) r.freqEnd / r.freqStart else 1f
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i / RATE.toFloat()
            val k = t / r.duration
            var f = r.freqStart * ratio.pow(k)
            if (r.vibratoHz > 0f) f *= 1f + r.vibratoDepth * sin(2f * PI.toFloat() * r.vibratoHz * t)
            phase += f / RATE
            val p = (phase - floor(phase)).toFloat()
            var s = when (r.wave) {
                Wave.SINE -> sin(2.0 * PI * p).toFloat()
                Wave.SQUARE -> if (p < r.duty) 1f else -1f
                Wave.SAW -> 2f * p - 1f
                Wave.TRIANGLE -> 4f * kotlin.math.abs(p - 0.5f) - 1f
                Wave.NOISE -> rng.nextFloat() * 2f - 1f
            }
            if (r.noise > 0f) s = s * (1f - r.noise) + (rng.nextFloat() * 2f - 1f) * r.noise
            lp += (s - lp) * r.lowpass.coerceIn(0.01f, 1f)
            val env = (if (t < r.attack) t / r.attack else 1f) *
                (if (r.decay > 0f) exp(-r.decay * (t - r.attack).coerceAtLeast(0f)) else 1f) *
                (1f - MathX.smoothstep(0.92f, 1f, k))
            out[idx] += lp * env * r.volume
        }
        for (layer in r.layers) mix(layer, out, offset + r.layerDelay, rng)
    }

    fun normalize(buf: FloatArray, peak: Float) {
        var m = 0f
        for (v in buf) m = maxOf(m, kotlin.math.abs(v))
        if (m > peak) { val k = peak / m; for (i in buf.indices) buf[i] *= k }
    }

    /** Writes mono 16-bit PCM WAV. */
    fun writeWav(file: File, samples: FloatArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { out ->
            val dataLen = samples.size * 2
            fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.writeBytes("RIFF"); le32(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(RATE); le32(RATE * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(dataLen)
            for (s in samples) le16((MathX.clamp(s, -1f, 1f) * 32767f).toInt())
        }
        tmp.renameTo(file)
    }
}

/**
 * A procedurally composed music loop: drums, bass and an arpeggiated lead
 * over a chord progression. Rendered once to a cached WAV and looped.
 */
data class MusicTrack(
    val bpm: Int = 120,
    val bars: Int = 8,
    /** Root note as MIDI number (57 = A3). */
    val root: Int = 57,
    val minor: Boolean = true,
    /** Chord degrees per bar (0-based scale steps). */
    val progression: List<Int> = listOf(0, 5, 3, 4),
    val drums: Boolean = true,
    val bass: Boolean = true,
    val lead: Boolean = true,
    val pad: Boolean = false,
    val energy: Float = 0.8f,
    val seed: Long = 1,
) {
    fun render(): FloatArray {
        val beat = 60f / bpm
        val barLen = beat * 4f
        val total = (barLen * bars * Synth.RATE).toInt()
        val out = FloatArray(total)
        val scale = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val rng = Rng(seed)
        fun midiHz(n: Int) = 440f * 2f.pow((n - 69) / 12f)
        fun note(degree: Int, octave: Int = 0): Int {
            val d = Math.floorMod(degree, 7)
            val o = Math.floorDiv(degree, 7)
            return root + scale[d] + 12 * (o + octave)
        }
        fun add(start: Float, dur: Float, gen: (Float, Float) -> Float) {
            val s = (start * Synth.RATE).toInt()
            val n = (dur * Synth.RATE).toInt()
            for (i in 0 until n) {
                val idx = (s + i) % total
                out[idx] += gen(i / Synth.RATE.toFloat(), dur)
            }
        }
        for (bar in 0 until bars) {
            val chord = progression[bar % progression.size]
            val t0 = bar * barLen
            if (drums) for (b in 0 until 4) {
                val bt = t0 + b * beat
                add(bt, 0.32f) { t, _ -> sin(2f * PI.toFloat() * (48f + 90f * exp(-t * 30f)) * t) * exp(-t * 9f) * 0.9f * energy }
                if (b % 2 == 1) add(bt, 0.2f) { t, _ -> (rng.nextFloat() * 2f - 1f) * exp(-t * 22f) * 0.35f * energy }
                for (h in 0 until 2) add(bt + h * beat / 2f, 0.05f) { t, _ -> (rng.nextFloat() * 2f - 1f) * exp(-t * 90f) * 0.12f * energy }
            }
            if (bass) for (e in 0 until 8) {
                val bt = t0 + e * beat / 2f
                val f = midiHz(note(chord, -2) + if (e % 4 == 3) 7 else 0)
                add(bt, beat / 2f * 0.9f) { t, d ->
                    val ph = (f * t) % 1f
                    (2f * ph - 1f) * 0.22f * exp(-t * 3f) * (1f - MathX.smoothstep(0.85f, 1f, t / d))
                }
            }
            if (lead) {
                val steps = 16
                for (sIdx in 0 until steps) {
                    if (rng.chance(0.18f)) continue
                    val bt = t0 + sIdx * beat / 4f
                    val deg = chord + intArrayOf(0, 2, 4, 7, 4, 2)[sIdx % 6]
                    val f = midiHz(note(deg, 1))
                    add(bt, beat / 4f * 0.95f) { t, d ->
                        val tri = 4f * kotlin.math.abs((f * t) % 1f - 0.5f) - 1f
                        tri * 0.1f * exp(-t * 6f) * (1f - MathX.smoothstep(0.8f, 1f, t / d))
                    }
                }
            }
            if (pad) for (k in intArrayOf(0, 2, 4)) {
                val f = midiHz(note(chord + k))
                add(t0, barLen) { t, d ->
                    val env = MathX.smoothstep(0f, 0.6f, t) * (1f - MathX.smoothstep(0.75f, 1f, t / d))
                    (sin(2f * PI.toFloat() * f * t) + 0.3f * sin(2f * PI.toFloat() * f * 2.003f * t)) * 0.06f * env
                }
            }
        }
        // Gentle soft clip and normalise.
        for (i in out.indices) { val x = out[i]; out[i] = x / (1f + kotlin.math.abs(x) * 0.6f) }
        Synth.normalize(out, 0.9f)
        @Suppress("UNUSED_VARIABLE") val unused = sqrt(1f)
        return out
    }
}

/** Ready-made effect recipes. */
object Sfx {
    val CLICK = SfxRecipe(Wave.SQUARE, 0.04f, 1800f, 1200f, decay = 60f, volume = 0.35f, lowpass = 0.5f)
    val COIN = SfxRecipe(Wave.SQUARE, 0.07f, 988f, 988f, decay = 18f, volume = 0.45f, duty = 0.25f,
        layerDelay = 0.06f, layers = listOf(SfxRecipe(Wave.SQUARE, 0.18f, 1319f, 1319f, decay = 14f, volume = 0.45f, duty = 0.25f)))
    val JUMP = SfxRecipe(Wave.SINE, 0.22f, 300f, 760f, decay = 7f, volume = 0.55f)
    val LAND = SfxRecipe(Wave.NOISE, 0.12f, decay = 28f, volume = 0.5f, lowpass = 0.08f)
    val WHOOSH = SfxRecipe(Wave.NOISE, 0.3f, decay = 7f, volume = 0.4f, lowpass = 0.25f, attack = 0.04f)
    val CRASH = SfxRecipe(Wave.NOISE, 0.6f, decay = 5f, volume = 0.9f, lowpass = 0.3f,
        layers = listOf(SfxRecipe(Wave.SINE, 0.5f, 120f, 40f, decay = 6f, volume = 0.9f)))
    val POWERUP = SfxRecipe(Wave.SQUARE, 0.08f, 523f, 523f, decay = 10f, volume = 0.4f, duty = 0.3f, layerDelay = 0.07f,
        layers = listOf(SfxRecipe(Wave.SQUARE, 0.08f, 659f, 659f, decay = 10f, volume = 0.4f, duty = 0.3f, layerDelay = 0.07f,
            layers = listOf(SfxRecipe(Wave.SQUARE, 0.25f, 784f, 1046f, decay = 6f, volume = 0.4f, duty = 0.3f)))))
    val SHOT_PISTOL = SfxRecipe(Wave.NOISE, 0.22f, decay = 22f, volume = 0.9f, lowpass = 0.55f,
        layers = listOf(SfxRecipe(Wave.SINE, 0.15f, 180f, 60f, decay = 18f, volume = 0.8f)))
    val SHOT_RIFLE = SfxRecipe(Wave.NOISE, 0.28f, decay = 16f, volume = 1f, lowpass = 0.7f,
        layers = listOf(SfxRecipe(Wave.SAW, 0.12f, 140f, 50f, decay = 20f, volume = 0.7f)))
    val SHOT_SHOTGUN = SfxRecipe(Wave.NOISE, 0.45f, decay = 9f, volume = 1f, lowpass = 0.45f,
        layers = listOf(SfxRecipe(Wave.SINE, 0.3f, 110f, 35f, decay = 10f, volume = 1f)))
    val SHOT_SNIPER = SfxRecipe(Wave.NOISE, 0.9f, decay = 5f, volume = 1f, lowpass = 0.6f,
        layers = listOf(SfxRecipe(Wave.SINE, 0.5f, 90f, 30f, decay = 7f, volume = 1f)))
    val HIT = SfxRecipe(Wave.SQUARE, 0.06f, 1500f, 900f, decay = 40f, volume = 0.4f)
    val HEADSHOT = SfxRecipe(Wave.SINE, 0.35f, 1760f, 1760f, decay = 9f, volume = 0.5f, layers = listOf(SfxRecipe(Wave.SINE, 0.35f, 2637f, 2637f, decay = 12f, volume = 0.25f)))
    val RELOAD = SfxRecipe(Wave.NOISE, 0.05f, decay = 60f, volume = 0.5f, lowpass = 0.9f, layerDelay = 0.22f,
        layers = listOf(SfxRecipe(Wave.NOISE, 0.07f, decay = 50f, volume = 0.6f, lowpass = 0.7f)))
    val FOOTSTEP = SfxRecipe(Wave.NOISE, 0.08f, decay = 40f, volume = 0.25f, lowpass = 0.12f)
    val EXPLOSION = SfxRecipe(Wave.NOISE, 1.4f, decay = 3f, volume = 1f, lowpass = 0.18f,
        layers = listOf(SfxRecipe(Wave.SINE, 1f, 70f, 25f, decay = 3.5f, volume = 1f)))
    val PIECE_MOVE = SfxRecipe(Wave.SINE, 0.09f, 210f, 160f, decay = 40f, volume = 0.7f, noise = 0.25f, lowpass = 0.35f)
    val PIECE_CAPTURE = SfxRecipe(Wave.SINE, 0.14f, 260f, 140f, decay = 28f, volume = 0.8f, noise = 0.35f, lowpass = 0.4f,
        layerDelay = 0.03f, layers = listOf(SfxRecipe(Wave.SINE, 0.1f, 420f, 300f, decay = 35f, volume = 0.4f)))
    val CHECK = SfxRecipe(Wave.TRIANGLE, 0.12f, 880f, 880f, decay = 12f, volume = 0.5f, layerDelay = 0.12f,
        layers = listOf(SfxRecipe(Wave.TRIANGLE, 0.2f, 1175f, 1175f, decay = 10f, volume = 0.5f)))
    val COUNTDOWN = SfxRecipe(Wave.SINE, 0.18f, 660f, 660f, decay = 10f, volume = 0.6f)
    val GO = SfxRecipe(Wave.SINE, 0.5f, 990f, 990f, decay = 4f, volume = 0.65f, layers = listOf(SfxRecipe(Wave.SINE, 0.5f, 1485f, 1485f, decay = 5f, volume = 0.25f)))
    val WIN = SfxRecipe(Wave.TRIANGLE, 0.12f, 523f, 523f, decay = 6f, volume = 0.5f, layerDelay = 0.12f,
        layers = listOf(SfxRecipe(Wave.TRIANGLE, 0.12f, 659f, 659f, decay = 6f, volume = 0.5f, layerDelay = 0.12f,
            layers = listOf(SfxRecipe(Wave.TRIANGLE, 0.5f, 784f, 784f, decay = 3f, volume = 0.5f, layerDelay = 0.0f,
                layers = listOf(SfxRecipe(Wave.TRIANGLE, 0.5f, 1046f, 1046f, decay = 3f, volume = 0.35f)))))))
    val LOSE = SfxRecipe(Wave.TRIANGLE, 0.25f, 440f, 415f, decay = 4f, volume = 0.5f, layerDelay = 0.22f,
        layers = listOf(SfxRecipe(Wave.TRIANGLE, 0.6f, 349f, 330f, decay = 2.5f, volume = 0.5f)))
    val POP = SfxRecipe(Wave.SINE, 0.08f, 500f, 900f, decay = 25f, volume = 0.4f)
}
