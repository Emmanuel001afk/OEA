package com.oea.engine.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.oea.engine.math.MathX
import kotlin.math.abs
import kotlin.math.hypot

/** One finger on the screen. Coordinates are surface pixels, origin top-left. */
class Pointer internal constructor(val id: Int) {
    var x = 0f; internal set
    var y = 0f; internal set
    var startX = 0f; internal set
    var startY = 0f; internal set
    /** Movement since the previous frame. */
    var dx = 0f; internal set
    var dy = 0f; internal set
    var isDown = false; internal set
    var justPressed = false; internal set
    var justReleased = false; internal set
    /** Seconds since the finger went down. */
    var heldTime = 0f; internal set
    /** Pressed on a HUD widget: games should ignore it. */
    var onUi = false; internal set
    /** The control (joystick, button, look pad ...) that claimed this pointer. */
    var owner: Any? = null
    internal var downAt = 0.0
    internal var swipeX = 0f
    internal var swipeY = 0f
    internal var swiped = false
    internal var maxTravel = 0f
    val isFree get() = owner == null && !onUi
}

enum class Swipe { LEFT, RIGHT, UP, DOWN }

class SwipeEvent internal constructor(val direction: Swipe, val pointerId: Int, val x: Float, val y: Float, val startX: Float, val startY: Float)
class TapEvent internal constructor(val x: Float, val y: Float, val pointerId: Int, val count: Int)

/**
 * Touch, keyboard and gamepad input, collected on the UI thread and handed to
 * the game thread once per frame. Gestures fire as early as possible: a swipe
 * triggers the moment the finger travels [swipeThresholdDp], not on release,
 * which is what fast games like the Runner need.
 */
class Input internal constructor(private val density: Float) {
    private val lock = Any()
    private var qAction = IntArray(256)
    private var qId = IntArray(256)
    private var qX = FloatArray(256)
    private var qY = FloatArray(256)
    private var qCount = 0

    private val active = ArrayList<Pointer>(MAX_POINTERS)
    private val swipeList = ArrayList<SwipeEvent>()
    private val tapList = ArrayList<TapEvent>()
    private var lastTapTime = -1.0
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var now = 0.0

    /** Pointers currently down or released this frame. */
    val pointers: List<Pointer> get() = active
    val swipes: List<SwipeEvent> get() = swipeList
    val taps: List<TapEvent> get() = tapList
    val keys = Keys()
    val gamepad = Gamepad()

    var swipeThresholdDp = 26f
    var tapMaxTravelDp = 14f
    var tapMaxTime = 0.3f
    var doubleTapTime = 0.32

    /** HUD hit areas from the previous frame (x, y, w, h quadruples); touches starting inside belong to the UI. */
    internal val uiRects = FloatArray(4 * 128)
    internal var uiRectCount = 0
    private val pendingUiRects = FloatArray(4 * 128)
    private var pendingUiCount = 0

    fun pointer(id: Int): Pointer? = active.firstOrNull { it.id == id }
    val anyDown: Boolean get() = active.any { it.isDown }

    /** Removes and returns the first swipe of this frame (so one handler consumes it). */
    fun consumeSwipe(): SwipeEvent? = if (swipeList.isEmpty()) null else swipeList.removeAt(0)
    fun consumeTap(): TapEvent? = if (tapList.isEmpty()) null else tapList.removeAt(0)

    /** Pointers that went down this frame and are free (not on UI, not claimed). */
    inline fun forEachNewFree(block: (Pointer) -> Unit) {
        for (p in pointers) if (p.justPressed && p.isFree) block(p)
    }

    // ---------------------------------------------------------------- UI thread

