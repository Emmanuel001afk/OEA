package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind cover art, which decides how much of a 4000-pixel cover is
 * decoded before it becomes a 512-pixel icon.
 *
 * This is the part of `OeaVipFrames` that can be checked without a device: the
 * sample factor must always be a power of two (BitmapFactory only honours those),
 * it must bring the long side down to the target without going under it, and a
 * degenerate size — a file whose header says nothing — must decode at full size
 * rather than divide by zero.
 */
class OeaVipFramesTest {

    @Test fun aLargeCoverIsSampledDownToTheTarget() {
        assertEquals(8, OeaVipFrames.sampleSize(4000, 4000, 512))
        assertEquals(8, OeaVipFrames.sampleSize(4000, 3000, 512))
    }

    @Test fun aSmallCoverIsDecodedAsItIs() {
        assertEquals(1, OeaVipFrames.sampleSize(512, 512, 512))
        assertEquals(1, OeaVipFrames.sampleSize(100, 100, 512))
    }

    @Test fun theFactorNeverGoesUnderTheTarget() {
        assertEquals(2, OeaVipFrames.sampleSize(1024, 512, 512))
        assertEquals(4, OeaVipFrames.sampleSize(800, 600, 256))
    }

    @Test fun aNonSquareCoverIsJudgedByItsLongSide() {
        assertEquals(8, OeaVipFrames.sampleSize(300, 4000, 512))
    }

    @Test fun aBrokenSizeStillDecodes() {
        assertEquals(1, OeaVipFrames.sampleSize(0, 4000, 512))
        assertEquals(1, OeaVipFrames.sampleSize(4000, 0, 512))
        assertEquals(1, OeaVipFrames.sampleSize(4000, 4000, 0))
        assertEquals(1, OeaVipFrames.sampleSize(-1, -1, 512))
    }
}
