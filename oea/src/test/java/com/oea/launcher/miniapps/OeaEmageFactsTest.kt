package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The numbers EMAGE prints about a photo, and the size it decodes it at.
 *
 * The detail sheet is where a picture app is most quietly wrong: a shutter speed of
 * `1/59 s`, a focal length of `50/1 mm`, a manual white balance called "Auto", a 48 MP
 * photo that cannot be opened at all. None of that crashes; all of it is arithmetic.
 */
class OeaEmageFactsTest {

    // ------------------------------------------------------------------ decodes

    @Test
    fun aFortyEightMegapixelPhotoIsNotDecodedAtFullSize() {
        // 8000×6000 in ARGB_8888 is 192 MB. The old rule's own 4096 ceiling actually
        // allowed 8192 because it halved before comparing, so this decoded at full size.
        val sample = OeaEmageFacts.sampleSize(8000, 6000, OeaEmageFacts.EDIT_EDGE)
        assertEquals(4, sample)
        val decoded = (8000 / sample) * (6000 / sample) * 4L
        assertTrue("decoded ${decoded / 1_000_000} MB is too large", decoded < 40L * 1000 * 1000)
    }

    @Test
    fun fitModeKeepsTheLongEdgeUnderTheTarget() {
        assertEquals(1, OeaEmageFacts.sampleSize(4000, 3000, 4096))
        assertEquals(2, OeaEmageFacts.sampleSize(6000, 4000, 3000))
        assertEquals(4, OeaEmageFacts.sampleSize(8000, 6000, 3000))
        // A panorama is limited by its long edge, which is exactly why fit uses it.
        assertEquals(4, OeaEmageFacts.sampleSize(10800, 800, 4096))
        assertTrue(10800 / OeaEmageFacts.sampleSize(10800, 800, 4096) <= 4096)
    }

    @Test
    fun coverModeKeepsTheShortEdgeAtTheTarget() {
        // A square tile is cropped, so the short edge is the one that must still fit.
        assertEquals(2, OeaEmageFacts.sampleSize(1080, 1920, 300, cover = true))
        assertEquals(2, OeaEmageFacts.sampleSize(4000, 3000, 1080, cover = true))
        // This panorama is already narrower than the screen height: no sampling at all.
        assertEquals(1, OeaEmageFacts.sampleSize(8000, 1000, 1080, cover = true))
    }

    @Test
    fun theFitAndCoverRulesDisagreeWhereTheyShould() {
        assertEquals(8, OeaEmageFacts.sampleSize(1080, 1920, 300))
        assertEquals(2, OeaEmageFacts.sampleSize(1080, 1920, 300, cover = true))
    }

    @Test
    fun nonsenseSizesDecodeWhole() {
        assertEquals(1, OeaEmageFacts.sampleSize(0, 0, 300))
        assertEquals(1, OeaEmageFacts.sampleSize(-4, 900, 300))
        assertEquals(1, OeaEmageFacts.sampleSize(900, 900, 0))
        assertEquals(2, OeaEmageFacts.sampleSize(400, 400, 300))
    }

    // -------------------------------------------------------------------- sizes

    @Test
    fun smallFilesAreCountedInBytes() {
        assertEquals("—", OeaEmageFacts.size(0))
        assertEquals("—", OeaEmageFacts.size(-5))
        assertEquals("512 B", OeaEmageFacts.size(512))
        // 1023 bytes used to round up to "1 KB".
        assertEquals("1023 B", OeaEmageFacts.size(1023))
    }

