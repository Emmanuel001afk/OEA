package app.lawnchair.oea.split

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class OeaSplitAccessibilityService : AccessibilityService() {
    private lateinit var controller: OeaSplitServiceController

    override fun onServiceConnected() {
        controller = OeaSplitServiceController(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 10
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = intent?.getStringExtra(EXTRA_FIRST)?.let { runCatching { Intent.parseUri(it, 0) }.getOrNull() }
        val second = intent?.getStringExtra(EXTRA_SECOND)?.let { runCatching { Intent.parseUri(it, 0) }.getOrNull() }
        if (first != null && second != null && ::controller.isInitialized) controller.begin(first, second)
        return START_NOT_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::controller.isInitialized) return
        val packageName = event?.packageName?.toString() ?: return
        val splitVisible = windows?.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER } == true
        controller.onWindowEvent(packageName, splitVisible) { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
    }

    override fun onInterrupt() = Unit

    companion object {
        const val EXTRA_FIRST = "oea_split_first"
        const val EXTRA_SECOND = "oea_split_second"
    }
}
