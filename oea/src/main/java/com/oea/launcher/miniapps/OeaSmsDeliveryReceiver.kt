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
        val inserted = runCatching {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, first.originatingAddress ?: "")
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, first.timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) != null
        }.onFailure { Log.w(TAG, "Could not store incoming SMS", it) }.getOrDefault(false)
        runCatching {
            context.getSharedPreferences("oea_messaging_v2", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_sms_delivery", System.currentTimeMillis())
                .putInt("last_sms_parts", messages.size)
                .apply()
        }.onFailure { Log.w(TAG, "Could not record SMS delivery", it) }
        if (inserted) postNewMessageNotification(context, first.originatingAddress.orEmpty(), body)
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
