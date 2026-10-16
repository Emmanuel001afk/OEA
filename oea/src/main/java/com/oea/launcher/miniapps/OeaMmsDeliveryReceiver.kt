package com.oea.launcher.miniapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Default-SMS role entry point for WAP push/MMS delivery notifications. */
class OeaMmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        context.getSharedPreferences("oea_messaging_v2", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_mms_delivery", System.currentTimeMillis())
            .apply()
    }
}
