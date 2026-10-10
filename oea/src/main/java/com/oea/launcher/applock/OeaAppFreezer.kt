package com.oea.launcher.applock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Uses only authority Android actually grants OEA. Ordinary Device Admin is
 * detected separately because it cannot suspend arbitrary packages.
 */
object OeaAppFreezer {
    enum class Backend { DEVICE_OWNER, ROOT, DEVICE_ADMIN, NONE }

    @Volatile private var rootAvailableCache: Boolean? = null

    data class Result(val success: Boolean, val message: String, val backend: Backend = Backend.NONE)

    fun backend(context: Context): Backend {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(context.packageName) == true) return Backend.DEVICE_OWNER
        if (hasRoot()) return Backend.ROOT
        val admin = ComponentName(context, OeaDeviceAdminReceiver::class.java)
        if (dpm?.isAdminActive(admin) == true) return Backend.DEVICE_ADMIN
        return Backend.NONE
    }

    fun setFrozen(context: Context, packageName: String, frozen: Boolean): Result {
        if (packageName.isBlank()) return Result(false, "No app selected")
        if (packageName == context.packageName) return Result(false, "OEA cannot freeze itself")
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(context.packageName) == true) {
            return runCatching {
                val failed = dpm.setPackagesSuspended(
                    ComponentName(context, OeaDeviceAdminReceiver::class.java),
                    arrayOf(packageName), frozen
                )
                if (failed.contains(packageName)) {
                    return@runCatching Result(false, "Android refused to change this app's frozen state", Backend.DEVICE_OWNER)
                }
                val actual = runCatching { context.packageManager.isPackageSuspended(packageName) }.getOrNull()
                if (actual == null || actual != frozen) {
                    return@runCatching Result(false, "Android did not confirm the requested state; nothing was saved.", Backend.DEVICE_OWNER)
                }
                persist(context, packageName, frozen)
                Result(true, if (frozen) "App frozen and verified" else "App restored and verified", Backend.DEVICE_OWNER)
            }.getOrElse { Result(false, it.message ?: "Could not change frozen state", Backend.DEVICE_OWNER) }
        }

        if (hasRoot()) {
            val command = if (frozen) "cmd package suspend --user 0 $packageName"
                else "cmd package unsuspend --user 0 $packageName"
            val (success, output) = runShell(command)
            if (!success) return Result(false, output.ifBlank { "Root package-suspension command failed" }, Backend.ROOT)
            val actual = suspensionState(packageName)
            if (actual == null || actual != frozen) {
                return Result(false, "Android did not confirm the requested state; nothing was saved.", Backend.ROOT)
            }
            persist(context, packageName, frozen)
            return Result(true, if (frozen) "App frozen and verified" else "App restored and verified", Backend.ROOT)
        }

        val admin = ComponentName(context, OeaDeviceAdminReceiver::class.java)
        return if (dpm?.isAdminActive(admin) == true) {
            Result(false, "Device Admin is active, but Android does not let ordinary Device Admin freeze apps. OEA must be provisioned as Device Owner or have root access.", Backend.DEVICE_ADMIN)
        } else {
            Result(false, "App freezing needs Device Owner or root authority on this Android device.", Backend.NONE)
        }
    }

    fun frozenPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FROZEN, emptySet()).orEmpty()

    fun syncActualState(context: Context, candidatePackages: Collection<String> = emptyList()) {
        val active = backend(context)
        if (active == Backend.NONE || active == Backend.DEVICE_ADMIN) return
        val candidates = (frozenPackages(context) + candidatePackages)
            .filter { it.isNotBlank() && it != context.packageName }.toSet()
        if (candidates.isEmpty()) return
        val actual: Set<String> = when (active) {
            Backend.DEVICE_OWNER -> candidates.filterTo(mutableSetOf()) { pkg ->
                runCatching { context.packageManager.isPackageSuspended(pkg) }.getOrDefault(false)
            }
            Backend.ROOT -> {
                val (success, dump) = runShell("dumpsys package")
                if (!success || !dump.contains("Package [")) return
                val blocks = Regex("""(?ms)^Package \[([^\]]+)](.*?)(?=^Package \[|\z)""").findAll(dump).toList()
                if (blocks.isEmpty()) return
                blocks.filter { match ->
                    match.groupValues[1] in candidates &&
                        Regex("""(?m)^\s*User 0:.*\bsuspended=true\b""").containsMatchIn(match.groupValues[2])
                }.mapTo(mutableSetOf()) { it.groupValues[1] }
            }
            Backend.DEVICE_ADMIN, Backend.NONE -> return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FROZEN, actual).apply()
    }

    private fun suspensionState(packageName: String): Boolean? {
        val (success, dump) = runShell("dumpsys package")
        if (!success || !dump.contains("Package [")) return null
        val block = Regex("""(?ms)^Package \[([^\]]+)](.*?)(?=^Package \[|\z)""")
            .findAll(dump).firstOrNull { it.groupValues[1] == packageName } ?: return null
        val state = Regex("""(?m)^\s*User 0:.*$""").find(block.groupValues[2])?.value ?: return null
        return Regex("""\bsuspended=true\b""").containsMatchIn(state)
    }

    private fun persist(context: Context, packageName: String, frozen: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packages = prefs.getStringSet(KEY_FROZEN, emptySet()).orEmpty().toMutableSet()
        if (frozen) packages.add(packageName) else packages.remove(packageName)
        prefs.edit().putStringSet(KEY_FROZEN, packages).apply()
    }

    private fun hasRoot(): Boolean {
        rootAvailableCache?.let { return it }
        val detected = runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            if (!process.waitFor(700, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                false
            } else process.exitValue() == 0
        }.getOrDefault(false)
        rootAvailableCache = detected
        return detected
    }

    private fun runShell(command: String): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exit = process.waitFor()
        (exit == 0) to output
    }.getOrElse { false to (it.message ?: "Root command failed") }

    private const val PREFS = "oea_app_freezer"
    private const val KEY_FROZEN = "frozen_packages"
}
