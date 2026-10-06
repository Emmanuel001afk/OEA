package app.lawnchair.oea.notifications

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

object OeaNotificationState {
    private val active = LinkedHashMap<String, StatusBarNotification>()

    @Synchronized
    fun update(sbn: StatusBarNotification) {
        active[sbn.key] = sbn
    }

    @Synchronized
    fun remove(sbn: StatusBarNotification) {
        active.remove(sbn.key)
    }

    @Synchronized
    fun clear() {
        active.clear()
    }

    @Synchronized
    fun countForPackage(packageName: String): Int =
        active.values.count { it.packageName == packageName }
}
