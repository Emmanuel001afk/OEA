package com.oea.launcher.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.View

/**
 * One reading of the phone's battery — the only one OEA shows anywhere.
 *
 * Everything comes from a real source: the sticky `ACTION_BATTERY_CHANGED` first,
 * and the `BatteryManager` properties as the fallback for the builds that hand over
 * an intent without the level in it. Nothing here estimates, interpolates or
 * remembers a value it was not told; a reading that cannot be had is `null`, which
 * the UI prints as "—" instead of inventing 0% or 100%.
 *
 * Charging is part of the reading, not a separate guess: `isCharging` covers
 * "charging" and "full", and `plugged` distinguishes a phone on a charge limiter
 * ("plugged in, not charging") from one that is actually taking charge.
 */
internal data class OeaBatteryState(
    val percent: Int?,
    val status: Int,
    val plugged: Boolean,
    /** Battery temperature in °C, or null when the phone does not report it. */
    val temperatureCelsius: Float? = null
) {
    val isCharging: Boolean get() = OeaBatteryMath.isCharging(status)
    val isFull: Boolean get() = OeaBatteryMath.isFull(status)
    val description: String get() = OeaBatteryMath.description(status, plugged)

    /** "85%" or "—". */
    val label: String get() = OeaBatteryMath.label(percent)

    /** "85%" on battery, "85% · Charging" on the cable. */
    val shortLabel: String get() = OeaBatteryMath.shortLabel(percent, status, plugged)

    /** "Charging" / "Plugged in, not charging" / null while draining. */
    val chargingNote: String? get() = OeaBatteryMath.note(status, plugged)

    /** "85% · Charging" — the sentence form the settings and the console use. */
    val text: String get() = "$label · $description"

    /** True when this reading carries nothing the phone actually said. */
    val isUnknown: Boolean get() = percent == null && status == OeaBatteryMath.STATUS_UNKNOWN

    companion object {
        val UNKNOWN = OeaBatteryState(null, OeaBatteryMath.STATUS_UNKNOWN, false, null)

        fun from(intent: Intent?): OeaBatteryState {
            if (intent == null) return UNKNOWN
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) != 0
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return OeaBatteryState(
                percent = OeaBatteryMath.percent(level, scale),
                status = status,
                plugged = plugged,
                temperatureCelsius = if (tenths == Int.MIN_VALUE) null else tenths / 10f
            )
        }

        /**
         * The current reading.
         *
         * The sticky broadcast is asked first because it is the same numbers the
         * system UI draws. When it arrives without a usable level or status — which
         * happens on some builds and in some power states — the `BatteryManager`
         * properties are asked instead. Both are real sources; neither is a guess,
         * and if both decline the answer is "unknown".
         */
        fun read(context: Context): OeaBatteryState {
            val sticky = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val fromBroadcast = from(sticky)
            if (fromBroadcast.percent != null && fromBroadcast.status != BatteryManager.BATTERY_STATUS_UNKNOWN) {
                return fromBroadcast
            }
            val manager = runCatching { context.getSystemService(BatteryManager::class.java) }.getOrNull()
                ?: return fromBroadcast
            val capacity = runCatching {
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrDefault(OeaBatteryMath.PROPERTY_UNKNOWN)
            val status = runCatching {
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            }.getOrDefault(OeaBatteryMath.PROPERTY_UNKNOWN)
            return OeaBatteryState(
                percent = fromBroadcast.percent ?: OeaBatteryMath.percentProperty(capacity),
                status = if (fromBroadcast.status != BatteryManager.BATTERY_STATUS_UNKNOWN) {
                    fromBroadcast.status
                } else {
                    OeaBatteryMath.statusProperty(status) ?: OeaBatteryMath.STATUS_UNKNOWN
                },
                plugged = fromBroadcast.plugged || (fromBroadcast.status == BatteryManager.BATTERY_STATUS_UNKNOWN &&
                    OeaBatteryMath.isCharging(OeaBatteryMath.statusProperty(status) ?: 0)),
                temperatureCelsius = fromBroadcast.temperatureCelsius
            )
        }

        /**
         * Live updates while the owning view is attached, and only then.
         *
         * Android re-broadcasts `ACTION_BATTERY_CHANGED` on every level change and on
         * every plug or unplug, so a screen that observes is never showing a number
         * the phone has already left behind.
         */
        fun observe(view: View, update: (OeaBatteryState) -> Unit) {
            val context = view.context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { update(from(intent)) }
            }
            var registered = false
            fun attach() {
                update(read(context))
                if (!registered) registered = runCatching {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }
                    true
                }.getOrDefault(false)
            }
            fun detach() {
                if (registered) runCatching { context.unregisterReceiver(receiver) }
                registered = false
            }
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = attach()
                override fun onViewDetachedFromWindow(v: View) = detach()
            })
            // Most screens hand us a view that is already in the window, where the
            // attach listener never fires. Observe now; `registered` keeps it single.
            if (view.isAttachedToWindow) attach()
        }
    }
}
