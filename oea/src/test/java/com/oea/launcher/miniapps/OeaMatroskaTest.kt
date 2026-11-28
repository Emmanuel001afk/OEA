package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading subtitles out of a film that carries them inside itself.
 *
 * The fixture is a real Matroska file built a byte at a time in this test — the
 * same element tree a muxer writes, with a video track, two subtitle tracks and
 * one of them forced — so the parser is checked against the format rather than
 * against a file somebody happened to have.
 */
class OeaMatroskaTest {

    // ------------------------------------------------------------ EBML building

    private fun idBytes(id: Long): ByteArray = when {
        id < 0x100L -> byteArrayOf(id.toByte())
        id < 0x10000L -> byteArrayOf((id shr 8).toByte(), id.toByte())
        id < 0x1000000L -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        else -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
    }

    private fun sizeBytes(size: Int): ByteArray {
        var length = 1
        while (length < 8 && size.toLong() >= (1L shl (7 * length)) - 1L) length++
        val out = ByteArray(length)
        var value = size.toLong()
        for (index in length - 1 downTo 0) {
            out[index] = (value and 0xFF).toByte()
            value = value shr 8
        }
        out[0] = (out[0].toInt() or (0x80 shr (length - 1))).toByte()
        return out
    }

    private fun element(id: Long, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { sum, part -> sum + part }
        return idBytes(id) + sizeBytes(body.size) + body
    }

    private fun uint(value: Long): ByteArray {
        var length = 1
        while (length < 8 && value >= (1L shl (8 * length))) length++
        val out = ByteArray(length)
        var remaining = value
        for (index in length - 1 downTo 0) {
            out[index] = (remaining and 0xFF).toByte()
            remaining = remaining shr 8
        }
        return out
    }

    private fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun block(track: Long, relative: Int, body: String): ByteArray {
        val head = byteArrayOf(
            (0x80 or track.toInt()).toByte(),
            (relative shr 8).toByte(),
            relative.toByte(),
            0
        )
        return element(0xA3L, head + text(body))
    }

    private fun trackEntry(
        number: Long,
        type: Long,
        codec: String,
        language: String? = null,
        ietf: String? = null,
        name: String? = null,
        forced: Boolean = false
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts.add(element(0xD7L, uint(number)))
        parts.add(element(0x83L, uint(type)))
        parts.add(element(0x86L, text(codec)))
        if (language != null) parts.add(element(0x22B59CL, text(language)))
        if (ietf != null) parts.add(element(0x22B59DL, text(ietf)))
        if (name != null) parts.add(element(0x536EL, text(name)))
        if (forced) parts.add(element(0x55AAL, uint(1)))
        return element(0xAEL, *parts.toTypedArray())
    }

    private fun cluster(timecode: Long, vararg children: ByteArray): ByteArray =
        element(0x1F43B675L, element(0xE7L, uint(timecode)), *children)

    private fun matroska(segment: ByteArray): ByteArray = element(0x1A45DFA3L) + element(0x18538067L, segment)

    private val tracks = element(
        0x1654AE6BL,
        trackEntry(1, 1, "V_MPEG4/ISO/AVC"),
        trackEntry(2, 0x11, "S_TEXT/UTF8", language = "eng", ietf = "en", name = "English SDH"),
        trackEntry(3, 0x11, "S_TEXT/UTF8", ietf = "fr", forced = true),
        trackEntry(4, 0x11, "S_HDMV/PGS", ietf = "de")
    )

