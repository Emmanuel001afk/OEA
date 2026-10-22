package com.oea.launcher.miniapps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

/**
 * Which SIM handled a message — the lookup behind Messaging's long-press
 * details ("Received on SIM 1 • MTN", "Sent with SIM 2 • Airtel").
 *
 * Android stores the subscription that handled each SMS in the provider's
 * `sub_id` column; this directory turns that id into the SIM's slot, the name
 * the user gave it, its network (carrier) name and, where Android allows it,
 * its number. Every failure is a named state rather than a guess: a message
 * whose SIM Android never recorded says so, a SIM that has been taken out of
 * the phone says so, and a missing Phone permission asks for it instead of
 * silently printing "SIM 1".
 */
internal object OeaSimDirectory {

    /** One active SIM (or eSIM profile), as Android currently sees it. */
    data class Line(
        val subscriptionId: Int,
        /** 1-based physical slot, or -1 when Android does not report one. */
        val slot: Int,
        /** The name shown in Settings for this SIM (often the carrier). */
        val displayName: String,
        /** The network's own name for itself. */
        val carrierName: String,
        /** The SIM's own number when Android lets an app read it, else null. */
        val number: String?
    )

    enum class State {
        /** The SIM is in the phone now and fully named. */
        FOUND,

        /** Android stored no subscription for this message at all. */
        NOT_RECORDED,

        /** A subscription is recorded, but that SIM is not active now. */
        SIM_NOT_PRESENT,

        /** Naming SIMs needs the Phone permission, which is not granted. */
        PERMISSION_NEEDED,

        /** This device reports no telephony subscriptions at all. */
        UNAVAILABLE
    }

    data class Lookup(val state: State, val line: Line?, val subscriptionId: Int)

    const val UNKNOWN_SUBSCRIPTION = -1

    fun hasPhonePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context): SubscriptionManager? =
        runCatching { context.getSystemService(SubscriptionManager::class.java) }.getOrNull()

    @Suppress("DEPRECATION") // SubscriptionInfo.getNumber(), only on the pre-33 branch below
    private fun lineOf(info: SubscriptionInfo, manager: SubscriptionManager, context: Context): Line {
        val number = runCatching {
            when {
                Build.VERSION.SDK_INT >= 33 -> {
                    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) ==
                        PackageManager.PERMISSION_GRANTED
                    ) manager.getPhoneNumber(info.subscriptionId) else null
                }
                else -> info.getNumber()
            }
        }.getOrNull()?.trim()?.ifBlank { null }
        return Line(
            subscriptionId = info.subscriptionId,
            slot = if (info.simSlotIndex >= 0) info.simSlotIndex + 1 else -1,
            displayName = info.displayName?.toString()?.trim().orEmpty(),
            carrierName = info.carrierName?.toString()?.trim().orEmpty(),
            number = number
        )
    }

    /** Every SIM Android considers active, in slot order. Empty without permission. */
    fun activeLines(context: Context): List<Line> {
        if (!hasPhonePermission(context)) return emptyList()
        val manager = manager(context) ?: return emptyList()
        val infos = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return infos.map { lineOf(it, manager, context) }
            .sortedWith(compareBy({ if (it.slot < 0) Int.MAX_VALUE else it.slot }, { it.subscriptionId }))
    }

    /** Resolves the subscription recorded on one message. Never throws, never guesses. */
    fun lookup(context: Context, subscriptionId: Int): Lookup {
        if (subscriptionId < 0) return Lookup(State.NOT_RECORDED, null, subscriptionId)
        if (!hasPhonePermission(context)) return Lookup(State.PERMISSION_NEEDED, null, subscriptionId)
        val manager = manager(context) ?: return Lookup(State.UNAVAILABLE, null, subscriptionId)
        val info = runCatching { manager.getActiveSubscriptionInfo(subscriptionId) }.getOrNull()
            ?: runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
                .firstOrNull { it.subscriptionId == subscriptionId }
            ?: return Lookup(
                if (runCatching { manager.activeSubscriptionInfoCount }.getOrDefault(0) == 0) State.UNAVAILABLE
                else State.SIM_NOT_PRESENT,
                null,
                subscriptionId
            )
        return Lookup(State.FOUND, lineOf(info, manager, context), subscriptionId)
    }

    /** The SIM Android would send an SMS from if nobody chooses one. */
    fun defaultSmsLine(context: Context): Line? {
        val id = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(UNKNOWN_SUBSCRIPTION)
        if (id < 0) return activeLines(context).firstOrNull()
        return lookup(context, id).line
    }

    /** The label Messaging prints for a SIM: "SIM 1 • MTN Nigeria". */
    fun shortLabel(line: Line): String = OeaSimLabels.short(line.slot, line.displayName, line.carrierName)
}

/** The pure wording of a SIM label, split out so it is unit-tested without Android. */
internal object OeaSimLabels {

    /**
     * "SIM 1 • MTN Nigeria". The Settings name and the carrier name are very
     * often the same string; printing both ("MTN • MTN") helps nobody, so the
     * name the user can edit wins and the carrier only appears when it adds
     * information.
     */
    fun short(slot: Int, displayName: String, carrierName: String): String {
        val sim = if (slot >= 1) "SIM $slot" else "SIM"
        val name = displayName.trim().ifBlank { carrierName.trim() }
        return if (name.isBlank()) sim else "$sim • $name"
    }

    /** The network line for the details sheet, or null when it would only repeat the SIM line. */
    fun network(displayName: String, carrierName: String): String? {
        val carrier = carrierName.trim()
        if (carrier.isBlank()) return null
        if (carrier.equals(displayName.trim(), ignoreCase = true)) return null
        return carrier
    }
}
