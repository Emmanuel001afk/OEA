package com.oea.engine.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.oea.engine.EngineSettings
import com.oea.engine.graphics.Align
import com.oea.engine.graphics.BitmapFont
import com.oea.engine.graphics.SpriteBatch
import com.oea.engine.math.Colors
import com.oea.engine.math.MathX
import com.oea.engine.math.Vec2
import kotlin.math.hypot

/**
 * On-screen analogue stick. With [floating], it appears wherever the finger
 * lands inside [region] (normalised screen rect) and follows the finger when
 * dragged past its edge, which is how mobile shooters feel natural.
 */
class VirtualJoystick(
    var region: FloatArray = floatArrayOf(0f, 0.3f, 0.45f, 1f),
    var radiusDp: Float = 62f,
    var floating: Boolean = true,
    /** Resting position (normalised) shown when idle and used when not floating. */
    var homeX: Float = 0.16f,
    var homeY: Float = 0.78f,
    var deadZone: Float = 0.1f,
) {
    /** Stick deflection: x right, y up (forward), length 0..1. */
    val value = Vec2()
    var active = false; private set
    private var pointerId = -1
    private var baseX = 0f
    private var baseY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var radius = 1f

    val magnitude get() = value.len()

    /** When true the control stands down: no touches claimed, value reads zero (UI overlays). */
    var paused = false

    fun update(input: Input, width: Float, height: Float, density: Float) {
        if (paused) {
            input.pointer(pointerId)?.owner = null
            pointerId = -1
            value.set(0f, 0f); active = false
            return
        }
        radius = radiusDp * density
        val owned = if (pointerId >= 0) input.pointer(pointerId) else null
        if (owned == null || !owned.isDown) {
            owned?.owner = null
            active = false; pointerId = -1; value.set(0f, 0f)
            input.forEachNewFree { p ->
                if (!active && inRegion(p.x / width, p.y / height)) {
                    p.owner = this
                    pointerId = p.id
                    active = true
                    if (floating) { baseX = p.x; baseY = p.y } else { baseX = homeX * width; baseY = homeY * height }
                }
            }
        }
        if (!active) { baseX = homeX * width; baseY = homeY * height; knobX = baseX; knobY = baseY; return }
        val p = input.pointer(pointerId) ?: return
        var dx = p.x - baseX; var dy = p.y - baseY
        val d = hypot(dx, dy)
        if (floating && d > radius * 1.35f) {
            // Drag the base along so the stick never "runs out".
            val k = (d - radius * 1.35f) / d
            baseX += dx * k; baseY += dy * k
            dx = p.x - baseX; dy = p.y - baseY
        }
        val len = hypot(dx, dy)
        val clamped = minOf(len, radius)
        val nx = if (len > 1e-3f) dx / len else 0f
        val ny = if (len > 1e-3f) dy / len else 0f
        knobX = baseX + nx * clamped; knobY = baseY + ny * clamped
        var m = clamped / radius
        m = if (m < deadZone) 0f else (m - deadZone) / (1f - deadZone)
        value.set(nx * m, -ny * m)
    }

    private fun inRegion(nx: Float, ny: Float) = nx >= region[0] && ny >= region[1] && nx <= region[2] && ny <= region[3]

    fun draw(batch: SpriteBatch, accent: Int = Colors.WHITE, idleAlpha: Float = 0.35f) {
        val a = if (active) 1f else idleAlpha
        batch.circle(baseX, baseY, radius * 1.08f, Colors.withAlpha(0xFF000000.toInt(), 0.22f * a))
        batch.ring(baseX, baseY, radius, radius * 0.06f, Colors.withAlpha(Colors.WHITE, 0.55f * a))
        batch.circle(knobX, knobY, radius * 0.42f, Colors.withAlpha(accent, 0.85f * a))
        batch.ring(knobX, knobY, radius * 0.42f, radius * 0.05f, Colors.withAlpha(Colors.WHITE, 0.8f * a))
    }
}

/**
 * Round on-screen button. Supports hold (auto-fire), tap and drag deltas
 * while held (so a fire button can also aim, like popular mobile shooters).
 */
