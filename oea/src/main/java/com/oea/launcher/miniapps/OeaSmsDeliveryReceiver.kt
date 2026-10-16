package com.oea.launcher.miniapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Android's default-SMS role entry point. The platform delivers SMS_DELIVER
    * here only when OEA is the selected SMS app. Android's SMS provider remains
    * the source of truth for the Messaging UI; this receiver commits the
    * assembled delivery there and never forwards message contents elsewhere.
 */
class OeaSmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        if (messages.isEmpty()) return
        // A default SMS app owns the SMS_DELIVER hand-off and must commit the
        // assembled message to the system provider. The Messaging screen reads
        // that provider, so this keeps the inbox real (and avoids a second
        // private database). Multi-part SMS arrives as several SmsMessage values.
        runCatching {
            val first = messages.first()
            val body = messages.joinToString("") { it.messageBody.orEmpty() }
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, first.originatingAddress ?: "")
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, first.timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        }.onFailure { Log.w("OeaSmsReceiver", "Could not write incoming SMS", it) }
        runCatching {
            context.getSharedPreferences("oea_messaging_v2", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_sms_delivery", System.currentTimeMillis())
                .putInt("last_sms_parts", messages.size)
                .apply()
        }.onFailure { Log.w("OeaSmsReceiver", "Could not record SMS delivery", it) }
    }
}
