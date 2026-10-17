package com.oea.launcher.miniapps

import android.app.Activity
import android.content.Intent

/**
 * The three ways a mini-app screen talks back to the activity that hosts it.
 *
 * Screens are handed a plain [Activity], because the same builder is reused by the
 * index page, a launcher shortcut and a share sheet. When the host really is
 * [OeaMiniAppActivity] the hook lands on it; when it is not, installing a hook is a
 * no-op and the screen still works — it just will not be told about an
 * activity result it never asked for.
 */
internal var Activity.miniResultHook: ((requestCode: Int, resultCode: Int, data: Intent?) -> Boolean)?
    get() = (this as? OeaMiniAppActivity)?.resultHook
    set(value) {
        (this as? OeaMiniAppActivity)?.let { it.resultHook = value }
    }

/** Called on every resume, so an app can reload what it has open without a rebuild. */
internal var Activity.miniResumeHook: (() -> Unit)?
    get() = (this as? OeaMiniAppActivity)?.resumeHook
    set(value) {
        (this as? OeaMiniAppActivity)?.let { it.resumeHook = value }
    }

/** Called just before the screen is backgrounded — flush unsaved text, stop the clock. */
internal var Activity.miniPauseHook: (() -> Unit)?
    get() = (this as? OeaMiniAppActivity)?.pauseHook
    set(value) {
        (this as? OeaMiniAppActivity)?.let { it.pauseHook = value }
    }