    internal fun onTouchEvent(e: MotionEvent): Boolean {
        synchronized(lock) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    push(DOWN, e.getPointerId(i), e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = e.actionIndex
                    push(UP, e.getPointerId(i), e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) push(MOVE, e.getPointerId(i), e.getX(i), e.getY(i))
                MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) push(UP, e.getPointerId(i), e.getX(i), e.getY(i))
            }
        }
        return true
    }

    private fun push(action: Int, id: Int, x: Float, y: Float) {
        if (qCount == qAction.size) {
            val n = qCount * 2
            qAction = qAction.copyOf(n); qId = qId.copyOf(n); qX = qX.copyOf(n); qY = qY.copyOf(n)
        }
        // Collapse consecutive moves of the same pointer.
        if (action == MOVE && qCount > 0 && qAction[qCount - 1] == MOVE && qId[qCount - 1] == id) {
            qX[qCount - 1] = x; qY[qCount - 1] = y
            return
        }
        qAction[qCount] = action; qId[qCount] = id; qX[qCount] = x; qY[qCount] = y
        qCount++
    }

    internal fun onKeyEvent(e: KeyEvent): Boolean {
        val handled = Gamepad.isGamepadKey(e.keyCode) || e.keyCode in 19..22 || e.keyCode in 29..54 || e.keyCode == KeyEvent.KEYCODE_SPACE ||
            e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || e.keyCode == KeyEvent.KEYCODE_CTRL_LEFT
        if (!handled) return false
        synchronized(lock) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) keys.queueDown(e.keyCode)
                KeyEvent.ACTION_UP -> keys.queueUp(e.keyCode)
            }
            gamepad.onKey(e)
        }
        return true
    }

    internal fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        synchronized(lock) { gamepad.onMotion(e) }
        return true
    }

    // ---------------------------------------------------------------- game thread

    internal fun beginFrame(time: Double, dt: Float) {
        now = time
        swipeList.clear()
        tapList.clear()
        // Drop pointers released last frame; reset per-frame flags.
        active.removeAll { !it.isDown }
        for (p in active) { p.justPressed = false; p.justReleased = false; p.dx = 0f; p.dy = 0f; p.heldTime += dt }
        synchronized(lock) {
            for (k in 0 until qCount) apply(qAction[k], qId[k], qX[k], qY[k])
            qCount = 0
            keys.swap()
            gamepad.swap()
        }
        System.arraycopy(pendingUiRects, 0, uiRects, 0, pendingUiCount * 4)
        uiRectCount = pendingUiCount
        pendingUiCount = 0
        detectGestures()
    }

    private fun apply(action: Int, id: Int, x: Float, y: Float) {
        when (action) {
            DOWN -> {
                val p = active.firstOrNull { it.id == id } ?: obtain(id) ?: return
                p.x = x; p.y = y; p.startX = x; p.startY = y; p.swipeX = x; p.swipeY = y
                p.isDown = true; p.justPressed = true; p.justReleased = false
                p.heldTime = 0f; p.downAt = now; p.swiped = false; p.maxTravel = 0f
                p.owner = null
                p.onUi = hitsUi(x, y)
            }
            MOVE -> {
                val p = active.firstOrNull { it.id == id && it.isDown } ?: return
                p.dx += x - p.x; p.dy += y - p.y
                p.x = x; p.y = y
                p.maxTravel = maxOf(p.maxTravel, hypot(x - p.startX, y - p.startY))
            }
            UP -> {
                val p = active.firstOrNull { it.id == id && it.isDown } ?: return
                p.dx += x - p.x; p.dy += y - p.y
                p.x = x; p.y = y
                p.isDown = false
                p.justReleased = true
                if (!p.onUi && p.owner == null) maybeTap(p)
            }
        }
    }

    private fun obtain(id: Int): Pointer? {
        if (active.size >= MAX_POINTERS) return null
        return Pointer(id).also { active += it }
    }

    private fun detectGestures() {
        val threshold = swipeThresholdDp * density
        for (p in active) {
            if (!p.isDown || p.onUi || p.owner != null) continue
            val dx = p.x - p.swipeX
            val dy = p.y - p.swipeY
            if (abs(dx) < threshold && abs(dy) < threshold) continue
            val dir = if (abs(dx) > abs(dy)) (if (dx > 0) Swipe.RIGHT else Swipe.LEFT) else (if (dy > 0) Swipe.DOWN else Swipe.UP)
            swipeList += SwipeEvent(dir, p.id, p.x, p.y, p.swipeX, p.swipeY)
            p.swiped = true
            // Chain: the next swipe in the same touch measures from here.
            p.swipeX = p.x; p.swipeY = p.y
        }
    }

    private fun maybeTap(p: Pointer) {
        if (p.swiped) return
        if (p.maxTravel > tapMaxTravelDp * density) return
        if (now - p.downAt > tapMaxTime) return
        val double = lastTapTime >= 0 && now - lastTapTime < doubleTapTime && hypot(p.x - lastTapX, p.y - lastTapY) < 40f * density
        tapList += TapEvent(p.x, p.y, p.id, if (double) 2 else 1)
        lastTapTime = if (double) -1.0 else now
        lastTapX = p.x; lastTapY = p.y
    }

    /** Registers a HUD hit area for the next frame's touch routing. */
    internal fun addUiRect(x: Float, y: Float, w: Float, h: Float) {
        if (pendingUiCount * 4 >= pendingUiRects.size) return
        val o = pendingUiCount * 4
        pendingUiRects[o] = x; pendingUiRects[o + 1] = y; pendingUiRects[o + 2] = w; pendingUiRects[o + 3] = h
        pendingUiCount++
    }

    private fun hitsUi(x: Float, y: Float): Boolean {
        for (i in 0 until uiRectCount) {
            val o = i * 4
            if (x >= uiRects[o] && x <= uiRects[o] + uiRects[o + 2] && y >= uiRects[o + 1] && y <= uiRects[o + 1] + uiRects[o + 3]) return true
        }
        return false
    }

    /** Clears all touches (e.g. when the game pauses). */
    internal fun reset() {
        synchronized(lock) { qCount = 0 }
        active.clear()
        swipeList.clear()
        tapList.clear()
        keys.clear()
    }

    private companion object {
        const val MAX_POINTERS = 10
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
    }
}

