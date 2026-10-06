package com.oea.launcher.applock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class OeaAppLockService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || !OeaAppLockStore.isLocked(this, pkg) || OeaAppLockStore.isTemporarilyUnlocked(this)) return
        OeaAppLockActivity.launch(this, pkg)
    }
    override fun onInterrupt() = Unit
}
