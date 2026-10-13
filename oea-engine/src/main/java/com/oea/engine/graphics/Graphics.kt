package com.oea.engine.graphics

import android.content.Context
import android.graphics.Typeface
import android.opengl.GLES30
import android.util.Log
import com.oea.engine.EngineSettings
import com.oea.engine.OeaEngine
import com.oea.engine.perf.PerformanceManager
import com.oea.engine.perf.QualitySettings
import com.oea.engine.ui.Hud
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One rendered frame. A frame is any number of world passes (3D or 2D, each
 * into a [Viewport], for split screen) followed by UI passes. World passes go
 * through dynamic resolution and post-processing; UI is drawn at native
 * resolution on top, so text stays sharp.
 */
interface Frame {
    /** Surface size in pixels. */
    val width: Int
    val height: Int
    /** Pixels per density-independent pixel. */
    val density: Float
    /** Interpolation factor between the last two fixed updates. */
    val alpha: Float
    /** Seconds since the previous frame. */
    val dt: Float
    /** Background colour when no sky is drawn and for pure-UI frames. */
    fun clear(color: Int)
    fun beginWorld(camera: Camera, viewport: Viewport = Viewport.FULL, environment: Environment? = null): Renderer3D
    fun endWorld()
    fun beginWorld2D(camera: Camera2D, viewport: Viewport = Viewport.FULL): SpriteBatch
    fun endWorld2D()
    fun beginUi(): Hud
    fun endUi()
}

inline fun Frame.world(camera: Camera, viewport: Viewport = Viewport.FULL, environment: Environment? = null, block: (Renderer3D) -> Unit) {
    val r = beginWorld(camera, viewport, environment)
    try { block(r) } finally { endWorld() }
}

inline fun Frame.world2D(camera: Camera2D, viewport: Viewport = Viewport.FULL, block: (SpriteBatch) -> Unit) {
    val b = beginWorld2D(camera, viewport)
    try { block(b) } finally { endWorld2D() }
}

inline fun Frame.ui(block: (Hud) -> Unit) {
    val h = beginUi()
    try { block(h) } finally { endUi() }
}

/**
 * The engine's graphics device: owns the renderer, sprite batch, fonts,
 * post-processing and the per-frame pass sequencing. Games reach it through
 * `ctx.graphics` and draw through [Frame].
 */
class Graphics internal constructor(context: Context, private val perf: PerformanceManager, private val settings: EngineSettings) : Frame {
    val sprites = SpriteBatch()
    val renderer = Renderer3D(this)
    /** Default lighting for world passes that don't pass their own. */
    val environment = Environment()
    val font: BitmapFont by lazy { BitmapFont(Typeface.create("sans-serif-medium", Typeface.NORMAL), 64) }
    val fontBold: BitmapFont by lazy { BitmapFont(Typeface.create("sans-serif-condensed", Typeface.BOLD), 72) }
    internal lateinit var hud: Hud

    override var width = 1
        private set
    override var height = 1
        private set
    override val density: Float = context.resources.displayMetrics.density
    override var alpha = 0f
        private set
    override var dt = 0f
        private set

    /** Safe area (display cutout / rounded corners) in pixels: left, top, right, bottom. */
    val safeInsets = IntArray(4)

    val quality: QualitySettings get() = perf.quality
    /** Current dynamic resolution scale. */
    val renderScale: Float get() = if (postAvailable) scaleThisFrame else 1f
    fun dp(v: Float) = v * density

    private val post = PostProcess()
    private var postAvailable = true
    private var scaleThisFrame = 1f
    private var sceneW = 1
    private var sceneH = 1
    private var anyWorld = false
    private var composited = false
    private var inWorld = false
    private var inUi = false
    private var clearColor = 0xFF000000.toInt()
    private var passViewport = IntArray(4)
    private var passToScreen = false
    private var frameTime = 0f

    internal fun perfThermalLimited() = perf.thermalLimited

