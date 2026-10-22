package com.oea.launcher.miniapps

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
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
            val manager = SmsManager.getDefault()
            val parts = manager.divideMessage(body)
            if (parts.size <= 1) manager.sendTextMessage(address, null, body, null, null)
            else manager.sendMultipartTextMessage(address, null, parts, null, null)
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
