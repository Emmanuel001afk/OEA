package com.oea.launcher.interaction

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * OEA-native directional gesture layer.
 * It detects four directions without replacing Android's system navigation.
 */
class OeaGestureController(
    private val view: View,
    private val onSwipeUp: () -> Unit = {},
    private val onSwipeDown: () -> Unit = {},
    private val onSwipeLeft: () -> Unit = {},
    private val onSwipeRight: () -> Unit = {},
    private val triggerDistanceDp: Float = 72f,
    private val consumeTouchEvents: Boolean = true,
) : GestureDetector.SimpleOnGestureListener(), View.OnTouchListener {

    private val detector = GestureDetector(view.context, this)
    private val density = view.resources.displayMetrics.density

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        if (e1 == null) return false
        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        val distance = triggerDistanceDp * density
        if (maxOf(abs(dx), abs(dy)) < distance) return false

        if (abs(dx) > abs(dy)) {
            if (dx < 0) onSwipeLeft() else onSwipeRight()
        } else {
            if (dy < 0) onSwipeUp() else onSwipeDown()
        }
        return true
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val detected = detector.onTouchEvent(event)
        return if (consumeTouchEvents) detected else false
    }
}