class VirtualButton(
    var anchorX: Float,
    var anchorY: Float,
    var offsetXDp: Float = 0f,
    var offsetYDp: Float = 0f,
    var radiusDp: Float = 34f,
    var label: String = "",
    var color: Int = Colors.WHITE,
) {
    var pressed = false; private set
    var justPressed = false; private set
    var justReleased = false; private set
    /** Finger movement this frame while held (px). */
    var dragDx = 0f; private set
    var dragDy = 0f; private set
    var enabled = true
    var visible = true
    /** Optional 0..1 cooldown sweep drawn around the button. */
    var progress = -1f
    var highlight = false
    private var pointerId = -1
    var cx = 0f; private set
    var cy = 0f; private set
    var radius = 1f; private set

    /** When true the button stands down: no touches claimed (UI overlays). */
    var paused = false

    fun update(input: Input, width: Float, height: Float, density: Float, safeLeft: Float = 0f, safeRight: Float = 0f) {
        if (paused) {
            input.pointer(pointerId)?.owner = null
            pointerId = -1
            pressed = false; justPressed = false; justReleased = false; dragDx = 0f; dragDy = 0f
            return
        }
        radius = radiusDp * density
        cx = anchorX * width + offsetXDp * density + (if (anchorX < 0.5f) safeLeft else -safeRight)
        cy = anchorY * height + offsetYDp * density
        justPressed = false; justReleased = false; dragDx = 0f; dragDy = 0f
        if (!enabled || !visible) {
            if (pointerId >= 0) input.pointer(pointerId)?.owner = null
            if (pressed) justReleased = true
            pressed = false; pointerId = -1
            return
        }
        if (pointerId >= 0) {
            val p = input.pointer(pointerId)
            if (p == null || !p.isDown) {
                p?.owner = null
                pressed = false; justReleased = true; pointerId = -1
            } else {
                dragDx = p.dx; dragDy = p.dy
            }
        }
        if (pointerId < 0) {
            input.forEachNewFree { p ->
                if (pointerId < 0 && hypot(p.x - cx, p.y - cy) <= radius * 1.15f) {
                    p.owner = this
                    pointerId = p.id
                    pressed = true; justPressed = true
                }
            }
        }
    }

    fun draw(batch: SpriteBatch, font: BitmapFont, density: Float) {
        if (!visible) return
        val a = if (!enabled) 0.35f else if (pressed) 1f else 0.78f
        val fill = if (pressed || highlight) Colors.withAlpha(color, 0.55f * a) else Colors.withAlpha(0xFF101318.toInt(), 0.45f * a)
        batch.circle(cx, cy, radius * (if (pressed) 0.94f else 1f), fill)
        batch.ring(cx, cy, radius, maxOf(1.5f, radius * 0.06f), Colors.withAlpha(color, 0.9f * a))
        if (progress in 0f..1f) batch.arc(cx, cy, radius + 4f * density, 3f * density, 0f, MathX.TAU * progress, Colors.withAlpha(Colors.WHITE, 0.9f))
        if (label.isNotEmpty()) {
            val size = radius * (if (label.length <= 2) 0.72f else 0.42f)
            font.draw(batch, label, cx, cy - font.lineHeight(size) / 2f, size, Colors.withAlpha(Colors.WHITE, a), Align.CENTER)
        }
    }
}

/** Free-look area: claims touches that start inside [region] and reports their movement. */
class LookPad(var region: FloatArray = floatArrayOf(0.45f, 0f, 1f, 1f)) {
    /**
     * Largest per-frame movement (surface px) believed to come from a real
     * finger. Android occasionally reports a pointer resync jump of hundreds of
     * pixels the instant a second finger lifts; those spikes are what make a
     * camera "glitch around" mid-turn. Anything beyond this is discarded.
     */
    var maxDeltaPx: Float = 160f
    var deltaX = 0f; private set
    var deltaY = 0f; private set
    var active = false; private set
    private var pointerId = -1
    /** True on the frame a finger was claimed: its whole travel happened before we owned it. */
    private var claimFrame = false

    /** When true the pad stands down: no touches claimed (UI overlays). */
    var paused = false

    fun update(input: Input, width: Float, height: Float) {
        if (paused) {
            input.pointer(pointerId)?.owner = null
            pointerId = -1
            claimFrame = false
            deltaX = 0f; deltaY = 0f; active = false
            return
        }
        deltaX = 0f; deltaY = 0f
        val wasClaim = claimFrame
        claimFrame = false
        if (pointerId >= 0) {
            val p = input.pointer(pointerId)
            if (p == null || !p.isDown) { p?.owner = null; pointerId = -1; active = false }
            else if (!wasClaim) {
                val ddx = p.dx; val ddy = p.dy
                val m = maxDeltaPx
                if (m > 0f && (ddx > m || ddx < -m || ddy > m || ddy < -m)) {
                    // Pointer resync, not a finger: swallow the jump entirely.
                    deltaX = 0f; deltaY = 0f
                } else { deltaX = ddx; deltaY = ddy }
            }
        }
        if (pointerId < 0) {
            input.forEachNewFree { p ->
                val nx = p.x / width; val ny = p.y / height
                if (pointerId < 0 && nx >= region[0] && ny >= region[1] && nx <= region[2] && ny <= region[3]) {
                    p.owner = this
                    pointerId = p.id
                    active = true
                    claimFrame = true
                }
            }
        }
    }
}

/** Short vibration cues tuned for games. Respects the engine's haptics setting. */
class Haptics internal constructor(context: Context, private val settings: EngineSettings) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }.getOrNull()

    private var lastAt = 0L

    fun tick() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_TICK else -1, 8, 60)
    fun click() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_CLICK else -1, 14, 120)
    fun heavy() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_HEAVY_CLICK else -1, 30, 255)
    fun double() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_DOUBLE_CLICK else -1, 24, 180)

    /** Custom buzz: [ms] duration, [amplitude] 1..255. */
    fun buzz(ms: Long, amplitude: Int = 160) {
        val v = vibrator ?: return
        if (!settings.haptics || !rateOk()) return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255))) }
    }

    private fun predefined(effect: Int, fallbackMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!settings.haptics || !rateOk()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29 && effect >= 0) v.vibrate(VibrationEffect.createPredefined(effect))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, amplitude))
        }
    }

    /** Avoids a buzzing mess when many events fire at once. */
    private fun rateOk(): Boolean {
        val now = System.nanoTime()
        if (now - lastAt < 35_000_000L) return false
        lastAt = now
        return true
    }
}
