package com.oea.launcher.miniapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading `tx3g` subtitles out of an MP4.
 *
 * The fixture is a real MP4 built a box at a time here: `ftyp`, an `mdat` whose
 * bytes the sample table points at, and a `moov` with one text track and one
 * video track. MP4 gives nothing away by walking — every sample's position is a
 * number in a table — so the test has to lay those tables out the way a muxer
 * does for the parser to have anything to find.
 */
class OeaMp4Test {

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()
    )

    private fun be16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun box(type: String, payload: ByteArray): ByteArray =
        be32(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun fullBox(type: String, payload: ByteArray): ByteArray =
        box(type, byteArrayOf(0, 0, 0, 0) + payload)

    private fun zeros(count: Int): ByteArray = ByteArray(count)

    private fun sample(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return be16(body.size) + body
    }

    /** `mdhd`'s fifteen-bit language: three five-bit letters counting from 'a'. */
    private fun packedLanguage(code: String): Int {
        var packed = 0
        code.forEach { packed = (packed shl 5) or (it.code - 0x60) }
        return packed
    }

    private fun textTrack(
        handler: String,
        timescale: Int,
        deltas: List<Int>,
        offsets: List<Int>,
        sizes: List<Int>,
        language: String = "eng"
    ): ByteArray {
        val mdhd = fullBox(
            "mdhd",
            zeros(4) + zeros(4) + be32(timescale) + be32(deltas.sum()) +
                be16(packedLanguage(language)) + be16(0)
        )
        val hdlr = fullBox("hdlr", zeros(4) + handler.toByteArray() + zeros(12) + byteArrayOf(0))
        val stsd = fullBox("stsd", be32(1) + box("tx3g", zeros(8)))
        val stts = fullBox(
            "stts",
            be32(deltas.size) + deltas.fold(ByteArray(0)) { sum, delta -> sum + be32(1) + be32(delta) }
        )
        val stszFull = box("stsz", byteArrayOf(0, 0, 0, 0) + be32(0) + be32(sizes.size) +
            sizes.fold(ByteArray(0)) { sum, size -> sum + be32(size) })
        val stsc = fullBox("stsc", be32(1) + be32(1) + be32(sizes.size) + be32(1))
        val stco = fullBox("stco", be32(offsets.size) + offsets.fold(ByteArray(0)) { sum, at -> sum + be32(at) })
        val stbl = box("stbl", stsd + stts + stszFull + stsc + stco)
        return box("trak", box("mdia", mdhd + hdlr + box("minf", stbl)))
    }

    private fun videoTrack(): ByteArray {
        val mdhd = fullBox("mdhd", zeros(4) + zeros(4) + be32(90_000) + be32(90_000) + be16(0) + be16(0))
        val hdlr = fullBox("hdlr", zeros(4) + "vide".toByteArray() + zeros(12) + byteArrayOf(0))
        return box("trak", box("mdia", mdhd + hdlr + box("minf", box("stbl", box("stsz", zeros(12))))))
    }

    private fun mp4(samples: List<ByteArray>): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + be32(0) + "isom".toByteArray())
        val payload = samples.fold(ByteArray(0)) { sum, part -> sum + part }
        val mdat = box("mdat", payload)
        // The chunk sits at the first sample's absolute offset.
        val firstOffset = ftyp.size + 8
        val sizes = samples.map { it.size }
        val moov = box(
            "moov",
            textTrack("sbtl", 1000, listOf(2000, 3000), listOf(firstOffset), sizes) + videoTrack()
        )
        return ftyp + mdat + moov
    }

    private fun write(bytes: ByteArray): java.io.File {
        val file = java.io.File.createTempFile("oea-mp4", ".mp4")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun sampleFile(): java.io.File = write(mp4(listOf(sample("Hello there"), sample("Second line"))))

    // ------------------------------------------------------------------- tests

    @Test fun theFileIsRecognisedByItsBox() {
        assertTrue(OeaMp4.looksMp4(sampleFile()))
        val other = write("not an mp4 at all, no ftyp".toByteArray())
        assertTrue(!OeaMp4.looksMp4(other))
        assertTrue(OeaMp4.read(other).isEmpty())
    }

    @Test fun aTextTrackBecomesATrackInItsLanguage() {
        val tracks = OeaMp4.read(sampleFile())
        assertEquals(1, tracks.size)
        assertEquals("en", tracks[0].language?.tag)
        assertEquals(2, tracks[0].count)
        assertEquals("Hello there", tracks[0].cues[0].text)
        assertEquals("Second line", tracks[0].cues[1].text)
    }

    @Test fun theSampleTimesComeFromTheTableNotFromGuessing() {
        val cues = OeaMp4.read(sampleFile()).single().cues
        assertEquals(0L, cues[0].startMs)
        assertEquals(2000L, cues[0].endMs)
        assertEquals(2000L, cues[1].startMs)
        assertEquals(5000L, cues[1].endMs)
    }

    @Test fun theVideoTrackIsNotSubtitleText() {
        assertEquals(1, OeaMp4.read(sampleFile()).size)
    }

    @Test fun anEmbeddedTrackKnowsWhereItCameFrom() {
        val origin = OeaMp4.read(sampleFile()).single().origin ?: ""
        assertTrue(origin, origin.startsWith(OeaMatroska.ORIGIN_PREFIX))
        assertTrue(origin, origin.endsWith("#1"))
    }

    @Test fun aTrackThatSaysNothingAboutItsLanguageStillWorks() {
        val ftyp = box("ftyp", "isom".toByteArray() + be32(0))
        val anonymous = textTrack(
            "sbtl", 1000, listOf(1500), listOf(ftyp.size + 8), listOf(sample("Hi").size), language = "und"
        )
        val file = write(ftyp + box("mdat", sample("Hi")) + box("moov", anonymous))
        val track = OeaMp4.read(file).single()
        assertNull(track.language)
        assertEquals("Hi", track.cues.single().text)
    }
}
