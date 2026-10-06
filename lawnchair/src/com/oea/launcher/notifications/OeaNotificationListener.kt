package com.oea.launcher.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * OEA-owned notification bridge.
 *
 * This replaces Launcher3's NotificationListener as the owner of notification state.
 * Rendering/badges are intentionally kept outside the Android service so the launcher
 * remains deterministic and testable.
 */
class OeaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        OeaNotificationState.update(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        OeaNotificationState.remove(sbn)
    }

    override fun onListenerDisconnected() {
        OeaNotificationState.clear()
    }
}
