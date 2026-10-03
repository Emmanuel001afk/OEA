package app.lawnchair.oea.split

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

object OeaSplitLauncher {
    fun launchPair(context: Context, first: String, second: String): Boolean {
        val pm = context.packageManager
        val a = pm.getLaunchIntentForPackage(first)?.apply { addCategory(Intent.CATEGORY_LAUNCHER) } ?: return false
        val b = pm.getLaunchIntentForPackage(second)?.apply { addCategory(Intent.CATEGORY_LAUNCHER) } ?: return false

        if (isAccessibilityEnabled(context)) {
            return runCatching {
                val service = Intent(context, OeaSplitAccessibilityService::class.java)
                    .putExtra(OeaSplitAccessibilityService.EXTRA_FIRST, a.toUri(0))
                    .putExtra(OeaSplitAccessibilityService.EXTRA_SECOND, b.toUri(0))
                context.startService(service)
                true
            }.getOrDefault(false)
        }

        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return runCatching {
            context.startActivity(a)
            context.startActivity(b)
            true
        }.getOrDefault(false)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = context.packageName + "/" + OeaSplitAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return TextUtils.SimpleStringSplitter(':').run {
            setString(enabled)
            any { it.equals(expected, ignoreCase = true) }
        }
    }
}
