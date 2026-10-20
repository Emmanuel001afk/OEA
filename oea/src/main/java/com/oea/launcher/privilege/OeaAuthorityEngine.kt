package com.oea.launcher.privilege

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OEA's shared authority API.
 *
 * Features call this API rather than opening their own ADB connections or
 * inventing per-feature shell workarounds. Requests are typed and validated;
 * this is deliberately not an unrestricted command-string API.
 *
 * This is the in-app capability layer over OEA's current Wireless Debugging
 * shell bridge. A persistent app_process/Binder system-API server is a separate
 * architectural step and must not be reported as available until implemented
 * and verified on-device.
 */
object OeaAuthorityEngine {
    enum class Level { AVAILABLE, LIMITED, UNAVAILABLE, UNKNOWN }

    enum class CapabilityId {
        WIRELESS_SHELL,
        PACKAGE_ENUMERATION,
        PACKAGE_SUSPENSION,
        APP_OPS_INSPECTION,
        SYSTEM_SERVICE_INSPECTION,
        SYSTEM_PROPERTY_READ,
        DEVICE_OWNER,
        ROOT
    }

    data class Capability(
        val id: CapabilityId,
        val level: Level,
        val evidence: String,
        val detail: String
    )

    data class Snapshot(
        val checkedAtMillis: Long,
        val androidApi: Int,
        val shellIdentity: String?,
        val capabilities: List<Capability>
    ) {
        fun capability(id: CapabilityId): Capability? = capabilities.firstOrNull { it.id == id }
    }

    sealed interface Request {
        data object InspectCapabilities : Request
        data class ReadProperty(val name: String) : Request
        data class ListPackages(val thirdPartyOnly: Boolean = false) : Request
        data class ReadSystemService(val service: String) : Request
        data class SuspendPackage(val packageName: String, val suspended: Boolean) : Request
        data class ReadAppOps(val packageName: String) : Request
    }

    data class Result(
        val success: Boolean,
        val operation: String,
        val output: String,
        val capability: CapabilityId? = null
    )

    /**
     * Discover real capabilities from command results. Call from a worker
     * coroutine; it performs several live shell probes.
     */
    suspend fun inspect(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        // Never execute "su" during a scan: some root managers would display an
        // unexpected authorization prompt. Presence is only a candidate signal,
        // not proof that OEA has been granted root.
        val rootCandidate = runCatching {
            val paths = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/data/local/xbin/su",
                "/data/local/bin/su", "/debug_ramdisk/su"
            )
            paths.any { java.io.File(it).exists() } ||
                System.getenv("PATH").orEmpty().split(':').any {
                    it.isNotBlank() && java.io.File(it, "su").exists()
                }
        }.getOrDefault(false)

        val bridgeReady = OeaAdbAuthority.isSessionReady()
        val caps = mutableListOf<Capability>()
        var identity: String? = null

        if (bridgeReady) {
            val id = OeaAdbAuthority.runShell("id")
            if (id.first && (id.second.contains("uid=2000") || id.second.contains("uid=0"))) {
                identity = id.second.trim()
                caps += Capability(CapabilityId.WIRELESS_SHELL, Level.AVAILABLE, "Live id probe", identity)
            } else {
                caps += Capability(CapabilityId.WIRELESS_SHELL, Level.UNAVAILABLE, "Live id probe failed", id.second)
            }

            fun probe(
                id: CapabilityId,
                command: String,
                success: (String) -> Boolean = { it.isNotBlank() },
                availableDetail: String
            ) {
                val result = OeaAdbAuthority.runShell(command)
                val ok = result.first && success(result.second)
                caps += Capability(
                    id,
                    if (ok) Level.AVAILABLE else Level.UNAVAILABLE,
                    if (ok) "Command executed successfully" else "Command failed or returned no usable evidence",
                    if (ok) availableDetail + if (result.second.isNotBlank()) "\n" + result.second.take(1800) else ""
                    else result.second.take(1200).ifBlank { "No output returned" }
                )
            }

            probe(CapabilityId.PACKAGE_ENUMERATION, "pm list packages -3",
                { it.lineSequence().any { line -> line.startsWith("package:") } },
                "Android returned third-party packages.")
            probe(CapabilityId.PACKAGE_SUSPENSION, "cmd package help",
                { it.contains("suspend", ignoreCase = true) },
                "Package manager advertises suspension commands; individual package operations can still be refused.")
            probe(CapabilityId.APP_OPS_INSPECTION, "cmd appops help",
                { it.contains("get", ignoreCase = true) || it.contains("appops", ignoreCase = true) },
                "App-ops tooling is present; write permission is checked per operation.")
            probe(CapabilityId.SYSTEM_SERVICE_INSPECTION, "dumpsys battery",
                { it.contains("level", ignoreCase = true) || it.contains("Battery", ignoreCase = true) },
                "Android returned battery service state.")
            probe(CapabilityId.SYSTEM_PROPERTY_READ, "getprop ro.build.version.sdk",
                { it.trim().toIntOrNull() != null },
                "Android build properties are readable.")
        } else {
            caps += Capability(
                CapabilityId.WIRELESS_SHELL, Level.UNAVAILABLE,
                "No live OEA ADB shell session",
                "Pair/reconnect through OEA Authority before shell-backed capabilities can be checked."
            )
            listOf(
                CapabilityId.PACKAGE_ENUMERATION,
                CapabilityId.PACKAGE_SUSPENSION,
                CapabilityId.APP_OPS_INSPECTION,
                CapabilityId.SYSTEM_SERVICE_INSPECTION,
                CapabilityId.SYSTEM_PROPERTY_READ
            ).forEach {
                caps += Capability(it, Level.UNKNOWN, "Not probed", "A live Wireless Debugging session is required for this probe.")
            }
        }

