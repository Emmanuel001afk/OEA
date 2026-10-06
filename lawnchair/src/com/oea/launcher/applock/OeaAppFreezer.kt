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
        if (!dpm.isDeviceOwnerApp(context.packageName)) return Result(false, "True app freezing requires OEA to be device owner")
        return runCatching {
            dpm.setPackagesSuspended(ComponentName(context, OeaDeviceAdminReceiver::class.java), arrayOf(packageName), frozen)
            Result(true, if (frozen) "App frozen" else "App unfrozen")
        }.getOrElse { Result(false, it.message ?: "Could not change frozen state") }
    }
}