    @Test
    fun theUnitFollowsTheRoundedValue() {
        assertEquals("1 KB", OeaEmageFacts.size(1024))
        assertEquals("12 KB", OeaEmageFacts.size(12L * 1024))
        // 1 048 500 bytes used to read "1024 KB".
        assertEquals("1.0 MB", OeaEmageFacts.size(1_048_500))
        assertEquals("3.0 MB", OeaEmageFacts.size(3L * 1024 * 1024))
        assertEquals("2.34 GB", OeaEmageFacts.size((2.34 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun sizesFollowTheDeviceLanguageButNeverLoseTheirUnit() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1,4 MB", OeaEmageFacts.size((1.44 * 1024 * 1024).toLong()))
        } finally {
            Locale.setDefault(previous)
        }
        assertEquals("12.0 MP", OeaEmageFacts.megapixels(4000, 3000))
        assertEquals("", OeaEmageFacts.megapixels(0, 3000))
    }

    // ----------------------------------------------------------------- shutter

    @Test
    fun aSixtiethOfASecondIsOneSixtieth() {
        // (1 / 0.0166666666).toInt() is 59: the old label was off by one on the most
        // common shutter speed there is.
        assertEquals("1/60 s", OeaEmageFacts.shutter("0.01666666666"))
        assertEquals("1/125 s", OeaEmageFacts.shutter("0.008"))
        assertEquals("1/8000 s", OeaEmageFacts.shutter("0.000125"))
    }

    @Test
    fun aFractionStoredAsTextKeepsItsUnit() {
        // Most files store "1/250"; the old sheet printed that branch without " s".
        assertEquals("1/250 s", OeaEmageFacts.shutter("1/250"))
        assertEquals("1/2 s", OeaEmageFacts.shutter("1/2"))
    }

    @Test
    fun slowShuttersAreWrittenInSeconds() {
        assertEquals("0.5 s", OeaEmageFacts.shutter("0.5"))
        assertEquals("1 s", OeaEmageFacts.shutter("1"))
        assertEquals("2.5 s", OeaEmageFacts.shutter("2.5"))
        assertEquals("30 s", OeaEmageFacts.shutter("30"))
    }

    @Test
    fun aMissingShutterIsADash() {
        assertEquals("—", OeaEmageFacts.shutter(""))
        assertEquals("—", OeaEmageFacts.shutter("0"))
        assertEquals("—", OeaEmageFacts.shutter("—"))
        assertEquals("—", OeaEmageFacts.shutter("1/0"))
    }

    // ------------------------------------------------------------- the lens line

    @Test
    fun apertureIsWrittenAsCamerasWriteIt() {
        assertEquals("f/1.8", OeaEmageFacts.aperture("1.8"))
        assertEquals("f/1.8", OeaEmageFacts.aperture("18/10"))
        assertEquals("f/2", OeaEmageFacts.aperture("2/1"))
        assertEquals("f/1.8", OeaEmageFacts.aperture("f/1.8"))
        assertEquals("—", OeaEmageFacts.aperture(""))
    }

    @Test
    fun aRationalFocalLengthIsNotPrintedAsOne() {
        // The old sheet said "50/1 mm".
        assertEquals("50 mm", OeaEmageFacts.focalLength("", "50/1"))
        assertEquals("3.5 mm", OeaEmageFacts.focalLength("", "35/10"))
        assertEquals("28 mm", OeaEmageFacts.focalLength("28", "28/1"))
        assertEquals("28 mm", OeaEmageFacts.focalLength("28 mm", ""))
        assertEquals("—", OeaEmageFacts.focalLength("", ""))
        assertEquals("—", OeaEmageFacts.focalLength("", "7/0"))
    }

    @Test
    fun isoIsLabelledOnceAndDroppedWhenUnknown() {
        assertEquals("ISO 100", OeaEmageFacts.iso("100"))
        assertEquals("—", OeaEmageFacts.iso(""))
    }

    @Test
    fun theExposureLineDropsWhatTheFileDoesNotHave() {
        assertEquals("1/250 s • f/1.8 • ISO 100", OeaEmageFacts.exposureLine("1/250 s", "f/1.8", "ISO 100"))
        assertEquals("f/1.8 • ISO 100", OeaEmageFacts.exposureLine("—", "f/1.8", "ISO 100"))
        assertEquals("1/250 s", OeaEmageFacts.exposureLine("1/250 s", "—", "—"))
        // A scan or a screenshot has no camera block at all: say that, not "— • — • —".
        assertEquals("No camera settings recorded", OeaEmageFacts.exposureLine("—", "—", "—"))
        assertEquals("No camera settings recorded", OeaEmageFacts.exposureLine("", " ", ""))
    }

    // ----------------------------------------------------------------- location

    @Test
    fun coordinatesAlwaysUseADot() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // The old label was "%.5f, %.5f".format(...), which in German is
            // "6,52400, 3,37900" — not a coordinate any map accepts, and what the
            // "Coordinates copied" row put on the clipboard.
            assertEquals("6.52400, 3.37900", OeaEmageFacts.coordinates(6.5240, 3.3790))
            assertEquals("-33.86882, 151.20929", OeaEmageFacts.coordinates(-33.86882, 151.20929))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun altitudeIsAHeightNotAGap() {
        assertEquals("12 m", OeaEmageFacts.altitude(12.4))
        assertEquals("-4 m", OeaEmageFacts.altitude(-4.2))
        assertEquals("0 m", OeaEmageFacts.altitude(0.0))
        assertNull(OeaEmageFacts.altitude(null))
        assertNull(OeaEmageFacts.altitude(Double.NaN))
    }

    // ------------------------------------------------------------- the EXIF codes

    @Test
    fun whiteBalanceIsAutomaticOrManualAndNothingElse() {
        // The old table read the light-source codes here and called a manual white
        // balance "Auto"; 9 is not a value this tag has.
        assertEquals("Auto", OeaEmageFacts.whiteBalance(0))
        assertEquals("Manual", OeaEmageFacts.whiteBalance(1))
        assertEquals("—", OeaEmageFacts.whiteBalance(9))
        assertEquals("—", OeaEmageFacts.whiteBalance(-1))
    }

    @Test
    fun everyMeteringModeHasAWord() {
        assertEquals("Average", OeaEmageFacts.metering(1))
        assertEquals("Centre weighted", OeaEmageFacts.metering(2))
        assertEquals("Spot", OeaEmageFacts.metering(3))
        // 4 and 6 used to fall through to "—".
        assertEquals("Multi spot", OeaEmageFacts.metering(4))
        assertEquals("Pattern", OeaEmageFacts.metering(5))
        assertEquals("Partial", OeaEmageFacts.metering(6))
        assertEquals("Other", OeaEmageFacts.metering(255))
        assertEquals("—", OeaEmageFacts.metering(0))
        assertEquals("—", OeaEmageFacts.metering(-1))
    }

    @Test
    fun printableGuardsTheSheetAgainstNonsense() {
        assertTrue(OeaEmageFacts.printable(6.5))
        assertEquals(false, OeaEmageFacts.printable(null))
        assertEquals(false, OeaEmageFacts.printable(Double.NaN))
        assertEquals(false, OeaEmageFacts.printable(Double.POSITIVE_INFINITY))
    }
}
