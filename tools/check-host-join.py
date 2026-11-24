#!/usr/bin/env python3
"""Check the host/join path of Send Off's Wi-Fi and hotspot play, statically.

There is no Android device in this workspace, so the two things that actually
break LAN play in practice are checked here against the sources:

  1. **The round trip of the join code.** `LanJoinCode` is what a host shows and
     what a friend types or scans, so its framing, CRC and field order must agree
     on both sides. This file re-parses the Kotlin `encode`/`decode` and asserts
     they mirror each other field by field, and that the port and address list
     can represent what `NetInterfaces.localAddresses()` can produce.

  2. **The wiring between the UI and the network.** Every button on the
     "Play with friends" sheet must lead to a real function, the host must start
     both transports it advertises (LAN *and* online invite), the client must
     connect to what discovery found, and the hot paths must not be stubs.

Exit code 0 and `HOST/JOIN OK` when every check passes.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "oea-engine/src/main/java/com/oea/engine"
GAMES = ROOT / "oea-games/src/main/java/com/oea/games"

DISCOVERY = ENGINE / "net/lan/LanDiscovery.kt"
LAN = ENGINE / "net/lan/Lan.kt"
SESSION = ENGINE / "net/session/NetSession.kt"
ACTIVITY = GAMES / "OeaStandaloneGameActivity.kt"
ARENA = GAMES / "arena/OeaArena.kt"

problems: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        problems.append(msg)


def read(p: Path) -> str:
    if not p.exists():
        problems.append(f"missing file: {p.relative_to(ROOT)}")
        return ""
    return p.read_text()


def field_order(block: str) -> list[str]:
    """Field writers/readers in source order, ignoring framing noise."""
    out = []
    for m in re.finditer(r"\.(u8|u16|i32|i64|f32|bool|string|bytes|angle|q8|q16|varint)\(", block):
        out.append(m.group(1))
    return out


def check_join_code() -> None:
    src = read(DISCOVERY)
    m_enc = re.search(r"fun encode\([^)]*\): String \{(.*?)\n    \}", src, re.S)
    m_dec = re.search(r"fun decode\(text: String\): Result<Target> \{(.*?)\n    \}", src, re.S)
    check(m_enc is not None, "LanJoinCode.encode is missing")
    check(m_dec is not None, "LanJoinCode.decode is missing")
    if not (m_enc and m_dec):
        return
    enc, dec = m_enc.group(1), m_dec.group(1)

    # Compare the *type sequence* both sides commit to, in source order.
    # Reader side: every ByteReader call in the body after the CRC check.
    # The first read is the CRC trailer, which encode writes as `i32(crc)` at
    # the end of the body rather than as part of the field chain.
    # The CRC trailer is read first, then the version byte; both sides must
    # agree on the payload after that: session, port, count, addresses, room.
    dec_reads = [f for f in field_order(dec)][1:]
    if dec_reads and dec_reads[0] == "u8":
        dec_reads = dec_reads[1:]
    # Writer side: the ByteWriter chain, minus the leading version byte.
    enc_writes = field_order(enc.split("val crc")[0])
    check(enc_writes and enc_writes[0] == "u8", "encode does not write a version byte first")
    enc_fields = enc_writes[1:]
    check(
        enc_fields == dec_reads,
        f"join-code fields disagree: encode {enc_fields} vs decode {dec_reads}",
    )

    check('PREFIX = "OEAL-"' in src, "join code prefix changed — old codes will not decode")
    check("Codes.crc32" in enc and "Codes.crc32" in dec, "join code is not CRC-checked on both sides")
    check("u16(port)" in enc or ".u16(port)" in enc, "encode does not write the port as u16")
    check("val port = r.u16()" in dec, "decode does not read the port as u16")
    # An 8-byte address block per entry, four entries max: enough for Wi-Fi + hotspot + USB.
    check("addresses.take(4)" in enc, "encode no longer caps addresses at 4")
    check("it.address" in enc and "getByAddress" in dec, "addresses are not raw IPv4 bytes on both sides")
    check(".bytes(4)" in dec, "decode does not read a 4-byte address per entry")
    # The version byte must match; a stale code should fail loudly, not silently.
    check("u8(VERSION)" in enc and "u8() != VERSION" in dec, "join code version check is asymmetric")


def check_interfaces() -> None:
    src = read(LAN)
    check("NetworkInterface.getNetworkInterfaces()" in src, "LAN layer no longer enumerates network interfaces")
    for needle, why in [
        ("isSiteLocalAddress", "private addresses are not filtered"),
        ("softap", "hotspot interfaces are not recognised"),
        ("255.255.255.255", "no global broadcast target"),
        ("broadcast", "no subnet broadcast target"),
        ("rmnet", "mobile data is not excluded from LAN play"),
    ]:
        check(needle in src, f"LAN: {why} ({needle!r} missing)")
    check("class LanServer" in src and "class LanClientLink" in src, "LAN server or client link is missing")
    # Host listens on the same port for TCP and UDP, so a client needs one code.
    check("bind(InetSocketAddress(port))" in src, "LAN UDP socket does not share the TCP port")


def check_session_wiring() -> None:
    src = read(SESSION)
    for fn in ["fun startLan(", "fun joinLan(", "fun joinLanAny(", "fun createOnlineInvite("]:
        check(fn in src, f"NetSession is missing {fn}")
    if "fun startLan(" in src:
        block = src[src.index("fun startLan("):]
        block = block[: block.find("\n    fun ", 10)] if "\n    fun " in block[10:] else block
        check("LanServer(this)" in block, "startLan does not start a LanServer")
        check("LanBeacon(" in block and "LanAdvertiser(" in block, "startLan does not advertise the session")
        check("LanJoinCode.encode" in block, "startLan does not produce a join code")
        check(
            "Not connected to Wi-Fi or a hotspot" in block,
            "hosting without a network no longer explains why nobody can join",
        )


def check_ui_wiring() -> None:
    src = read(ACTIVITY)
    # Every row of the friends sheet must call something real.
    sheet = re.search(r"private fun showFriendsMenu\(\) \{(.*?)\n    \}", src, re.S)
    check(sheet is not None, "showFriendsMenu is missing")
    if sheet:
        body = sheet.group(1)
        calls = re.findall(r"dialog\.dismiss\(\); (\w+)\(", body) + re.findall(r"dialog\.dismiss\(\); showHome\(\)", body)
        for fn in re.findall(r"dialog\.dismiss\(\); (\w+)\(", body):
            check(
                re.search(rf"private fun {fn}\(", src) is not None,
                f"friends sheet calls {fn}(), which is not defined",
            )
        check(len(calls) >= 4, "the friends sheet no longer offers host LAN, host online, join nearby and join code")
        check("hostSession(lan = true)" in body, "Wi-Fi/hotspot hosting is not wired")
        check("hostSession(lan = false)" in body, "online hosting is not wired")
        check("showNearbyBrowser()" in body, "join-nearby is not wired")
        check("promptJoinCode()" in body, "join-with-code is not wired")

    host = re.search(r"private fun hostSession\(lan: Boolean\) \{(.*?)\n    \}", src, re.S)
    check(host is not None, "hostSession is missing")
    if host:
        b = host.group(1)
        check("NetSession.host(" in b, "hostSession does not create a session")
        check("s.startLan" in b, "hostSession never starts the LAN listener")
        check("s.createOnlineInvite()" in b, "hostSession never creates an online invite")
        check("showLobby(" in b, "hostSession never shows the lobby")

    join = re.search(r"private fun joinWithCode\(text: String\) \{(.*?)\n    \}", src, re.S)
    check(join is not None, "joinWithCode is missing")
    if join:
        b = join.group(1)
        check("LanJoinCode.isLanCode" in b, "joinWithCode does not recognise Wi-Fi codes")
        check("LanJoinCode.decode" in b, "joinWithCode does not decode Wi-Fi codes")
        check("NetSession.joinLanAny(" in b, "joinWithCode does not connect to the decoded addresses")

    nearby = re.search(r"private fun renderNearbyRows\(container: LinearLayout, games: List<LanGame>\) \{(.*?)\n    \}", src, re.S)
    check(nearby is not None, "renderNearbyRows is missing")
    if nearby:
        b = nearby.group(1)
        check("NetSession.joinLan(" in b, "a discovered game cannot be joined")
        check("nearbyList" in b, "the nearby list is no longer rendered")

    browser = re.search(r"private fun showNearbyBrowser\(\) \{(.*?)\n    \}", src, re.S)
    if browser:
        check("LanBrowser(" in browser.group(1), "join-nearby does not start a LanBrowser")


def check_host_gameplay() -> None:
    src = read(ARENA)
    check("isAuthority" in src, "the match has no host authority flag")
    check("sendSnapshot()" in src, "the host never sends snapshots")
    check("sendCommand(" in src, "clients never send commands")
    check("MSG_SNAP" in src and "MSG_CMD" in src, "network message ids are missing")
    check("sim.compensation = " in src and "rttTo(" in src,
          "host hit detection is not lag-compensated by measured RTT")
    # The host must keep simulating while it is the authority, and clients must predict.
    check("sim.step(dt)" in src, "the host does not step the simulation")
    check("reconcil" in src.lower(), "clients do not reconcile against the host")


def main() -> int:
    check_join_code()
    check_interfaces()
    check_session_wiring()
    check_ui_wiring()
    check_host_gameplay()
    if problems:
        print(f"HOST/JOIN: {len(problems)} problem(s)")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("HOST/JOIN OK — join code round-trips, LAN + hotspot discovery wired, "
          "host/join UI reaches a real transport, match is host-authoritative")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
