package com.oea.engine.graphics

import com.oea.engine.math.Colors
import com.oea.engine.math.Frustum
import com.oea.engine.math.Mat4
import com.oea.engine.math.MathX
import com.oea.engine.math.Ray
import com.oea.engine.math.Vec3
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Surface appearance. Colours are sRGB ARGB Ints; the renderer works in
 * linear space with ACES tone mapping, so emissive values above 1 (via
 * [emissiveStrength]) glow and feed bloom.
 */
class Material(
    var color: Int = Colors.WHITE,
    var texture: Texture? = null,
    var emissive: Int = Colors.BLACK,
    var emissiveStrength: Float = 1f,
    var specular: Float = 0.25f,
    var shininess: Float = 32f,
    var rim: Float = 0f,
    var unlit: Boolean = false,
    var transparent: Boolean = false,
    var additive: Boolean = false,
    var doubleSided: Boolean = false,
    var castShadows: Boolean = true,
    var receiveShadows: Boolean = true,
    var fog: Boolean = true,
    var depthWrite: Boolean = true,
    /** Fragments with alpha below this are discarded (foliage, fences). */
    var alphaCutoff: Float = 0f,
    var uvScaleX: Float = 1f,
    var uvScaleY: Float = 1f,
    var uvOffsetX: Float = 0f,
    var uvOffsetY: Float = 0f,
) {
    val id = ids.incrementAndGet()

    fun copy(): Material = Material(color, texture, emissive, emissiveStrength, specular, shininess, rim, unlit, transparent, additive,
        doubleSided, castShadows, receiveShadows, fog, depthWrite, alphaCutoff, uvScaleX, uvScaleY, uvOffsetX, uvOffsetY)

    companion object {
        private val ids = AtomicInteger()
        fun unlit(color: Int, texture: Texture? = null) = Material(color = color, texture = texture, unlit = true, castShadows = false)
        fun glow(color: Int, strength: Float = 2.5f) = Material(color = color, emissive = color, emissiveStrength = strength, castShadows = false)
        fun glass(color: Int) = Material(color = color, transparent = true, specular = 0.9f, shininess = 96f, castShadows = false, depthWrite = false)
    }
}

/** Lighting, sky and fog for a 3D pass. */
class Environment {
    /** Direction towards the sun. */
    val sunDirection = Vec3(-0.45f, 0.82f, -0.35f).nor()
    var sunColor: Int = 0xFFFFF1DC.toInt()
    var sunIntensity = 2.4f
    var skyAmbient: Int = 0xFFA9C8EC.toInt()
    var groundAmbient: Int = 0xFF6E6152.toInt()
    var ambientIntensity = 0.6f
    var drawSky = true
    var skyTop: Int = 0xFF2F6FD0.toInt()
    var skyHorizon: Int = 0xFFC5DCF2.toInt()
    var skyGround: Int = 0xFF7D838C.toInt()
    var sunSize = 1f
    /** Used when [drawSky] is false. */
    var clearColor: Int = 0xFF101418.toInt()
    var fogColor: Int = 0xFFC2D6EC.toInt()
    /** Exponential-squared fog density; 0 disables fog. */
    var fogDensity = 0.006f
    var exposure = 1f
    var shadows = true
    /** Shadow coverage radius around the camera focus. */
    var shadowDistance = 45f
    var shadowStrength = 0.72f

    fun copyFrom(o: Environment): Environment {
        sunDirection.set(o.sunDirection); sunColor = o.sunColor; sunIntensity = o.sunIntensity
        skyAmbient = o.skyAmbient; groundAmbient = o.groundAmbient; ambientIntensity = o.ambientIntensity
        drawSky = o.drawSky; skyTop = o.skyTop; skyHorizon = o.skyHorizon; skyGround = o.skyGround; sunSize = o.sunSize
        clearColor = o.clearColor; fogColor = o.fogColor; fogDensity = o.fogDensity; exposure = o.exposure
        shadows = o.shadows; shadowDistance = o.shadowDistance; shadowStrength = o.shadowStrength
        return this
    }

    companion object {
        fun day() = Environment()
        fun sunset() = Environment().apply {
            sunDirection.set(-0.7f, 0.25f, 0.55f).nor()
            sunColor = 0xFFFFB27A.toInt(); sunIntensity = 2.2f
            skyTop = 0xFF2B3E78.toInt(); skyHorizon = 0xFFFF9E6B.toInt(); skyGround = 0xFF3C3440.toInt()
            skyAmbient = 0xFF8C88C0.toInt(); groundAmbient = 0xFF5A4038.toInt()
            fogColor = 0xFFE8A07C.toInt(); fogDensity = 0.007f
        }
        fun night() = Environment().apply {
            sunDirection.set(0.3f, 0.6f, -0.5f).nor()
            sunColor = 0xFF9DB4FF.toInt(); sunIntensity = 0.9f
            skyTop = 0xFF050814.toInt(); skyHorizon = 0xFF1B2440.toInt(); skyGround = 0xFF06070C.toInt()
            skyAmbient = 0xFF3A4A80.toInt(); groundAmbient = 0xFF141520.toInt(); ambientIntensity = 0.55f
            fogColor = 0xFF141B30.toInt(); fogDensity = 0.012f; sunSize = 0.5f
        }
        fun studio() = Environment().apply {
            drawSky = false; clearColor = 0xFF14171D.toInt(); fogDensity = 0f
            skyAmbient = 0xFFB8C4D8.toInt(); groundAmbient = 0xFF3A3530.toInt(); ambientIntensity = 0.7f
        }
    }
}

