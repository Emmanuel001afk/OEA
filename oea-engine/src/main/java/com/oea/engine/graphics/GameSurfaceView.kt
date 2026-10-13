package com.oea.engine.graphics

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

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
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val attempts = listOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
            )
            for (attribs in attempts) {
                val count = IntArray(1)
                if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] <= 0) continue
                val configs = arrayOfNulls<EGLConfig>(count[0])
                if (!egl.eglChooseConfig(display, attribs, configs, count[0], count)) continue
                // Prefer an exact colour match (drivers may list deeper formats first).
                val wantRed = attribs[1]
                val exact = configs.filterNotNull().firstOrNull { c ->
                    val v = IntArray(1)
                    egl.eglGetConfigAttrib(display, c, EGL10.EGL_RED_SIZE, v) && v[0] == wantRed
                }
                (exact ?: configs.firstOrNull())?.let { return it }
            }
            Log.e("OeaSurface", "No OpenGL ES 3 configuration available")
            throw IllegalStateException("This device does not provide an OpenGL ES 3.0 surface")
        }
    }

    private companion object {
        /** EGL_OPENGL_ES3_BIT_KHR */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
