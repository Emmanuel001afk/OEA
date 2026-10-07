package com.oea.launcher.notifications

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object OeaNotificationState {
    private val active = LinkedHashMap<String, StatusBarNotification>()
    @Synchronized fun update(sbn: StatusBarNotification) { active[sbn.key] = sbn }
    @Synchronized fun remove(sbn: StatusBarNotification) { active.remove(sbn.key) }
    @Synchronized fun clear() { active.clear() }
    @Synchronized fun countForPackage(packageName: String): Int = active.values.count { it.packageName == packageName }
}

class OeaNotificationListener : NotificationListenerService() {
    private var island: View? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        OeaNotificationState.update(sbn)
        if (isMediaNotification(sbn)) showDynamicIsland(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        OeaNotificationState.remove(sbn)
        if (isMediaNotification(sbn)) hideDynamicIsland()
    }

    override fun onListenerDisconnected() {
        OeaNotificationState.clear()
        hideDynamicIsland()
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val category = sbn.notification.category
        val pkg = sbn.packageName.lowercase()
        return category == Notification.CATEGORY_TRANSPORT ||
            pkg.contains("spotify") ||
            sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    private fun showDynamicIsland(sbn: StatusBarNotification) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: sbn.packageName.substringAfterLast('.')
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 8, 18, 8)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 60f
            }
            addView(TextView(this@OeaNotificationListener).apply {
                this.text = "●"
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 10, 0)
            })
            addView(TextView(this@OeaNotificationListener).apply {
                this.text = if (text.isBlank()) title else title + "  •  " + text
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, 32, 1f))
        }
        root.setOnClickListener {
            runCatching {
                sbn.notification.contentIntent?.send()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 24
        }
        hideDynamicIsland()
        runCatching { wm.addView(root, params); island = root }
    }

    private fun hideDynamicIsland() {
        island?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        island = null
    }
}
