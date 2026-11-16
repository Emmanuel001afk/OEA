package com.oea.launcher.security

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Exact-file SHA-256 matching. A hash match is evidence against the imported list,
 * not a verdict from OEA; unmatched files may still be malicious. */
object OeaSignatureEngine {
    // EICAR is a harmless standard antivirus test file, not actual malware.
    const val EICAR_SHA256 = "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f"
    private val hashPattern = Regex("^[0-9a-f]{64}$")
    const val MAX_SIGNATURES = 200_000
    const val MAX_DATABASE_BYTES = 20 * 1024 * 1024

    data class Database(val hashes: Set<String>, val ignored: Int)
    data class Result(val sha256: String, val testFile: Boolean, val matched: Boolean)

    /** Plain text: SHA-256 at the start of each line, optionally followed by a label.
     * Invalid lines are counted, never treated as signatures. */
    fun parse(text: String): Database {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_DATABASE_BYTES) { "Signature list is too large" }
        val hashes = HashSet<String>()
        var ignored = 0
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val hash = trimmed.split(Regex("\\s+"), limit = 2)[0].lowercase(Locale.ROOT)
                if (hashPattern.matches(hash)) {
                    hashes.add(hash)
                    require(hashes.size <= MAX_SIGNATURES) { "Too many signatures" }
                } else ignored++
            }
        }
        return Database(hashes, ignored)
    }

    fun scan(input: InputStream, signatures: Set<String>): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Scan cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Result(hash, hash == EICAR_SHA256, hash in signatures)
    }
}
