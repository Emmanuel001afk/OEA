package com.oea.engine.net.rtc

import com.oea.engine.net.ByteReader
import com.oea.engine.net.ByteWriter
import com.oea.engine.net.Codes
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A STUN/TURN server the user runs themselves (for example coturn). OEA ships
 * with none: online play never touches a third-party server unless the user
 * adds one here.
 */
data class IceServerConfig(val url: String, val username: String = "", val credential: String = "") {
    val isTurn get() = url.startsWith("turn:", ignoreCase = true) || url.startsWith("turns:", ignoreCase = true)

    companion object {
        /** One server per line: `url` or `url|username|credential`. */
        fun parseList(text: String): List<IceServerConfig> = text.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split('|')
            val url = parts[0].trim()
            if (!isValidUrl(url)) return@mapNotNull null
            IceServerConfig(url, parts.getOrNull(1)?.trim().orEmpty(), parts.getOrNull(2)?.trim().orEmpty())
        }

        fun formatList(list: List<IceServerConfig>): String = list.joinToString("\n") {
            if (it.username.isEmpty() && it.credential.isEmpty()) it.url else "${it.url}|${it.username}|${it.credential}"
        }

        fun isValidUrl(url: String): Boolean =
            Regex("^(stun|stuns|turn|turns):[^\\s|]+$", RegexOption.IGNORE_CASE).matches(url)
    }
}

/** Raised for codes that can't be used, with a message meant for the player. */
class SignalCodeException(message: String) : Exception(message)

/**
 * Turns a WebRTC session description into a short, shareable code and back.
 *
 * Layout: version, kind (invite/reply), protocol, game version, session
 * nonce, creation time, game id, player name, then the SDP compressed with a
 * deflate dictionary of typical WebRTC text, and a CRC32 over everything.
 * The text form is `OEA-` + Base32 (letters and digits only), which survives
 * messengers, is case-insensitive and packs densely into a QR code.
 */
object SignalCode {
    const val PREFIX = "OEA-"
    private const val VERSION = 1
    /** Invites older than this are rejected with a clear message. */
    const val MAX_AGE_SECONDS = 2 * 60 * 60L

    enum class Kind { INVITE, REPLY }

    data class Payload(
        val kind: Kind,
        val gameId: String,
        val gameVersion: Int,
        val protocol: Int,
        val nonce: Long,
        val createdAtSeconds: Long,
        val playerName: String,
        val sdp: String,
    )

    fun looksLikeCode(text: String): Boolean {
        val t = text.trim().uppercase()
        return t.startsWith(PREFIX) && !t.startsWith("OEAL-") && t.length > 40
    }

    /** Finds a code inside a longer shared message ("Join my game: OEA-…"). */
    fun extract(text: String): String? = candidates(text).firstOrNull()

    /**
     * Possible codes inside [text], longest first. Codes are upper-case and may
     * be grouped with spaces; trailing words of a message are tried off.
     */
    private fun candidates(text: String): List<String> {
        val m = Regex("OEA-[A-Z2-7]{4,}(?:[ \\n\\-]+[A-Z2-7]{1,8})*").find(text)
            ?: Regex("OEA-[A-Z2-7]{4,}(?:[ \\n\\-]+[A-Z2-7]{1,8})*", RegexOption.IGNORE_CASE).find(text)
            ?: return emptyList()
        val groups = m.value.trim().split(Regex("[ \\n\\-]+")).filter { it.isNotEmpty() }
        // groups[0] == "OEA"
        return (0..minOf(3, groups.size - 2)).map { drop -> "OEA-" + groups.subList(1, groups.size - drop).joinToString("") }
    }

    fun encode(p: Payload): String {
        val w = ByteWriter(512)
            .u8(VERSION).u8(p.kind.ordinal).u16(p.protocol).u16(p.gameVersion)
            .i64(p.nonce).i32(p.createdAtSeconds.toInt())
            .string(p.gameId).string(p.playerName.take(24))
        w.bytes(deflate(p.sdp.toByteArray(Charsets.UTF_8)))
        val body = w.toByteArray()
        val all = ByteWriter(body.size + 4).bytes(body).i32(Codes.crc32(body)).toByteArray()
        return PREFIX + Codes.base32(all)
    }

