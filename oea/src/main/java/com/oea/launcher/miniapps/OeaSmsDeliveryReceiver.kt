package com.oea.launcher.miniapps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import com.oea.launcher.R

/**
 * Android's default-SMS role entry point. SMS_DELIVER is sent here only when
 * OEA is the selected SMS app. The system provider remains the source of truth.
 */
class OeaSmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        if (messages.isEmpty()) return
        val first = messages.first()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        // The SIM this arrived on. Android puts the subscription in the
        // deliver intent ("subscription" historically, the named extra on
        // newer releases); recording it in sub_id is what lets Messaging
        // later answer "which SIM was this received on?" honestly.
        val subscriptionId = subscriptionIdFrom(intent)
        fun values(withSub: Boolean) = android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, first.originatingAddress ?: "")
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, first.timestampMillis)
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (withSub && subscriptionId >= 0) put("sub_id", subscriptionId)
        }
        val inserted = runCatching {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values(true)) != null
        }.recoverCatching {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values(false)) != null
        }.onFailure { Log.w(TAG, "Could not store incoming SMS", it) }.getOrDefault(false)
        runCatching {
            context.getSharedPreferences("oea_messaging_v2", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_sms_delivery", System.currentTimeMillis())
                .putInt("last_sms_parts", messages.size)
                .apply()
        }.onFailure { Log.w(TAG, "Could not record SMS delivery", it) }
        val muted = runCatching {
            context.getSharedPreferences("oea_messaging_v2", Context.MODE_PRIVATE)
                .getStringSet("muted_addresses", emptySet())
                ?.contains(first.originatingAddress.orEmpty()) == true
        }.getOrDefault(false)
        if (inserted && !muted) postNewMessageNotification(context, first.originatingAddress.orEmpty(), body)
    }

    private fun subscriptionIdFrom(intent: Intent): Int {
        val extras = intent.extras ?: return -1
        for (key in listOf("subscription", "android.telephony.extra.SUBSCRIPTION_ID", "subId", "subscription_id")) {
            if (extras.containsKey(key)) {
                val value = runCatching { extras.getInt(key, -1) }.getOrDefault(-1)
                if (value >= 0) return value
            }
        }
        return -1
    }

    private fun postNewMessageNotification(context: Context, sender: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Alerts for new SMS received by OEA"
                }
            )
        }
        val openMessaging = runCatching {
            val launch = OeaMiniApps.launchIntent(context, "messaging")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull() ?: return
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val notification = builder
            .setSmallIcon(R.drawable.ic_oea_messaging)
            .setContentTitle(sender.ifBlank { "New message" })
            .setContentText(body.take(120))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openMessaging)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context))
                    .setSmallIcon(R.drawable.ic_oea_messaging)
                    .setContentTitle("New message")
                    .setContentText("Open OEA to view")
                    .build()
            )
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification) }
            .onFailure { Log.w(TAG, "Could not show incoming-message notification", it) }
    }

    companion object {
        private const val TAG = "OeaSmsReceiver"
        private const val CHANNEL = "oea_sms_messages"
    }
}
