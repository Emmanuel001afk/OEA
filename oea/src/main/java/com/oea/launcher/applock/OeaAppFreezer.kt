package com.oea.launcher.applock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object OeaAppFreezer {
    data class Result(val success: Boolean, val message: String)
    fun setFrozen(context: Context, packageName: String, frozen: Boolean): Result {
        if (packageName.isBlank()) return Result(false, "No package selected")
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return Result(false, "Device policy service unavailable")
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return Result(false, "OEA must be device owner for real app freezing")
        }
        return runCatching {
            val failed = dpm.setPackagesSuspended(
                ComponentName(context, OeaDeviceAdminReceiver::class.java),
                arrayOf(packageName),
                frozen,
            )
            if (failed.contains(packageName)) return@runCatching Result(false, "Android refused to change the frozen state")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val packages = prefs.getStringSet(KEY_FROZEN, emptySet()).orEmpty().toMutableSet()
            if (frozen) packages.add(packageName) else packages.remove(packageName)
            prefs.edit().putStringSet(KEY_FROZEN, packages).apply()
            Result(true, if (frozen) "App frozen" else "App unfrozen")
        }.getOrElse { Result(false, it.message ?: "Could not change frozen state") }
    }

    fun frozenPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FROZEN, emptySet()).orEmpty()

    private const val PREFS = "oea_app_freezer"
    private const val KEY_FROZEN = "frozen_packages"
}
