package com.oea.launcher.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

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
