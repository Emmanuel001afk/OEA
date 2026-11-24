package com.oea.engine.graphics

import com.oea.engine.math.Mat4
import com.oea.engine.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The skinned path publishes bind-relative matrices; these guard that contract. */
class SkeletonTest {
    private fun rest(skeleton: Skeleton, hip: Float = 0f, pitch: Float = 0f) {
        skeleton.update(0f, 0f, false, false, 0f, false, pitch, 0f, hip, 0f)
    }

    private fun assertIdentity(m: FloatArray, offset: Int) {
        for (r in 0 until 4) for (c in 0 until 4) {
            val expected = if (r == c) 1f else 0f
            assertEquals("matrix[$r,$c]", expected, m[offset + c * 4 + r], 1e-4f)
        }
    }

    @Test fun proceduralRestPoseLeavesMeshAlone() {
        val skeleton = Skeleton()
        rest(skeleton)
        for (i in 0 until Skeleton.Bone.entries.size) assertIdentity(skeleton.matrices, i * 16)
    }

    @Test fun rootOffsetTranslatesEveryBone() {
        val skeleton = Skeleton()
        rest(skeleton, hip = -0.2f)
        for (i in 0 until Skeleton.Bone.entries.size) {
            assertEquals(-0.2f, skeleton.matrices[i * 16 + 13], 1e-4f)
        }
    }

    @Test fun importedBindPivotsRotateAroundImportedJoint() {
        val inverseBind = FloatArray(Skeleton.Bone.entries.size * 16)
        val head = Skeleton.Bone.HEAD.ordinal
        // Bind head at y = 2.0: inverse bind translates it back to the origin.
        inverseBind[head * 16 + 13] = -2f
        val skeleton = Skeleton(inverseBind)
        rest(skeleton)
        for (i in 0 until Skeleton.Bone.entries.size) assertIdentity(skeleton.matrices, i * 16)

        rest(skeleton, pitch = 0.6f)
        val m = Mat4().set(skeleton.matrices, head * 16)
        val p = m.transformPoint(Vec3(0f, 2.1f, 0f))
        assertTrue("head joint moved to ${p.y}", kotlin.math.abs(p.y - 2.1f) < 0.05f)
        assertTrue("vertex did not orbit the head joint: ${p.z}", kotlin.math.abs(p.z) < 0.1f)
    }
}
