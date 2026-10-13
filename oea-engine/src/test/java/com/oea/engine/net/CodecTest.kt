package com.oea.engine.net

import com.oea.engine.net.lan.LanJoinCode
import com.oea.engine.net.rtc.IceServerConfig
import com.oea.engine.net.rtc.SignalCode
import com.oea.engine.net.rtc.SignalCodeException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class CodecTest {
    private val sdp = "v=0\r\no=- 8340915624113376205 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
        "c=IN IP4 0.0.0.0\r\na=candidate:2930483719 1 udp 2122260223 192.168.43.112 52371 typ host generation 0 network-id 3 network-cost 10\r\n" +
        "a=ice-ufrag:Xp3q\r\na=ice-pwd:Ul8mW2qYbQ0nT9vKc5hR3aZs\r\na=fingerprint:sha-256 7A:91:0C:3E:55:12:A8:F0:9B:CD:4E:21:77:6A:B3:08:1F:E2:94:5D:C6:30:8A:71:0E:B9:24:6F:D3:58:A1:7C\r\n" +
        "a=setup:actpass\r\na=mid:0\r\na=sctp-port:5000\r\na=max-message-size:262144\r\n"
    private val payload = SignalCode.Payload(SignalCode.Kind.INVITE, "chess", 1, 1, 0x1234567890ABCDEFL, 1_760_000_000L, "Ada", sdp)

    @Test fun signalCodeRoundTrip() {
        val code = SignalCode.encode(payload)
        assertTrue(code.startsWith("OEA-"))
        assertEquals(payload, SignalCode.decode(code))
        // Messengers may lowercase, wrap or group it, and add words around it.
        assertEquals(payload, SignalCode.decode("Join me: " + code.lowercase().chunked(7).joinToString(" ") + " thanks"))
        assertEquals(payload, SignalCode.decode("OEA-" + Codes.group(code.removePrefix("OEA-")) + " OK BYE"))
    }

    @Test fun damagedCodesAreRejected() {
        val code = SignalCode.encode(payload)
        val i = code.length - 9
        val bad = code.substring(0, i) + (if (code[i] == 'A') 'B' else 'A') + code.substring(i + 1)
        for (text in listOf(bad, code.substring(0, code.length / 2), "hello", "OEAL-AAAA")) {
            try { SignalCode.decode(text); fail("accepted: $text") } catch (_: SignalCodeException) { }
        }
    }

    @Test fun base32() {
        val data = ByteArray(200) { (it * 37 + 11).toByte() }
        assertArrayEquals(data, Codes.base32Decode(Codes.base32(data)))
        assertArrayEquals(byteArrayOf(0x66, 0x6F, 0x6F), Codes.base32Decode("mzxw 6"))
    }

    @Test fun byteWriterRoundTrip() {
        val w = ByteWriter().u8(200).u16(65000).i32(-5).i64(Long.MIN_VALUE).f32(3.5f).varint(300).varint(Int.MAX_VALUE).svarint(-12345).string("héllo").q16(0.25f, -1f, 1f).angle(1f)
        val r = ByteReader(w.toByteArray())
        assertEquals(200, r.u8()); assertEquals(65000, r.u16()); assertEquals(-5, r.i32()); assertEquals(Long.MIN_VALUE, r.i64())
        assertEquals(3.5f, r.f32(), 0f); assertEquals(300, r.varint()); assertEquals(Int.MAX_VALUE, r.varint()); assertEquals(-12345, r.svarint())
        assertEquals("héllo", r.string()); assertEquals(0.25f, r.q16(-1f, 1f), 1e-4f); assertEquals(1f, r.angle(), 1e-3f)
        assertEquals(0, r.remaining)
        try { r.u8(); fail("read past the end") } catch (_: NetFormatException) { }
    }

    @Test fun lanJoinCode() {
        val addr = InetAddress.getByName("192.168.43.1") as java.net.Inet4Address
        val code = LanJoinCode.encode(listOf(addr), 47812, 42L, "runner")
        val t = LanJoinCode.decode(code).getOrThrow()
        assertEquals(listOf("192.168.43.1"), t.addresses)
        assertEquals(47812, t.port)
        assertEquals("runner", t.gameId)
    }

    @Test fun iceServerParsing() {
        val list = IceServerConfig.parseList("stun:stun.example.net:3478\n# comment\nturn:turn.example.net:3478|user|secret\nnot a server")
        assertEquals(2, list.size)
        assertTrue(list[1].isTurn)
        assertEquals(list, IceServerConfig.parseList(IceServerConfig.formatList(list)))
    }
}
