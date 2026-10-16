package com.oea.launcher.miniapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Android's default-SMS role entry point. The platform delivers SMS_DELIVER
 * here only when OEA is the selected SMS app. Android's SMS provider remains
 * the source of truth for the Messaging UI; this receiver deliberately does
 * not duplicate messages or silently forward their contents.
 */
class OeaSmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        if (messages.isEmpty()) return
        // Keep a small delivery marker for the Messaging app's status card and
        // diagnostics. The actual message is written by Android's provider role
        // pipeline and read with READ_SMS, avoiding a second private database.
        runCatching {
            context.getSharedPreferences("oea_messaging_v2", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_sms_delivery", System.currentTimeMillis())
                .putInt("last_sms_parts", messages.size)
                .apply()
        }.onFailure { Log.w("OeaSmsReceiver", "Could not record SMS delivery", it) }
    }
}
