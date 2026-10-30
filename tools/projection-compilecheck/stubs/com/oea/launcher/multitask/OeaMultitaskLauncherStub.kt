// Compile-check stub. NOT part of the app build.
// Signature-compatible copy of OeaMultitaskLauncher.launchFloating.
package com.oea.launcher.multitask

import android.content.Context
import com.oea.launcher.model.OeaAppInfo

object OeaMultitaskLauncher {
    data class Result(val success: Boolean, val message: String, val freeformAdvertised: Boolean = false)
    fun launchFloating(context: Context, app: OeaAppInfo): Result = Result(false, "stub")
}
