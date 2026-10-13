package com.oea.engine.math

import com.oea.engine.math.nd.MatN
import com.oea.engine.math.nd.Polytopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTest {
    @Test fun inverseTimesMatrixIsIdentity() {
        val m = Mat4().setTRS(1f, 2f, 3f, 0.3f, -0.4f, 0.2f, 2f, 0.5f, 1.5f)
        val inv = Mat4().also { assertTrue(it.invert(m)) }
        val id = Mat4().mul(m, inv)
        for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1f else 0f, id.m[i], 1e-4f)
    }

    @Test fun lookAtMapsTargetToNegativeZ() {
        val v = Mat4().lookAt(Vec3(0f, 0f, 5f), Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f))
        val p = v.transformPoint(Vec3(0f, 0f, 0f))
        assertEquals(-5f, p.z, 1e-5f)
    }

    @Test fun rngIsDeterministic() {
        val a = Rng(1234); val b = Rng(1234)
        repeat(1000) { assertEquals(a.nextInt(), b.nextInt()) }
        val c = Rng(99)
        repeat(1000) { val v = c.nextInt(7); assertTrue(v in 0..6) }
    }

    @Test fun quaternionRotation() {
        val q = Quat().setAxisAngle(0f, 1f, 0f, MathX.HALF_PI)
        val v = q.transform(Vec3(0f, 0f, 1f))
        assertEquals(1f, v.x, 1e-5f); assertEquals(0f, v.z, 1e-5f)
    }

    @Test fun polytopes() {
        val tesseract = Polytopes.hypercube(4)
        assertEquals(16, tesseract.vertexCount); assertEquals(32, tesseract.edges.size); assertEquals(24, tesseract.faces.size)
        val cell5 = Polytopes.simplex(4)
        assertEquals(5, cell5.vertexCount); assertEquals(10, cell5.edges.size)
        val cell16 = Polytopes.crossPolytope(4)
        assertEquals(8, cell16.vertexCount); assertEquals(24, cell16.edges.size)
        assertEquals(6, MatN.planeCount(4)); assertEquals(15, MatN.planeCount(6))
    }

    @Test fun rotationsStayOrthonormal() {
        val r = MatN(5)
        repeat(500) { i -> r.rotatePlane(i % 5, (i + 2) % 5, 0.37f) }
        r.orthonormalize()
        for (a in 0 until 5) for (b in 0 until 5) {
            var dot = 0f
            for (k in 0 until 5) dot += r[k, a] * r[k, b]
            assertEquals(if (a == b) 1f else 0f, dot, 1e-3f)
        }
    }
}