/** Hardware keyboard state (Chromebooks, Bluetooth keyboards, emulators). */
class Keys internal constructor() {
    private val down = BooleanArray(512)
    private val pressedNow = BooleanArray(512)
    private val releasedNow = BooleanArray(512)
    private val queuedDown = ArrayList<Int>()
    private val queuedUp = ArrayList<Int>()

    fun isDown(keyCode: Int) = keyCode in down.indices && down[keyCode]
    fun justPressed(keyCode: Int) = keyCode in pressedNow.indices && pressedNow[keyCode]
    fun justReleased(keyCode: Int) = keyCode in releasedNow.indices && releasedNow[keyCode]

    internal fun queueDown(k: Int) { queuedDown += k }
    internal fun queueUp(k: Int) { queuedUp += k }

    internal fun swap() {
        pressedNow.fill(false); releasedNow.fill(false)
        for (k in queuedDown) if (k in down.indices) { if (!down[k]) pressedNow[k] = true; down[k] = true }
        for (k in queuedUp) if (k in down.indices) { down[k] = false; releasedNow[k] = true }
        queuedDown.clear(); queuedUp.clear()
    }

    internal fun clear() { down.fill(false); pressedNow.fill(false); releasedNow.fill(false); queuedDown.clear(); queuedUp.clear() }
}

/** Standard gamepad (Android's gamepad key and axis mapping). Sticks are -1..1 with a dead zone applied. */
class Gamepad internal constructor() {
    var connected = false; private set
    var leftX = 0f; private set
    var leftY = 0f; private set
    var rightX = 0f; private set
    var rightY = 0f; private set
    var leftTrigger = 0f; private set
    var rightTrigger = 0f; private set
    private val buttons = Keys()
    private var pending = FloatArray(6)
    var deadZone = 0.16f

    fun isDown(keyCode: Int) = buttons.isDown(keyCode)
    fun justPressed(keyCode: Int) = buttons.justPressed(keyCode)

    internal fun onKey(e: KeyEvent) {
        if (!isGamepadKey(e.keyCode)) return
        connected = true
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) buttons.queueDown(e.keyCode)
            KeyEvent.ACTION_UP -> buttons.queueUp(e.keyCode)
        }
    }

    internal fun onMotion(e: MotionEvent) {
        connected = true
        pending[0] = e.getAxisValue(MotionEvent.AXIS_X)
        pending[1] = e.getAxisValue(MotionEvent.AXIS_Y)
        pending[2] = e.getAxisValue(MotionEvent.AXIS_Z)
        pending[3] = e.getAxisValue(MotionEvent.AXIS_RZ)
        pending[4] = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        pending[5] = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
    }

    internal fun swap() {
        buttons.swap()
        leftX = dz(pending[0]); leftY = dz(pending[1]); rightX = dz(pending[2]); rightY = dz(pending[3])
        leftTrigger = pending[4]; rightTrigger = pending[5]
    }

    private fun dz(v: Float): Float {
        val a = abs(v)
        if (a < deadZone) return 0f
        return MathX.sign(v) * (a - deadZone) / (1f - deadZone)
    }

    companion object {
        fun isGamepadKey(k: Int): Boolean = KeyEvent.isGamepadButton(k) || k in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
    }
}
