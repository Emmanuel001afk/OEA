package com.oea.launcher.applock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object OeaAppFreezer {
    enum class Backend { DEVICE_OWNER, ROOT, NONE }
    data class Result(val success: Boolean, val message: String, val backend: Backend = Backend.NONE)

    fun backend(context: Context): Backend {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(context.packageName) == true) return Backend.DEVICE_OWNER
        return if (hasRoot()) Backend.ROOT else Backend.NONE
    }

    fun setFrozen(context: Context, packageName: String, frozen: Boolean): Result {
        if (packageName.isBlank()) return Result(false, "No package selected")
        if (packageName == context.packageName) return Result(false, "OEA cannot freeze itself")
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            return runCatching {
                val failed = dpm.setPackagesSuspended(
                    ComponentName(context, OeaDeviceAdminReceiver::class.java),
                    arrayOf(packageName),
                    frozen,
                )
                if (failed.contains(packageName)) {
                    return@runCatching Result(false, "Android refused to change the frozen state", Backend.DEVICE_OWNER)
                }
                persist(context, packageName, frozen)
                Result(true, if (frozen) "App frozen" else "App unfrozen", Backend.DEVICE_OWNER)
            }.getOrElse { Result(false, it.message ?: "Could not change frozen state", Backend.DEVICE_OWNER) }
        }

        if (hasRoot()) {
            val command = if (frozen) "cmd package suspend --user 0 $packageName"
            else "cmd package unsuspend --user 0 $packageName"
            val result = runRoot(command)
            if (result.first) {
                persist(context, packageName, frozen)
                return Result(true, if (frozen) "App frozen with root authority" else "App unfrozen with root authority", Backend.ROOT)
            }
            return Result(false, result.second.ifBlank { "Root package suspension failed" }, Backend.ROOT)
        }

        return Result(false, "No freezer authority. Provision OEA as device owner or provide root authority.", Backend.NONE)
    }

    fun frozenPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FROZEN, emptySet()).orEmpty()

    fun syncActualState(context: Context) {
        if (backend(context) != Backend.DEVICE_OWNER) return
        val pm = context.packageManager
        val known = frozenPackages(context).toMutableSet()
        known.removeAll { pkg -> runCatching { !pm.isPackageSuspended(pkg) }.getOrDefault(true) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FROZEN, known).apply()
    }

    private fun persist(context: Context, packageName: String, frozen: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packages = prefs.getStringSet(KEY_FROZEN, emptySet()).orEmpty().toMutableSet()
        if (frozen) packages.add(packageName) else packages.remove(packageName)
        prefs.edit().putStringSet(KEY_FROZEN, packages).apply()
    }

    private fun hasRoot(): Boolean =
        runCatching { ProcessBuilder("su", "-c", "id").start().apply { waitFor() }.exitValue() == 0 }.getOrDefault(false)

    private fun runRoot(command: String): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val code = process.waitFor()
        (code == 0) to output
    }.getOrElse { false to (it.message ?: "") }

    private const val PREFS = "oea_app_freezer"
    private const val KEY_FROZEN = "frozen_packages"
}