    internal fun onContextCreated() {
        Gpu.newContext()
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
        val vendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty()
        val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val tmp = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, tmp, 0)
        val maxTex = tmp[0]
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, tmp, 0)
        OeaEngine.updateGpuInfo(renderer, vendor, version, maxTex, tmp[0], ext)
        if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
            val f = FloatArray(1)
            GLES30.glGetFloatv(Texture.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, f, 0)
            Texture.maxAnisotropy = min(f[0], perf.quality.anisotropy)
        } else {
            Texture.maxAnisotropy = 1f
        }
        postAvailable = true
        Log.i(TAG, "GPU: $renderer ($version), max texture $maxTex")
    }

    internal fun onSurfaceChanged(w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        perf.refreshQuality(width, height)
        GLES30.glViewport(0, 0, width, height)
    }

    internal fun beginFrame(alpha: Float, dt: Float) {
        this.alpha = alpha
        this.dt = dt
        frameTime += dt
        anyWorld = false
        composited = false
        renderer.resetStats()
        scaleThisFrame = perf.renderScale
        val maxTex = OeaEngine.device.maxTextureSize
        sceneW = min((width * scaleThisFrame).roundToInt(), maxTex).coerceAtLeast(1)
        sceneH = min((height * scaleThisFrame).roundToInt(), maxTex).coerceAtLeast(1)
    }

    internal fun endFrame(showStats: Boolean) {
        if (anyWorld && !composited) composite()
        if (!anyWorld && !composited) clearScreen()
        if (showStats) drawStats()
        composited = false
    }

    override fun clear(color: Int) { clearColor = color }

    // ---------------------------------------------------------------- world passes

    override fun beginWorld(camera: Camera, viewport: Viewport, environment: Environment?): Renderer3D {
        check(!inWorld && !inUi) { "Nested passes: end the current pass first" }
        inWorld = true
        val env = environment ?: this.environment
        startWorldTarget(viewport, env.clearColor, env.drawSky)
        camera.update(passViewport[2].toFloat() / max(1, passViewport[3]), viewport.rotate180)
        renderer.begin(camera, env)
        return renderer
    }

    override fun endWorld() {
        if (!inWorld) return
        renderer.flush()
        inWorld = false
    }

    override fun beginWorld2D(camera: Camera2D, viewport: Viewport): SpriteBatch {
        check(!inWorld && !inUi) { "Nested passes: end the current pass first" }
        inWorld = true
        startWorldTarget(viewport, clearColor, false)
        camera.update(passViewport[2].toFloat() / max(1, passViewport[3]), viewport.rotate180)
        sprites.begin(camera.matrix)
        return sprites
    }

    override fun endWorld2D() {
        if (!inWorld) return
        sprites.end()
        inWorld = false
    }

    private fun startWorldTarget(viewport: Viewport, clear: Int, skyCovers: Boolean) {
        val alloc = perf.allocationScale
        passToScreen = composited || !postAvailable || !post.ensure(
            min((width * alloc).roundToInt(), OeaEngine.device.maxTextureSize),
            min((height * alloc).roundToInt(), OeaEngine.device.maxTextureSize),
        ).also { ok -> if (!ok) postAvailable = false }
        val tw = if (passToScreen) width else sceneW
        val th = if (passToScreen) height else sceneH
        if (!anyWorld) {
            if (passToScreen) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) else post.bindScene()
            GLES30.glViewport(0, 0, tw, th)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            val c = if (skyCovers) 0xFF000000.toInt() else clear
            GLES30.glClearColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
            GLES30.glDepthMask(true)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            anyWorld = true
        }
        passViewport[0] = (viewport.x * tw).roundToInt()
        passViewport[1] = ((1f - viewport.y - viewport.height) * th).roundToInt()
        passViewport[2] = max(1, (viewport.width * tw).roundToInt())
        passViewport[3] = max(1, (viewport.height * th).roundToInt())
        rebindWorldTarget()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /** Re-binds the current world pass target (after the shadow pass switched framebuffers). */
    internal fun rebindWorldTarget() {
        if (passToScreen) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) else post.bindScene()
        GLES30.glViewport(passViewport[0], passViewport[1], passViewport[2], passViewport[3])
    }

    // ---------------------------------------------------------------- UI passes

    override fun beginUi(): Hud {
        check(!inWorld && !inUi) { "Nested passes: end the current pass first" }
        if (anyWorld && !composited) composite()
        if (!anyWorld && !composited) clearScreen()
        inUi = true
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        sprites.beginPixels(width.toFloat(), height.toFloat())
        hud.begin(width.toFloat(), height.toFloat())
        return hud
    }

    override fun endUi() {
        if (!inUi) return
        hud.end()
        sprites.end()
        inUi = false
    }

    private fun clearScreen() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        val c = clearColor
        GLES30.glClearColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        composited = true
    }

    private fun composite() {
        composited = true
        if (passToScreen || !postAvailable) return
        val q = quality
        val hot = perf.thermalLimited
        post.composite(
            screenW = width, screenH = height, sceneW = sceneW, sceneH = sceneH,
            fxaa = q.fxaa,
            bloom = if (q.bloom && !hot) 0.55f else 0f,
            vignette = if (q.vignette) 0.22f else 0f,
            sharpen = if (scaleThisFrame < 0.92f) 0.35f else 0f,
            time = frameTime,
        )
    }

    private fun drawStats() {
        val s = perf.stats
        val text = String.format(
            java.util.Locale.US, "%.0f fps  %.1f ms  1%% low %.0f\n%.2fx  %s  %d draws  %d tris",
            s.fps, s.avgFrameMs, s.onePercentLowFps, renderScale, quality.tier.label, renderer.stats.drawCalls, renderer.stats.triangles,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        sprites.beginPixels(width.toFloat(), height.toFloat())
        val size = dp(11f)
        val x = safeInsets[0] + dp(10f)
        val y = safeInsets[1] + dp(8f)
        val w = font.measure(text, size) + dp(14f)
        sprites.roundRect(x - dp(6f), y - dp(4f), w, font.lineHeight(size) * 2 + dp(8f), dp(8f), 0x99000000.toInt())
        font.draw(sprites, text, x, y, size, 0xFFB9F6CA.toInt())
        sprites.end()
    }

    private companion object { const val TAG = "OeaGraphics" }
}

