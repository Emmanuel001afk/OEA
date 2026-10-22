package com.oea.launcher.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which camera the flashlight uses, and why it matters.
 *
 * Every torch in OEA used to take `cameraIdList.first()`. On a phone whose first
 * camera is the front one — or a rear camera with no LED — that silently does
 * nothing, and the tile, the gesture and the voice command each disagreed about
 * whether the light was on. This is the decision, on its own, so it can be
 * checked without a phone in hand.
 */
class OeaTorchChoiceTest {

    private fun candidate(id: String, back: Boolean, flash: Boolean) =
        OeaTorch.Candidate(id = id, backFacing = back, hasFlash = flash)

    @Test fun prefersARearCameraWithAFlash() {
        val chosen = OeaTorch.choose(
            listOf(
                candidate("0", back = false, flash = true),   // front, with a screen flash
                candidate("1", back = true, flash = true),
                candidate("2", back = true, flash = true)
            )
        )
        assertEquals("1", chosen?.id)
    }

    @Test fun skipsARearCameraWithoutAnLed() {
        val chosen = OeaTorch.choose(
            listOf(
                candidate("0", back = true, flash = false),   // e.g. an ultra-wide
                candidate("1", back = false, flash = false),
                candidate("2", back = true, flash = true)
            )
        )
        assertEquals("2", chosen?.id)
    }

    @Test fun fallsBackToAnyCameraWithAFlash() {
        val chosen = OeaTorch.choose(
            listOf(
                candidate("0", back = false, flash = true),
                candidate("1", back = false, flash = false)
            )
        )
        assertEquals("0", chosen?.id)
    }

    @Test fun noFlashMeansNoTorch() {
        assertNull(OeaTorch.choose(listOf(candidate("0", back = true, flash = false))))
        assertNull(OeaTorch.choose(emptyList()))
    }
}
