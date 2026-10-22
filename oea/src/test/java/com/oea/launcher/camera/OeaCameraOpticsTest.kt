package com.oea.launcher.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera maths, which used to be guessed in the middle of a 1,300-line view.
 *
 * These are the numbers that were wrong before: the preview was drawn raw (no
 * rotation, stretched to the window), the crop ignored the requested shape, and
 * the Pro focus slider pushed the lens to infinity when the user asked for close.
 */
class OeaCameraOpticsTest {

    @Test fun rearCameraIsTurnedAgainstTheDisplay() {
        // A typical phone: sensor mounted at 90°, portrait window.
        assertEquals(90, OeaCameraOptics.rotationDegrees(sensorOrientation = 90, deviceRotationDegrees = 0, frontFacing = false))
        // Landscape, turned the other way: the buffer needs no rotation at all.
        assertEquals(0, OeaCameraOptics.rotationDegrees(90, 90, frontFacing = false))
        assertEquals(270, OeaCameraOptics.rotationDegrees(90, 180, frontFacing = false))
        assertEquals(180, OeaCameraOptics.rotationDegrees(90, 270, frontFacing = false))
    }

    @Test fun frontCameraAddsInsteadOfSubtracting() {
        assertEquals(90, OeaCameraOptics.rotationDegrees(270, 180, frontFacing = true))
        assertEquals(0, OeaCameraOptics.rotationDegrees(270, 90, frontFacing = true))
    }

    @Test fun rotationIsAlwaysAQuarterTurn() {
        val all = listOf(0, 90, 180, 270)
        for (sensor in all) {
            for (device in all) {
                for (front in listOf(true, false)) {
                    val rotation = OeaCameraOptics.rotationDegrees(sensor, device, front)
                    assertTrue("$rotation is not a quarter turn", rotation % 90 == 0)
                    assertTrue("$rotation is out of range", rotation in 0..270)
                }
            }
        }
    }

    @Test fun exifTagMatchesTheRotationItDescribes() {
        assertEquals(6, OeaCameraOptics.exifOrientation(90, 0, frontFacing = false))
        assertEquals(3, OeaCameraOptics.exifOrientation(90, 180, frontFacing = false))
        assertEquals(8, OeaCameraOptics.exifOrientation(90, 270, frontFacing = false))
        assertEquals(1, OeaCameraOptics.exifOrientation(90, 90, frontFacing = false))
    }

    @Test fun aspectSizePrefersTheBiggestMatchingShape() {
        val sizes = listOf(
            640 to 480,
            1280 to 720,
            1920 to 1080,
            4032 to 3024,
            1600 to 1200
        )
        assertEquals(4032 to 3024, OeaCameraOptics.bestAspectSize(sizes, 4f / 3f))
        assertEquals(1920 to 1080, OeaCameraOptics.bestAspectSize(sizes, 16f / 9f))
        // A square request has no exact answer here, so the closest shape wins
        // instead of returning nothing.
        assertEquals(1600 to 1200, OeaCameraOptics.bestAspectSize(sizes, 1f))
    }

    @Test fun aspectSizeRespectsThePixelBudget() {
        val sizes = listOf(1920 to 1080, 3840 to 2160, 1280 to 720)
        assertEquals(1280 to 720, OeaCameraOptics.bestAspectSize(sizes, 16f / 9f, maxPixels = 1920L * 1080L))
        // "Full" means "the biggest the budget allows", not "no picture".
        assertEquals(1920 to 1080, OeaCameraOptics.bestAspectSize(sizes, 0f, maxPixels = 1920L * 1080L))
        assertNull(OeaCameraOptics.bestAspectSize(emptyList(), 4f / 3f))
    }

    @Test fun coverScaleCropsInsteadOfStretching() {
        // A 16:9 buffer in a tall window must be scaled until its short side fills
        // the window's width — the window never gets a squashed picture.
        val scale = OeaCameraOptics.coverScale(
            viewWidth = 1080f,
            viewHeight = 1920f,
            bufferWidth = 1280f,
            bufferHeight = 720f,
            rotationDegrees = 90
        )
        // Rotated, the buffer is 720 wide by 1280 tall; width needs 1.5.
        assertEquals(1.5f, scale, 0.0001f)
    }

    @Test fun coverScaleHandlesMissingMeasurements() {
        assertEquals(1f, OeaCameraOptics.coverScale(0f, 0f, 1280f, 720f, 90))
        assertEquals(1f, OeaCameraOptics.coverScale(1080f, 1920f, 0f, 0f, 90))
    }

    @Test fun cropTakesTheWantedShapeFromTheMiddle() {
        // 4:3 sensor frame asked for 16:9: trim top and bottom, keep full width.
        val wide = OeaCameraOptics.cropRect(4000, 3000, 16f / 9f)
        assertEquals(intArrayOf(0, 375, 4000, 2625), wide)

        // 4:3 frame asked for 1:1: trim the sides, keep full height.
        val square = OeaCameraOptics.cropRect(4000, 3000, 1f)
        assertEquals(intArrayOf(500, 0, 3500, 3000), square)

        // Already the right shape: untouched.
        val same = OeaCameraOptics.cropRect(4000, 3000, 4f / 3f)
        assertEquals(intArrayOf(0, 0, 4000, 3000), same)
    }

    @Test fun cropNeverExceedsTheFrame() {
        val rect = OeaCameraOptics.cropRect(1000, 1000, 21f / 9f)
        assertTrue(rect[0] >= 0)
        assertTrue(rect[1] >= 0)
        assertTrue(rect[2] <= 1000)
        assertTrue(rect[3] <= 1000)
        assertTrue(rect[2] > rect[0])
        assertTrue(rect[3] > rect[1])
    }

    @Test fun focusSliderMeansCloserNotFurther() {
        val near = 10f // dioptres, i.e. 10 cm
        assertEquals(0f, OeaCameraOptics.focusDioptres(near, 0), 0.0001f)
        assertEquals(2.5f, OeaCameraOptics.focusDioptres(near, 25), 0.0001f)
        assertEquals(near, OeaCameraOptics.focusDioptres(near, 100), 0.0001f)
        // A lens that cannot focus manually must not be handed a fabricated value.
        assertEquals(0f, OeaCameraOptics.focusDioptres(0f, 100), 0.0001f)
    }

    @Test fun aspectHelpersSurviveRubbish() {
        assertTrue(OeaCameraOptics.matchesAspect(1280, 720, 0f))
        assertEquals(1f, OeaCameraOptics.aspectOf(0, 0), 0.0001f)
        assertTrue(OeaCameraOptics.matchesAspect(1280, 720, 16f / 9f))
    }
}