    /** Parses a code, throwing [SignalCodeException] with a human explanation when it can't be used. */
    fun decode(text: String): Payload {
        val options = candidates(text).ifEmpty { listOf(text) }
        var first: SignalCodeException? = null
        for (option in options) {
            try { return decodeOne(option) } catch (e: SignalCodeException) { if (first == null) first = e }
        }
        throw first ?: SignalCodeException("That isn't an OEA online code.")
    }

    private fun decodeOne(found: String): Payload {
        val cleaned = found.trim().uppercase()
        if (!cleaned.startsWith(PREFIX)) {
            if (cleaned.startsWith("OEAL-")) throw SignalCodeException("That's a Wi-Fi/hotspot code. Use \"Join nearby\" for it.")
            throw SignalCodeException("That isn't an OEA online code. Codes start with OEA-.")
        }
        val raw = Codes.base32Decode(cleaned.removePrefix(PREFIX))
            ?: throw SignalCodeException("The code contains characters that don't belong to it. Copy it again exactly as sent.")
        if (raw.size < 24) throw SignalCodeException("The code is too short — part of it is missing. Copy the whole code.")
        val crc = ByteReader(raw, raw.size - 4).i32()
        if (Codes.crc32(raw, 0, raw.size - 4) != crc) {
            throw SignalCodeException("The code is incomplete or was changed while copying. Ask your friend to send it again (or scan the QR).")
        }
        try {
            val r = ByteReader(raw, 0, raw.size - 4)
            val version = r.u8()
            if (version != VERSION) throw SignalCodeException("This code comes from a different OEA version. Update OEA on both phones.")
            val kind = Kind.entries.getOrNull(r.u8()) ?: throw SignalCodeException("Unknown code type.")
            val protocol = r.u16()
            val gameVersion = r.u16()
            val nonce = r.i64()
            val created = r.i32().toLong() and 0xFFFFFFFFL
            val gameId = r.string(64)
            val name = r.string(96)
            val sdp = String(inflate(r.rest()), Charsets.UTF_8)
            if (!sdp.startsWith("v=0")) throw SignalCodeException("The code is damaged.")
            return Payload(kind, gameId, gameVersion, protocol, nonce, created, name, sdp)
        } catch (e: SignalCodeException) {
            throw e
        } catch (e: Exception) {
            throw SignalCodeException("The code is damaged. Ask for a new one.")
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setDictionary(DICTIONARY)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setDictionary(DICTIONARY)
        // Raw deflate needs one extra dummy byte at the end of the input.
        inf.setInput(data + byteArrayOf(0))
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(2048)
        var guard = 0
        while (!inf.finished() && guard++ < 512) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024) throw SignalCodeException("The code is damaged.")
        }
        inf.end()
        return out.toByteArray()
    }

    /** Typical libwebrtc data-channel SDP; deflate references it to shrink codes a lot. */
    private val DICTIONARY: ByteArray = (
        "a=candidate:1 1 udp 1686052607 100.64.0.1 3478 typ srflx raddr 10.0.0.2 rport 9 generation 0 network-id 3 network-cost 900\r\n" +
            "a=candidate:2 1 udp 41885439 198.51.100.7 3478 typ relay raddr 203.0.113.5 rport 46243 generation 0 network-id 1 network-cost 10\r\n" +
            "a=candidate:3 1 udp 2122262783 2001:db8::1 46244 typ host generation 0 network-id 2 network-cost 10\r\n" +
            "a=candidate:4 1 udp 2122194687 10.0.0.2 46245 typ host generation 0 network-id 3 network-cost 900\r\n" +
            "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=extmap-allow-mixed\r\n" +
            "a=msid-semantic: WMS\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\n" +
            "a=ice-ufrag:abcd\r\na=ice-pwd:abcdefghijklmnopqrstuvwx\r\na=ice-options:trickle renomination\r\n" +
            "a=fingerprint:sha-256 00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF\r\n" +
            "a=setup:actpass\r\na=setup:active\r\na=mid:0\r\na=sctp-port:5000\r\na=max-message-size:262144\r\n" +
            "a=candidate:1467250027 1 udp 2122260223 192.168.1.10 46243 typ host generation 0 network-id 1 network-cost 10\r\n"
        ).toByteArray(Charsets.UTF_8)
}