/** Base camera: view/projection matrices, frustum and picking helpers. */
abstract class Camera {
    val position = Vec3(0f, 2f, -6f)
    val target = Vec3(0f, 0f, 0f)
    val up = Vec3(0f, 1f, 0f)
    var near = 0.1f
    var far = 600f
    val view = Mat4()
    val projection = Mat4()
    val viewProj = Mat4()
    val invViewProj = Mat4()
    val frustum = Frustum()
    var aspect = 1f
        private set
    private val tmp = Vec3()

    protected abstract fun buildProjection(aspect: Float)

    fun lookAt(x: Float, y: Float, z: Float): Camera { target.set(x, y, z); return this }

    /** Rebuilds all matrices. [rotate180] spins the image for face-to-face split screens. */
    fun update(aspect: Float, rotate180: Boolean = false) {
        this.aspect = aspect
        view.lookAt(position, target, up)
        buildProjection(aspect)
        if (rotate180) {
            val m = projection.m
            for (i in 0 until 4) { m[i * 4] = -m[i * 4]; m[i * 4 + 1] = -m[i * 4 + 1] }
        }
        viewProj.mul(projection, view)
        if (!invViewProj.invert(viewProj)) invViewProj.idt()
        frustum.update(viewProj)
    }

    /** Unit vector the camera looks along. */
    fun forward(out: Vec3): Vec3 = out.set(target).sub(position).nor()
    fun right(out: Vec3): Vec3 = out.set(view.m[0], view.m[4], view.m[8])
    fun upVector(out: Vec3): Vec3 = out.set(view.m[1], view.m[5], view.m[9])

    /** World-space ray through viewport pixel (x, y), origin top-left. */
    fun screenRay(x: Float, y: Float, viewportWidth: Float, viewportHeight: Float, out: Ray): Ray {
        val nx = x / viewportWidth * 2f - 1f
        val ny = 1f - y / viewportHeight * 2f
        tmp.set(nx, ny, -1f); invViewProj.project(tmp)
        val ox = tmp.x; val oy = tmp.y; val oz = tmp.z
        tmp.set(nx, ny, 1f); invViewProj.project(tmp)
        return out.set(ox, oy, oz, tmp.x - ox, tmp.y - oy, tmp.z - oz)
    }

    /** Projects a world point to viewport pixels (top-left origin). Returns false if behind the camera. */
    fun worldToScreen(p: Vec3, viewportWidth: Float, viewportHeight: Float, out: Vec3): Boolean {
        val m = viewProj.m
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        if (w <= 1e-5f) return false
        val cx = (m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]) / w
        val cy = (m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]) / w
        out.set((cx * 0.5f + 0.5f) * viewportWidth, (0.5f - cy * 0.5f) * viewportHeight, w)
        return true
    }
}

class PerspectiveCamera(fovYDegrees: Float = 60f) : Camera() {
    /** Vertical field of view in radians. */
    var fovY = fovYDegrees * MathX.DEG_TO_RAD

    override fun buildProjection(aspect: Float) {
        projection.perspective(fovY, aspect, near, far)
    }

    /** Places the camera on a sphere around [target] (yaw/pitch in radians). */
    fun orbit(yaw: Float, pitch: Float, distance: Float): PerspectiveCamera {
        val cp = cos(pitch)
        position.set(target.x + sin(yaw) * cp * distance, target.y + sin(pitch) * distance, target.z + cos(yaw) * cp * distance)
        return this
    }

    /** Visible height of the frustum at [distance], handy for fitting objects. */
    fun frustumHeightAt(distance: Float): Float = 2f * distance * tan(fovY / 2f)
}

class OrthographicCamera(var halfHeight: Float = 10f) : Camera() {
    override fun buildProjection(aspect: Float) {
        projection.ortho(-halfHeight * aspect, halfHeight * aspect, -halfHeight, halfHeight, near, far)
    }
}

/**
 * 2D camera for sprite worlds: [viewHeight] world units fill the viewport
 * height, Y points up, ([x], [y]) is the centre.
 */
class Camera2D(var viewHeight: Float = 20f) {
    var x = 0f
    var y = 0f
    var zoom = 1f
    var rotation = 0f
    val matrix = Mat4()
    private val inv = Mat4()
    var aspect = 1f
        private set

    fun update(aspect: Float, rotate180: Boolean = false) {
        this.aspect = aspect
        val h = viewHeight / zoom / 2f
        val w = h * aspect
        matrix.ortho(-w, w, -h, h, -1f, 1f)
        if (rotation != 0f || rotate180) {
            val r = Mat4().setTRS(0f, 0f, 0f, roll = rotation + if (rotate180) MathX.PI_F else 0f)
            matrix.mul(r)
        }
        matrix.translate(-x, -y, 0f)
        if (!inv.invert(matrix)) inv.idt()
    }

    /** Viewport pixel (top-left origin) → world coordinates. */
    fun screenToWorld(px: Float, py: Float, viewportWidth: Float, viewportHeight: Float, out: Vec3): Vec3 {
        out.set(px / viewportWidth * 2f - 1f, 1f - py / viewportHeight * 2f, 0f)
        return inv.project(out)
    }
}

/**
 * Part of the screen in normalised coordinates (0..1, origin top-left).
 * [rotate180] renders the viewport upside down for a player sitting opposite.
 */
data class Viewport(val x: Float, val y: Float, val width: Float, val height: Float, val rotate180: Boolean = false) {
    companion object {
        val FULL = Viewport(0f, 0f, 1f, 1f)
        val TOP_HALF = Viewport(0f, 0f, 1f, 0.5f)
        val BOTTOM_HALF = Viewport(0f, 0.5f, 1f, 0.5f)
        val LEFT_HALF = Viewport(0f, 0f, 0.5f, 1f)
        val RIGHT_HALF = Viewport(0.5f, 0f, 0.5f, 1f)
    }
}
