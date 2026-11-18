package com.oea.games.allinone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View

/**
 * The glass in front of a cabinet.
 *
 * One view runs every game: it owns the animation clock, converts touches into
 * the game's own pixel space, keeps painting the final board after a result,
 * and never lets a slow frame (a resumed app, a rotation) turn into a physics
 * explosion — the delta is capped.
 *
 * Games rarely need sixty updates a second of physics, but they are all cheap;
 * the frame callback is only scheduled while the view is actually attached and
 * running, so a paused arcade burns no battery.
 */
class ArcadeGameView(context: Context) : View(context) {

    var game: ArcadeGame? = null
        private set

    /** Fired once, on the UI thread, when the game produces its result. */
    var onFinished: ((ArcadeResult) -> Unit)? = null

    /** Ticks between frames, for games with sound or haptics. */
    var onFrame: ((dt: Float) -> Unit)? = null

    private var running = false
    private var paused = false
    private var announced = false
    private var lastFrame = 0L

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val raw = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            val dt = raw.coerceIn(0f, 0.05f)
            val live = game
            if (live != null && !paused) {
                live.update(dt)
                onFrame?.invoke(dt)
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    fun attach(newGame: ArcadeGame, config: ArcadeConfig) {
        game = newGame
        announced = false
        paused = false
        newGame.start(config)
        start()
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        postOnAnimation(frame)
    }

    fun pauseGame() {
        if (paused) return
        paused = true
        game?.setPaused(true)
        running = false
        invalidate()
    }

    fun resumeGame() {
        if (!paused) return
        paused = false
        game?.setPaused(false)
        start()
    }

    val isPaused: Boolean get() = paused

    /** Restart the same cabinet with a fresh seed (and the same level and difficulty). */
    fun restart(config: ArcadeConfig) {
        val live = game ?: return
        announced = false
        paused = false
        live.start(config)
        start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val live = game ?: return
        if (width <= 0 || height <= 0) return
        live.draw(canvas, width.toFloat(), height.toFloat())
        val result = live.result()
        if (result != null && !announced) {
            announced = true
            post { onFinished?.invoke(result) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val live = game ?: return false
        if (paused) return false
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> ArcadeTouch.DOWN
            MotionEvent.ACTION_MOVE -> ArcadeTouch.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ArcadeTouch.UP
            else -> return false
        }
        if (phase == ArcadeTouch.DOWN) performClick()
        return live.touch(phase, event.x, event.y, width.toFloat(), height.toFloat())
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        running = false
        paused = false
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (game != null && !paused) start()
    }
}
