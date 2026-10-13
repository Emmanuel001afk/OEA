package com.oea.engine.core

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.oea.engine.EngineSettings
import com.oea.engine.OeaEngine
import com.oea.engine.audio.Audio
import com.oea.engine.graphics.GameSurfaceView
import com.oea.engine.graphics.Graphics
import com.oea.engine.input.Haptics
import com.oea.engine.input.Input
import com.oea.engine.island.GameIsland
import com.oea.engine.net.session.NetSession
import com.oea.engine.perf.PerformanceManager
import com.oea.engine.ui.Hud
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

/**
 * Runs an [OeaGame] inside an Activity: creates the GL surface and every
 * engine service, drives the game loop on the GL thread, routes input,
 * handles pause/resume and GPU context loss, and isolates crashes.
 *
 * ```
 * host = GameHost(activity, descriptor, launch, hostUi)
 * setContentView(host.root)
 * // forward onResume/onPause/onDestroy, key and generic motion events, Back
 * ```
 * Android views placed in [overlay] are drawn above the game (menus, the
 * Dynamic Island overlay).
 */
class GameHost(
    private val activity: Activity,
    override val descriptor: GameDescriptor,
    override val launch: GameLaunch,
    hostUi: HostUi,
) : GameContext {
    override val android: Context = activity.applicationContext
    override val settings: EngineSettings = OeaEngine.settings
    override val perf = PerformanceManager(android, settings)
    override val input = Input(activity.resources.displayMetrics.density)
    override val graphics = Graphics(android, perf, settings)
    override val audio = Audio(android, settings)
    override val haptics = Haptics(android, settings)
    override val storage = GameStorage(android, descriptor.id)
    override val jobs = Jobs()
    override val time = GameTime()
    override val net: NetSession? = launch.session
    override val ui: HostUi = GameThreadUi(hostUi)
    override val island = GameIsland(descriptor, settings, { OeaEngine.islandHost }, ::runOnGameThread) { id -> safely { game.onIslandAction(id) } }

    private val game: OeaGame = descriptor.factory()
    private val surface = GameSurfaceView(activity)
    /** Root view: the game surface plus [overlay]. */
    val root = FrameLayout(activity)
    /** Android views shown above the game. */
    val overlay = FrameLayout(activity)

    private val lock = ReentrantLock()
    private val main = Handler(Looper.getMainLooper())
    private var created = false
    private var destroyed = false
    @Volatile private var failed = false
    private var lastNanos = 0L
    private var accumulator = 0.0
    private var background: Thread? = null
    @Volatile private var resumed = false

    /** Called (UI thread) when the game cannot continue; the host should close. */
    var onFatalError: ((Throwable) -> Unit)? = null

    init {
        OeaEngine.init(activity)
        game.attach(this)
        graphics.hud = Hud(graphics, input)
        activity.requestedOrientation = when (descriptor.orientation) {
            GameOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            GameOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            GameOrientation.ANY -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        surface.setRenderer(Renderer())
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surface.isFocusable = true
        surface.isFocusableInTouchMode = true
        surface.setOnTouchListener { _, e -> input.onTouchEvent(e) }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                perf.applySurfaceFrameRate(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
        setupWindow()
        perf.attach(activity)
    }

    private fun setupWindow() {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        immersive()
    }

    /** Hides system bars (swipe from an edge to reveal them briefly). */
    fun immersive() {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        val s = graphics.safeInsets
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            s[0] = i.left; s[1] = i.top; s[2] = i.right; s[3] = i.bottom
        } else if (Build.VERSION.SDK_INT >= 28) {
            val c = insets.displayCutout
            s[0] = c?.safeInsetLeft ?: 0; s[1] = c?.safeInsetTop ?: 0; s[2] = c?.safeInsetRight ?: 0; s[3] = c?.safeInsetBottom ?: 0
        }
    }

    // ---------------------------------------------------------------- lifecycle (UI thread)

    fun onResume() {
        resumed = true
        stopBackground()
        immersive()
        surface.onResume()
        audio.resume()
        surface.queueEvent { lock.withLock { perf.resetTiming(); lastNanos = 0L; if (created) safely { game.onResume() } } }
    }

    fun onPause() {
        resumed = false
        surface.queueEvent { lock.withLock { input.reset(); if (created) safely { game.onPause() } } }
        surface.onPause()
        audio.pause()
        if (created && !destroyed && game.keepsRunningInBackground()) startBackground()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) { if (hasFocus) immersive() }

    fun onDestroy() {
        destroyed = true
        stopBackground()
        island.end()
        perf.detach(activity)
        surface.queueEvent {
            lock.withLock {
                if (created) safely { game.onDestroy() }
                perf.onGlThreadStopped()
            }
        }
        audio.release()
        jobs.shutdown()
    }

    /** Back press: the game may consume it; otherwise [fallback] runs on the UI thread. */
    fun onBackPressed(fallback: () -> Unit) {
        runOnGameThread {
            val handled = safely { game.onBack() } ?: false
            if (!handled) main.post(fallback)
        }
    }

    fun dispatchKeyEvent(e: KeyEvent): Boolean = input.onKeyEvent(e)
    fun dispatchGenericMotionEvent(e: MotionEvent): Boolean = input.onGenericMotion(e)

    override fun runOnGameThread(action: () -> Unit) = jobs.post(action)

    // ---------------------------------------------------------------- background simulation

    private fun startBackground() {
        if (background != null) return
        val stepNanos = 1_000_000_000L / game.fixedStepHz.coerceIn(10, 240)
        background = Thread({
            var next = System.nanoTime()
            while (!Thread.currentThread().isInterrupted && !resumed && !destroyed) {
                next += stepNanos
                lock.withLock {
                    if (!failed) safely {
                        jobs.drain()
                        game.onFixedUpdate(1f / game.fixedStepHz)
                        time.fixedTick++
                        island.tick()
                    }
                }
                val sleep = next - System.nanoTime()
                if (sleep > 0) try { Thread.sleep(sleep / 1_000_000L, (sleep % 1_000_000L).toInt()) } catch (_: InterruptedException) { break }
                else next = System.nanoTime()
            }
        }, "oea-game-bg").apply { isDaemon = true; start() }
    }

    private fun stopBackground() {
        background?.interrupt()
        runCatching { background?.join(200) }
        background = null
    }

    // ---------------------------------------------------------------- GL thread

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            lock.withLock {
                perf.onGlThreadStarted()
                graphics.onContextCreated()
                if (created) safely { game.onGpuContextRestored() }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            lock.withLock {
                graphics.onSurfaceChanged(width, height)
                perf.onSurfaceSize(width, height)
                if (!created) {
                    created = true
                    time.fixedDelta = 1f / game.fixedStepHz.coerceIn(10, 240)
                    safely { game.onCreate() }
                }
                safely { game.onResize(width, height) }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            lock.withLock { frame(start) }
            perf.endFrame(System.nanoTime(), System.nanoTime() - start)
        }
    }

    private fun frame(now: Long) {
        if (failed || !created) {
            graphics.beginFrame(0f, 0f)
            graphics.clear(0xFF000000.toInt())
            graphics.endFrame(false)
            return
        }
        val rawDt = if (lastNanos == 0L) time.fixedDelta.toDouble() else (now - lastNanos) / 1e9
        lastNanos = now
        val dt = rawDt.coerceIn(0.0, 0.1).toFloat() * time.timeScale
        time.delta = dt
        time.elapsed += rawDt.coerceAtMost(0.1)
        time.frame++
        safely {
            input.beginFrame(time.elapsed, dt)
            jobs.drain()
            accumulator += dt
            val step = time.fixedDelta.toDouble()
            var steps = 0
            while (accumulator >= step && steps < MAX_STEPS) {
                game.onFixedUpdate(time.fixedDelta)
                time.fixedTick++
                accumulator -= step
                steps++
            }
            if (steps == MAX_STEPS) accumulator = 0.0
            time.alpha = (accumulator / step).toFloat().coerceIn(0f, 1f)
            game.onUpdate(dt)
            island.tick()
            graphics.beginFrame(time.alpha, dt)
            game.onRender(graphics)
            graphics.endFrame(settings.showFps)
        }
    }

    /** Runs game code, converting any exception into a clean shutdown instead of a launcher crash. */
    private inline fun <T> safely(block: () -> T): T? {
        if (failed) return null
        return try {
            block()
        } catch (t: Throwable) {
            failed = true
            Log.e(TAG, "Game '${descriptor.id}' stopped", t)
            main.post {
                onFatalError?.invoke(t) ?: run {
                    ui.toast("${descriptor.title} stopped: ${t.javaClass.simpleName}")
                    activity.finish()
                }
            }
            null
        }
    }

    /** Wraps the activity's [HostUi] so every callback lands on the game thread. */
    private inner class GameThreadUi(private val delegate: HostUi) : HostUi {
        override fun toast(message: String) = main.post { delegate.toast(message) }.let { }
        override fun share(text: String, title: String) = main.post { delegate.share(text, title) }.let { }
        override fun menu(title: String, subtitle: String?, items: List<MenuItem>, onPick: (String?) -> Unit) =
            main.post { delegate.menu(title, subtitle, items) { id -> runOnGameThread { onPick(id) } } }.let { }
        override fun confirm(title: String, message: String, positive: String, negative: String, onResult: (Boolean) -> Unit) =
            main.post { delegate.confirm(title, message, positive, negative) { ok -> runOnGameThread { onResult(ok) } } }.let { }
        override fun prompt(title: String, hint: String, initial: String, onResult: (String?) -> Unit) =
            main.post { delegate.prompt(title, hint, initial) { v -> runOnGameThread { onResult(v) } } }.let { }
        override fun exit(result: GameResult?) = main.post { delegate.exit(result) }.let { }
    }

    private companion object {
        const val TAG = "OeaGameHost"
        const val MAX_STEPS = 8
        @Suppress("unused") val CLOCK = SystemClock::class.java
    }
}
