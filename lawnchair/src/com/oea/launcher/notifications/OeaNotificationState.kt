package com.oea.launcher.notifications

import android.service.notification.StatusBarNotification

/**
 * Process-local OEA notification model. UI layers read this model for future
 * notification dots/counts without depending on Launcher3 data classes.
 */
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
