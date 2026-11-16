package com.oea.launcher.security

import org.junit.Assert.*
import org.junit.Test

class OeaSignatureEngineTest {
    @Test fun eicarIsTestNotMalware() {
        val test = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"
        val result = OeaSignatureEngine.scan(test.byteInputStream(), emptySet())
        assertTrue(result.testFile)
        assertFalse(result.matched)
        assertEquals(OeaSignatureEngine.EICAR_SHA256, result.sha256)
    }

    @Test fun signaturesRequireExactHashAndInvalidLinesAreIgnored() {
        val content = "example".toByteArray()
        val hash = OeaSignatureEngine.scan(content.inputStream(), emptySet()).sha256
        val db = OeaSignatureEngine.parse("# trusted list\n${hash.uppercase()} label\n$hash\nnot-a-hash\n")
        assertEquals(1, db.hashes.size)
        assertEquals(1, db.ignored)
        assertTrue(OeaSignatureEngine.scan(content.inputStream(), db.hashes).matched)
        assertFalse(OeaSignatureEngine.scan("different".byteInputStream(), db.hashes).matched)
    }

    @Test fun inputFailureIsNotReportedAsClean() {
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("unreadable")
        }
        try { OeaSignatureEngine.scan(broken, emptySet()); fail("Expected IO failure") }
        catch (_: java.io.IOException) { }
    }
}
