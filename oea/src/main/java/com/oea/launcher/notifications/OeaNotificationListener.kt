package com.oea.launcher.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper

/**
 * Process-local notification snapshot shared with Home. Android calls this
 * listener only after the user grants Notification access; it only keeps a
 * process-local snapshot. Home or the explicitly enabled overlay service renders
 * supported activities, and this listener never creates windows itself.
 */
object OeaNotificationState {
    private data class Candidate(
        val notification: StatusBarNotification,
        val priority: Int,
        val postedAt: Long,
    )

    private val lock = Any()
    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notificationPending = false

    @Volatile
    var connected: Boolean = false
        private set

    fun update(notification: StatusBarNotification) {
        synchronized(lock) {
            active[notification.key] = notification
            notifyChangedLocked()
        }
    }

    fun remove(notification: StatusBarNotification) {
        synchronized(lock) {
            active.remove(notification.key)
            notifyChangedLocked()
        }
    }

    /** Dismisses a clearable notification only while Android's listener is connected. */
    fun dismiss(notification: StatusBarNotification): Boolean =
        notification.isClearable && connected && OeaNotificationListener.dismiss(notification.key)

    fun clear() {
        synchronized(lock) {
            active.clear()
            notifyChangedLocked()
        }
    }

    fun replaceAll(values: Array<StatusBarNotification>?) {
        synchronized(lock) {
            active.clear()
            values.orEmpty().forEach { active[it.key] = it }
            notifyChangedLocked()
        }
    }

    fun setConnected(value: Boolean) {
        connected = value
        synchronized(lock) { notifyChangedLocked() }
    }

    /** Notifications that should produce a badge (ongoing/system noise excluded). */
    fun countForPackage(packageName: String): Int = synchronized(lock) {
        active.values.count { value ->
            runCatching {
                val notification = value.notification
                value.packageName == packageName && value.isClearable && !value.isOngoing &&
                    (notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }.getOrDefault(false)
        }
    }

    /** Most recently active media notification used by the existing At a Glance controls. */
    fun media(): StatusBarNotification? = synchronized(lock) {
        active.values
            .filter(::isMediaNotification)
            .maxByOrNull { it.postTime }
    }

    /**
     * Best notification-backed activity for a Home live-activity surface.
     * Calls and media sessions are prioritized over generic ongoing/actionable
     * notifications. Only notifications with useful content are returned;
     * [accept] limits the choice to the apps routed to one surface.
     */
    fun dynamicIslandNotification(accept: (StatusBarNotification) -> Boolean = { true }): StatusBarNotification? = synchronized(lock) {
        active.values.filter { value -> runCatching { accept(value) }.getOrDefault(false) }.mapNotNull { value ->
            val notification = runCatching { value.notification }.getOrNull() ?: return@mapNotNull null
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return@mapNotNull null

            val media = isMediaNotification(value)
            val call = notification.category == Notification.CATEGORY_CALL
            val hasText = notificationText(notification, Notification.EXTRA_TITLE).isNotBlank() ||
                notificationText(notification, Notification.EXTRA_TEXT).isNotBlank() ||
                notificationText(notification, Notification.EXTRA_BIG_TEXT).isNotBlank()
            if (!hasText && !media && !call) return@mapNotNull null

            val hasSafeAction = runCatching {
                notification.actions.orEmpty().any { action ->
                    action.actionIntent != null && action.remoteInputs.isNullOrEmpty()
                }
            }.getOrDefault(false)
            val usefulOngoingCategory = notification.category in setOf(
                Notification.CATEGORY_CALL,
                Notification.CATEGORY_NAVIGATION,
                Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_STOPWATCH,
                "timer",
            )
            val priority = when {
                call -> 5
                media -> 4
                value.isOngoing && (hasSafeAction || usefulOngoingCategory) -> 3
                hasSafeAction -> 2
                else -> 0
            }
            if (priority == 0) null else Candidate(value, priority, value.postTime)
        }.maxWithOrNull(
            // Keep live calls and media ahead of ordinary notifications. Among
            // ordinary activities, recency wins so a fresh message can replace
            // an older ongoing item without the user opening the older one.
            compareBy<Candidate>({ it.priority >= 4 }, { it.priority == 5 }, { it.postedAt }, { it.priority }),
        )?.notification
    }

    fun addListener(listener: () -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    private fun isMediaNotification(value: StatusBarNotification): Boolean = runCatching {
        val notification = value.notification
        notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }.getOrDefault(false)

    private fun notificationText(notification: Notification, key: String): String = runCatching {
        notification.extras?.getCharSequence(key)?.toString()?.trim().orEmpty()
    }.getOrDefault("")

    /** Coalesce notification bursts and deliver observers on the UI thread. */
    private fun notifyChangedLocked() {
        if (notificationPending) return
        notificationPending = true
        main.postDelayed({
            val observers = synchronized(lock) {
                notificationPending = false
                listeners.toList()
            }
            observers.forEach { observer -> runCatching { observer() } }
        }, 100L)
    }
}

class OeaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        OeaNotificationState.update(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        OeaNotificationState.remove(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeListener = this
        OeaNotificationState.setConnected(true)
        runCatching { OeaNotificationState.replaceAll(activeNotifications) }
            .onFailure { Log.w(TAG, "Could not restore active notifications", it) }
    }

    override fun onListenerDisconnected() {
        if (activeListener === this) {
            activeListener = null
            OeaNotificationState.clear()
            OeaNotificationState.setConnected(false)
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeListener === this) {
            activeListener = null
            OeaNotificationState.clear()
            OeaNotificationState.setConnected(false)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OeaNotifications"
        @Volatile private var activeListener: OeaNotificationListener? = null

        fun dismiss(key: String): Boolean {
            val service = activeListener ?: return false
            return runCatching {
                service.cancelNotification(key)
                true
            }.getOrDefault(false)
        }
    }
}