    private fun write(bytes: ByteArray): java.io.File {
        val file = java.io.File.createTempFile("oea-matroska", ".mkv")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun sample(): java.io.File = write(
        matroska(
            tracks +
                cluster(
                    1000L,
                    block(2, 0, "Hello there"),
                    block(2, 2000, "Second line"),
                    // A forced track carries only the foreign dialogue, and a
                    // track with no cues at all is not offered as a track.
                    block(3, 500, "Bonjour")
                ) +
                element(
                    0x1F43B675L,
                    element(0xE7L, uint(10_000L)),
                    element(
                        0xA0L,
                        element(0xA1L, byteArrayOf(0x82.toByte(), 0, 0, 0) + text("Third line")),
                        element(0x9BL, uint(3000L))
                    )
                )
        )
    )

    // ------------------------------------------------------------------- tests

    @Test fun theFileIsRecognisedByItsSignature() {
        assertTrue(OeaMatroska.looksMatroska(sample()))
        val other = write("not a matroska file at all".toByteArray())
        assertTrue(!OeaMatroska.looksMatroska(other))
        assertTrue(OeaMatroska.read(other).isEmpty())
    }

    @Test fun theTextTrackInTheFileBecomesATrack() {
        val found = OeaMatroska.read(sample())
        assertEquals(2, found.size)
        val english = found.first { it.language?.tag == "en" }
        assertEquals(3, english.count)
        assertEquals("Hello there", english.cues[0].text)
        assertEquals(1000L, english.cues[0].startMs)
        assertEquals(3000L, english.cues[0].endMs)
    }

    @Test fun theNameOfTheTrackSaysItIsForHardOfHearing() {
        val english = OeaMatroska.read(sample()).first { it.language?.tag == "en" }
        assertTrue(english.sdh)
        assertTrue(!english.forced)
    }

    @Test fun aBlockDurationIsTheEndOfTheCue() {
        val english = OeaMatroska.read(sample()).first { it.language?.tag == "en" }
        val third = english.cues.last()
        assertEquals(10_000L, third.startMs)
        assertEquals(13_000L, third.endMs)
    }

    @Test fun aCueWithNoDeclaredLengthDoesNotRunAway() {
        val english = OeaMatroska.read(sample()).first { it.language?.tag == "en" }
        val second = english.cues[1]
        assertEquals(3000L, second.startMs)
        // The next cue is eight seconds later; a cue that sits on screen that
        // long is a bug, so the natural length is capped.
        assertTrue("${second.endMs - second.startMs} ms", second.endMs - second.startMs <= 7000L)
    }

    @Test fun aForcedTrackIsMarkedForced() {
        val french = OeaMatroska.read(sample()).first { it.language?.tag == "fr" }
        assertTrue(french.forced)
    }

    @Test fun theBitmapTrackIsLeftAlone() {
        val languages = OeaMatroska.read(sample()).mapNotNull { it.language?.tag }
        assertTrue("found $languages", "de" !in languages)
    }

    @Test fun theVideoTrackIsNotASubtitleTrack() {
        val found = OeaMatroska.read(sample())
        assertEquals(2, found.size)
    }

    @Test fun eachTrackKnowsWhereItCameFromSoItCanBeFoundAgain() {
        OeaMatroska.read(sample()).forEach { track ->
            val origin = track.origin ?: ""
            assertTrue(origin, origin.startsWith(OeaMatroska.ORIGIN_PREFIX))
            assertTrue(origin, origin.contains("#"))
        }
    }

    @Test fun anAssTrackKeepsItsWordsAndLosesItsStyling() {
        val ass = write(
            matroska(
                element(
                    0x1654AE6BL,
                    trackEntry(2, 0x11, "S_TEXT/ASS", ietf = "ja")
                ) +
                    cluster(
                        500L,
                        block(2, 0, "12,0,Default,,0,0,0,,{\\an8}Line one\\NLine two")
                    )
            )
        )
        val track = OeaMatroska.read(ass).single()
        assertEquals("ja", track.language?.tag)
        assertEquals("Line one\nLine two", track.cues.single().text)
    }

    @Test fun aFileNamedLikeAFilmStillProducesReadableTrackNames() {
        val found = OeaMatroska.read(sample())
        found.forEach { track -> assertTrue(track.name, track.name.contains("#")) }
    }
}
