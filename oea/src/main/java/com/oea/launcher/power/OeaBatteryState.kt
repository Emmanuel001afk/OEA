package com.oea.launcher.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.View

/** A single snapshot from Android's battery broadcast. Unknown is not discharging. */
internal data class OeaBatteryState(val level: Int?, val status: Int, val plugged: Boolean) {
    val description: String get() = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> if (plugged) "Plugged in · discharging" else "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> if (plugged) "Plugged in · not charging" else "Not charging"
        else -> if (plugged) "Plugged in · status unknown" else "Status unknown"
    }
    val isCharging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING
    val text: String get() = "${level?.let { "$it%" } ?: "—"} · $description"

    companion object {
        fun from(intent: Intent?): OeaBatteryState {
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val level = if (raw >= 0 && scale > 0 && raw <= scale) (raw.toLong() * 100 / scale).toInt() else null
            return OeaBatteryState(level, intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN,
                (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        }
        fun read(context: Context): OeaBatteryState = from(runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull())

        /** Updates only while the owning view is attached; unregisters on detach. */
        fun observe(view: View, update: (OeaBatteryState) -> Unit) {
            val context = view.context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { update(from(intent)) }
            }
            var registered = false
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    update(read(context))
                    if (!registered) registered = runCatching {
                        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        else context.registerReceiver(receiver, filter)
                        true
                    }.getOrDefault(false)
                }
                override fun onViewDetachedFromWindow(v: View) {
                    if (registered) runCatching { context.unregisterReceiver(receiver) }
                    registered = false
                }
            })
        }
    }
}