        caps += Capability(
            CapabilityId.DEVICE_OWNER,
            if (dpm?.isDeviceOwnerApp(context.packageName) == true) Level.AVAILABLE else Level.UNAVAILABLE,
            "DevicePolicyManager.isDeviceOwnerApp",
            if (dpm?.isDeviceOwnerApp(context.packageName) == true) "OEA is device owner." else "OEA is not device owner; ordinary Device Admin is not equivalent."
        )
        caps += Capability(
            CapabilityId.ROOT,
            if (rootCandidate) Level.LIMITED else Level.UNAVAILABLE,
            "Non-invasive su binary presence check",
            if (rootCandidate) "A su binary exists, but OEA has not tested or requested root authorization. This is not proof that root is usable."
            else "No known su binary was found. The scan did not request root authorization."
        )
        Snapshot(System.currentTimeMillis(), Build.VERSION.SDK_INT, identity, caps)
    }

    /** Execute one validated internal operation through the shared authority bridge. */
    suspend fun execute(context: Context, request: Request): Result = withContext(Dispatchers.IO) {
        when (request) {
            Request.InspectCapabilities -> {
                val snapshot = inspect(context)
                Result(true, "Inspect capabilities", snapshot.capabilities.joinToString("\n\n") {
                    "${it.id}: ${it.level}\nEvidence: ${it.evidence}\n${it.detail}"
                })
            }
            is Request.ReadProperty -> {
                if (!PROPERTY_NAME.matches(request.name)) return@withContext Result(false, "Read property", "Invalid Android property name.", CapabilityId.SYSTEM_PROPERTY_READ)
                val r = OeaAdbAuthority.runShell("getprop ${request.name}")
                Result(r.first, "Read property ${request.name}", r.second, CapabilityId.SYSTEM_PROPERTY_READ)
            }
            is Request.ListPackages -> {
                val cmd = if (request.thirdPartyOnly) "pm list packages -3" else "pm list packages"
                val r = OeaAdbAuthority.runShell(cmd)
                Result(r.first, "List packages", r.second, CapabilityId.PACKAGE_ENUMERATION)
            }
            is Request.ReadSystemService -> {
                if (request.service !in DUMPSYS_SERVICES) return@withContext Result(false, "Inspect system service", "Unsupported service name.", CapabilityId.SYSTEM_SERVICE_INSPECTION)
                val r = OeaAdbAuthority.runShell("dumpsys ${request.service}")
                Result(r.first, "Inspect ${request.service}", r.second, CapabilityId.SYSTEM_SERVICE_INSPECTION)
            }
            is Request.SuspendPackage -> {
                if (!PACKAGE_NAME.matches(request.packageName) || request.packageName == context.packageName) {
                    return@withContext Result(false, "Change package suspension", "Invalid package or OEA itself was selected.", CapabilityId.PACKAGE_SUSPENSION)
                }
                val verb = if (request.suspended) "suspend" else "unsuspend"
                val change = OeaAdbAuthority.runShell("cmd package $verb --user 0 ${request.packageName}")
                if (!change.first) return@withContext Result(false, "Package $verb", change.second, CapabilityId.PACKAGE_SUSPENSION)
                val verify = OeaAdbAuthority.runShell("dumpsys package ${request.packageName}")
                val stateLine = Regex("""(?m)^\\s*User 0:.*$""").find(verify.second)?.value
                if (!verify.first || stateLine == null) {
                    Result(false, "Package $verb", "Android accepted the request but OEA could not verify the per-user state.\n${change.second}\n${verify.second}", CapabilityId.PACKAGE_SUSPENSION)
                } else {
                    val actual = Regex("""\\bsuspended=true\\b""").containsMatchIn(stateLine)
                    val ok = actual == request.suspended
                    Result(ok, "Package $verb", if (ok) "Verified state: suspended=$actual\n${change.second}" else "State mismatch: expected suspended=${request.suspended}, observed suspended=$actual", CapabilityId.PACKAGE_SUSPENSION)
                }
            }
            is Request.ReadAppOps -> {
                if (!PACKAGE_NAME.matches(request.packageName)) return@withContext Result(false, "Read app-ops", "Invalid package name.", CapabilityId.APP_OPS_INSPECTION)
                val r = OeaAdbAuthority.runShell("cmd appops get ${request.packageName}")
                Result(r.first, "Read app-ops ${request.packageName}", r.second, CapabilityId.APP_OPS_INSPECTION)
            }
        }
    }

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val PROPERTY_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    private val DUMPSYS_SERVICES = setOf(
        "activity", "appops", "battery", "connectivity", "deviceidle", "diskstats",
        "input", "meminfo", "notification", "package", "power", "thermalservice",
        "window", "wifi", "accessibility", "cpuinfo"
    )
}
