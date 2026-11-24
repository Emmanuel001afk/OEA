package com.oea.launcher.miniapps

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/** Handles the platform's explicit reply-via-message action when OEA is the SMS role holder. */
class OeaSmsRespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val address = intent?.data?.schemeSpecificPart?.trim().orEmpty()
            val body = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (address.isBlank() || body.isBlank()) {
                Log.w(TAG, "Ignored an empty respond-via-message request")
                return START_NOT_STICKY
            }
            if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot respond via SMS without SEND_SMS permission")
                return START_NOT_STICKY
            }
            // Through the app's own sender, not a bare SmsManager call: this reply
            // was sent and never recorded, so it vanished from the thread it
            // answered and from Android's Sent folder. sendNow splits the text the
            // same way the conversation does and files the row afterwards.
            OeaMessagingApp.sendNow(applicationContext, address, body, -1)
        } catch (error: Throwable) {
            Log.e(TAG, "Respond-via-message failed", error)
        } finally {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "OeaSmsReply"
    }
}