/** Scene render target with dynamic resolution, bloom and the final composite. */
internal class PostProcess {
    private var fbo = 0
    private var colorTex = 0
    private var depthRb = 0
    private var texW = 0
    private var texH = 0
    private var generation = -1
    private var failed = false
    private val bloomFbo = IntArray(2)
    private val bloomTex = IntArray(2)
    private var bloomW = 0
    private var bloomH = 0
    private val bright = Shader("bloom-bright", Shaders.FULLSCREEN_VERT, Shaders.BRIGHT_FRAG)
    private val blur = Shader("bloom-blur", Shaders.FULLSCREEN_VERT, Shaders.BLUR_FRAG)
    private val compositeShader = Shader("composite", Shaders.FULLSCREEN_VERT, Shaders.COMPOSITE_FRAG)

    /** Makes sure the scene target can hold [w]×[h]. Returns false if FBOs are unsupported. */
    fun ensure(w: Int, h: Int): Boolean {
        if (failed) return false
        if (generation != Gpu.generation) { fbo = 0; colorTex = 0; depthRb = 0; bloomFbo.fill(0); bloomTex.fill(0); texW = 0; texH = 0; generation = Gpu.generation }
        if (fbo != 0 && w <= texW && h <= texH) return true
        release()
        texW = w; texH = h
        colorTex = Gpu.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        linearClamp()
        depthRb = Gpu.genRenderbuffer()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        fbo = Gpu.genFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb)
        val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        // Bloom chain at quarter resolution.
        bloomW = max(1, w / 4); bloomH = max(1, h / 4)
        for (i in 0 until 2) {
            bloomTex[i] = Gpu.genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bloomW, bloomH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            linearClamp()
            bloomFbo[i] = Gpu.genFramebuffer()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTex[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!ok) {
            Log.w("OeaPost", "Scene render target unsupported; rendering directly to the screen")
            release()
            failed = true
        }
        return ok
    }

    private fun linearClamp() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun release() {
        if (generation == Gpu.generation) {
            Gpu.deleteFramebuffer(fbo); Gpu.deleteTexture(colorTex); Gpu.deleteRenderbuffer(depthRb)
            for (i in 0 until 2) { Gpu.deleteFramebuffer(bloomFbo[i]); Gpu.deleteTexture(bloomTex[i]) }
        }
        fbo = 0; colorTex = 0; depthRb = 0; bloomFbo.fill(0); bloomTex.fill(0)
    }

    fun bindScene() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo) }

    fun composite(screenW: Int, screenH: Int, sceneW: Int, sceneH: Int, fxaa: Boolean, bloom: Float, vignette: Float, sharpen: Float, time: Float) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        // Depth is no longer needed: tell tiled GPUs not to write it back to memory.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_DEPTH_ATTACHMENT), 0)
        val su = sceneW.toFloat() / texW
        val sv = sceneH.toFloat() / texH
        val bw = max(1, sceneW / 4); val bh = max(1, sceneH / 4)
        val bu = bw.toFloat() / bloomW; val bv = bh.toFloat() / bloomH
        val useBloom = bloom > 0f && bright.bind()
        if (useBloom) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
            GLES30.glViewport(0, 0, bw, bh)
            bright.set2f("u_uvScale", su, sv)
            bright.set2f("u_texel", 1f / texW, 1f / texH)
            bright.set1f("u_threshold", 0.78f)
            bright.set1i("u_tex", 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
            FullscreenTriangle.draw()
            if (blur.bind()) {
                blur.set2f("u_uvScale", bu, bv)
                blur.set2f("u_clamp", bu - 0.5f / bloomW, bv - 0.5f / bloomH)
                blur.set1i("u_tex", 0)
                for (pass in 0 until 2) {
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[1])
                    blur.set2f("u_dir", 1f / bloomW, 0f)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[0])
                    FullscreenTriangle.draw()
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
                    blur.set2f("u_dir", 0f, 1f / bloomH)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[1])
                    FullscreenTriangle.draw()
                }
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        if (!compositeShader.bind()) return
        compositeShader.set2f("u_uvScale", su, sv)
        compositeShader.set2f("u_uvMax", su - 0.5f / texW, sv - 0.5f / texH)
        compositeShader.set2f("u_texel", 1f / texW, 1f / texH)
        compositeShader.set2f("u_bloomUvScale", bu, bv)
        compositeShader.set4f("u_params", if (fxaa) 1f else 0f, if (useBloom) bloom else 0f, vignette, sharpen)
        compositeShader.set1f("u_time", time % 100f)
        compositeShader.set1i("u_scene", 0)
        compositeShader.set1i("u_bloom", 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (useBloom) bloomTex[0] else colorTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        FullscreenTriangle.draw()
        GLES30.glDepthMask(true)
    }
}
