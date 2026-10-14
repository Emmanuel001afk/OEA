package com.oea.engine.graphics

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * A pre-flight check that asks the driver for an OpenGL ES 3 configuration
 * *before* a GLSurfaceView exists.
 *
 * It matters because of how GLSurfaceView behaves when it cannot get one: the
 * failure happens on its own GL thread, escapes as a RuntimeException and kills
 * the host process. From the user's side that is not "the game did not start", it
 * is "OEA closed" — so the question is asked here first, on the caller's thread,
 * where the answer can be turned into a readable message instead. Nothing is
 * created: a display is opened, a configuration is looked for and the display is
 * closed again. It costs a few milliseconds, once, when a game starts.
 */
object SurfaceProbe {
    /** null when a surface can be created; otherwise why it cannot. */
    fun reason(): String? = runCatching {
        val egl = javax.microedition.khronos.egl.EGLContext.getEGL() as? javax.microedition.khronos.egl.EGL10
            ?: return null
        val display = egl.eglGetDisplay(javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY)
        if (display == null || display == javax.microedition.khronos.egl.EGL10.EGL_NO_DISPLAY) {
            return "Android did not provide a display for games on this phone."
        }
        val version = IntArray(2)
        if (!egl.eglInitialize(display, version)) {
            return "The graphics driver would not start (EGL could not initialise)."
        }
        try {
            val attempts = listOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_NONE),
            )
            for (attribs in attempts) {
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attribs, null, 0, count) && count[0] > 0) return null
            }
            // Nothing at all: name the real reason, because the two are different
            // problems with different answers.
            val es3 = IntArray(2)
            if (egl.eglGetConfigs(display, null, 0, es3) && es3[0] > 0) {
                "This phone's graphics driver offers no OpenGL ES 3.0 display, which OEA's games need."
            } else {
                "This phone reported no graphics configurations at all; a reboot usually restores the driver."
            }
        } finally {
            runCatching { egl.eglTerminate(display) }
        }
    }

    /** EGL_OPENGL_ES3_BIT_KHR */
    private const val EGL_OPENGL_ES3_BIT = 0x40
}

/**
 * OpenGL ES 3 surface. Anti-aliasing and resolution scaling happen in the
 * engine's own render targets, so the window surface itself is a plain
 * RGBA8888 buffer with a depth buffer for the direct-render fallback.
 */
internal class GameSurfaceView(context: Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(ConfigChooser())
        preserveEGLContextOnPause = true
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
    }

    private class ConfigChooser : EGLConfigChooser {
        /**
         * Finds a usable surface configuration, from ideal down to "anything the
         * driver will give us".
         *
         * This deliberately never throws. A chooser that throws does not fail a
         * game politely: the exception escapes into GLSurfaceView's own thread and
         * takes the whole process with it, which from the user's side looks like OEA
         * itself crashed. Every attempt below is tried in order, and the last one
         * accepts a configuration with no depth buffer rather than give up — the
         * engine renders into its own depth-attached targets, so a window without
         * depth still draws.
         */
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
            val attempts = listOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                // Same three shapes without a depth buffer, for drivers that only
                // expose depth-less window configs to ES3.
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                // Last resort: whatever the driver will render to at all, depth or no
                // depth. An ES3 context on such a config beats no game at all.
                intArrayOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_NONE),
            )
            for (attribs in attempts) {
                val count = IntArray(1)
                if (!runCatching { egl.eglChooseConfig(display, attribs, null, 0, count) }.getOrDefault(false)) continue
                if (count[0] <= 0) continue
                val configs = arrayOfNulls<EGLConfig>(count[0])
                if (!runCatching { egl.eglChooseConfig(display, attribs, configs, count[0], count) }.getOrDefault(false)) continue
                val available = configs.filterNotNull()
                if (available.isEmpty()) continue
                // Prefer an exact colour match (drivers may list deeper formats first).
                val wantRed = attribs.getOrNull(1)
                val exact = if (wantRed != null) available.firstOrNull { c ->
                    val v = IntArray(1)
                    egl.eglGetConfigAttrib(display, c, EGL10.EGL_RED_SIZE, v) && v[0] == wantRed
                } else null
                val chosen = exact ?: available.first()
                Log.i("OeaSurface", "Surface config chosen after ${attempts.indexOf(attribs) + 1} attempt(s)")
                return chosen
            }
            Log.e("OeaSurface", "No OpenGL ES surface configuration on this device")
            return null
        }
    }

    private companion object {
        /** EGL_OPENGL_ES3_BIT_KHR */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
