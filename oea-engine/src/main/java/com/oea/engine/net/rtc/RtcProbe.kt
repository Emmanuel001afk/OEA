package com.oea.engine.net.rtc

import android.content.Context
import com.oea.engine.net.Channel
import com.oea.engine.net.Link
import com.oea.engine.net.LinkListener

/**
 * "Online readiness" check: gathers this phone's WebRTC network routes
 * (local IPv4/IPv6, public addresses via the user's STUN server, relays via
 * their TURN server) and explains in plain words what that means for
 * connecting to friends on other networks.
 */
object RtcProbe {
    data class Report(
        val hostV4: Int,
        val hostV6: Int,
        val reflexive: Int,
        val relay: Int,
        val publicAddresses: List<String>,
        val error: String?,
    ) {
        val globalV6 get() = hostV6 > 0
        fun summary(): String = when {
            error != null -> error
            relay > 0 -> "Excellent: your TURN server works, so online games connect even on strict networks."
            reflexive > 0 && globalV6 -> "Very good: IPv6 and your STUN server both work. Most friends will connect directly."
            reflexive > 0 -> "Good: your STUN server works (public address ${publicAddresses.firstOrNull() ?: "found"}). Strict mobile networks may still need a TURN server."
            globalV6 -> "OK: this network has IPv6, which often allows direct connections without any server. Add your own STUN/TURN server for IPv4-only friends."
            hostV4 > 0 -> "Local only: without IPv6 or your own STUN/TURN server, online codes only connect phones on the same network. Wi-Fi / hotspot mode always works nearby."
            else -> "No network routes found. Check that Wi-Fi or mobile data is on."
        }
    }

    fun run(context: Context, servers: List<IceServerConfig>, done: (Report) -> Unit) {
        if (!RtcRuntime.isAvailable(context)) {
            done(Report(0, 0, 0, 0, emptyList(), RtcRuntime.unavailableReason ?: "Online play isn't available on this device."))
            return
        }
        val listener = object : LinkListener {
            override fun onLinkUp(link: Link) = Unit
            override fun onLinkMessage(link: Link, channel: Channel, data: ByteArray) = Unit
            override fun onLinkDown(link: Link, reason: String) = Unit
        }
        val link = RtcLink(context.applicationContext, isOfferer = true, iceServers = servers, listener = listener)
        link.createOffer { sdp ->
            link.close("probe finished")
            if (sdp == null) { done(Report(0, 0, 0, 0, emptyList(), link.stateDetail.ifBlank { "Couldn't gather network routes." })); return@createOffer }
            var v4 = 0; var v6 = 0; var srflx = 0; var relay = 0
            val publics = ArrayList<String>()
            for (line in sdp.lines()) {
                if (!line.startsWith("a=candidate:")) continue
                val parts = line.split(' ')
                val addr = parts.getOrNull(4).orEmpty()
                val typ = parts.getOrNull(parts.indexOf("typ") + 1).orEmpty()
                when (typ) {
                    "host" -> if (addr.contains(':')) { if (!addr.startsWith("fe80", ignoreCase = true)) v6++ } else v4++
                    "srflx", "prflx" -> { srflx++; publics += addr }
                    "relay" -> relay++
                }
            }
            done(Report(v4, v6, srflx, relay, publics.distinct(), null))
        }
    }
}
