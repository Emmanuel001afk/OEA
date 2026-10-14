package com.oea.launcher.perf

import android.content.Context
import android.content.Intent
import java.io.File
import kotlin.math.min
import com.oea.engine.OeaEngine
import com.oea.launcher.R
import com.oea.launcher.data.OeaDataStore

/**
 * Real fixes for a game that crashed or hit a hardware limit, offered from
 * inside the game instead of as a force-close.
 *
 * The options are chosen from the fault itself, because the causes differ and a
 * generic "please try again" wastes the diagnosis:
 * - `OutOfMemoryError` / allocation faults on a big scene -> render smaller;
 * - a fault inside the first frames or while loading -> the saved match is
 *   almost always the culprit, so offer to clear only that game's data;
 * - a fault naming island/overlay code -> the in-game Dynamic Island is involved;
 * - a GL/EGL fault -> the GPU context is at fault: drop quality one tier.
 *
 * Everything here is best effort and never throws: a rescue action that fails
 * must not turn a contained game fault into a launcher problem.
 */
object OeaGameRescue {
    /** One button on the game's failure screen. */
    class Option(
        val label: String,
        val icon: Int,
        /** True when the action needs a fresh game process (i.e. a retry). */
        val restart: Boolean,
        val run: () -> Unit,
    )

    /** The standalone APKs OEA can hand a game over to — full process isolation. */
    private val standalonePackages = mapOf(
        "arena" to "com.oea.arena",
        "chess" to "com.oea.chess",
        "runner" to "com.oea.runner",
    )

    fun options(context: Context, gameId: String, error: Throwable?): List<Option> {
        val signal = error.summary()
        val out = ArrayList<Option>()

        if (signal.callsIsland) {
            out += Option(
                "Hide the island in games", R.drawable.oic_layers, restart = true,
                run = { OeaEngine.settings.islandInGames = false },
            )
        }
        if (signal.memory || signal.graphics) {
            out += Option(
                "Lower game quality", R.drawable.oic_tune, restart = true,
                run = { lowerQuality() },
            )
        }
        if (signal.early || signal.corrupt) {
            out += Option(
                "Reset this game's data", R.drawable.oic_delete, restart = true,
                run = { clearSaveData(context, gameId) },
            )
        }
        if (signal.frameFault) {
            // The device stopped responding while the game ran: ask for less work
            // per second, which is the only change that reliably helps a phone
            // that is throttling or has been left with too little headroom.
            out += Option(
                "Cap the frame rate at 60", R.drawable.oic_bolt_fill, restart = true,
                run = {
                    val target = OeaEngine.settings.fpsTarget
                    OeaEngine.settings.fpsTarget = min(target.takeIf { it > 0 } ?: 120, 60)
                    OeaEngine.settings.adaptiveResolution = true
                },
            )
        }
        val standalone = standalonePackages[gameId]
        if (standalone != null && launchable(context, standalone)) {
            out += Option(
                "Open as its own app", R.drawable.oic_open_in_new, restart = false,
                run = {
                    context.packageManager.getLaunchIntentForPackage(standalone)
                        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            )
        }
        // Always available, always safe: the generic path down.
        if (out.isEmpty()) {
            out += Option("Lower game quality", R.drawable.oic_tune, restart = true) { lowerQuality() }
            out += Option("Reset this game's data", R.drawable.oic_delete, restart = true) { clearSaveData(context, gameId) }
        }
        return out
    }

    /**
     * Steps the quality tier down one level (never below low). Returns the label
     * of the tier now in force, so the caller can say what changed.
     */
    fun lowerQuality(): String {
        val settings = OeaEngine.settings
        // "auto" resolves from the measured device profile; stepping below it by
        // one is a good guess for a mid-range phone and never makes things worse.
        val current = settings.quality
        val next = when (current) {
            "ultra" -> "high"
            "high" -> "medium"
            "medium" -> "low"
            "low" -> "low"
            else -> "medium"
        }
        settings.quality = next
        settings.adaptiveResolution = true
        if (settings.fpsTarget == 0 || settings.fpsTarget > 60) settings.fpsTarget = 60
        return next.replaceFirstChar { it.uppercase() }
    }

    /**
     * Deletes only this game's own records (engine prefs + its files folder). A
     * corrupt save is a common reason a game dies the moment it opens, and this
     * is the one repair that fixes it — so it is scoped to one game and never
     * touches OEA's layout, settings or other games.
     */
    fun clearSaveData(context: Context, gameId: String): Boolean {
        if (gameId.isBlank()) return false
        var cleared = false
        runCatching { context.deleteSharedPreferences("oea_game_$gameId") }.onSuccess { cleared = true }
        runCatching {
            val dir = File(context.filesDir, "games/$gameId")
            if (dir.exists()) {
                dir.deleteRecursively()
                cleared = true
            }
        }
        runCatching { OeaDataStore.get(context).markUnstable(gameId, false) }
        return cleared
    }

    /** Whether the standalone build of this game is installed and can be launched. */
    fun launchable(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    fun standaloneFor(gameId: String): String? = standalonePackages[gameId]

    /** True when the game keeps stopping and the hub should lead with a repair. */
    fun isUnstable(context: Context, gameId: String): Boolean = runCatching {
        OeaDataStore.get(context).unstableGames().contains(gameId)
    }.getOrDefault(false)

    /** Clears the unstable mark after a successful run. */
    fun noteRunOk(context: Context, gameId: String) {
        runCatching { OeaDataStore.get(context).markUnstable(gameId, false) }
    }

    // ------------------------------------------------------------------ diagnosis

    private class Signal(
        val memory: Boolean,
        val graphics: Boolean,
        val early: Boolean,
        val corrupt: Boolean,
        val callsIsland: Boolean,
        val frameFault: Boolean,
    )

    private fun Throwable?.summary(): Signal {
        val error = this
        val text = buildString {
            var cursor: Throwable? = error
            var depth = 0
            while (cursor != null && depth < 6) {
                append(cursor.javaClass.name).append(' ').append(cursor.message).append('\n')
                cursor = cursor.cause
                depth++
            }
            error?.stackTrace?.take(16)?.forEach { append(it.className).append(it.methodName).append('\n') }
        }.lowercase()
        val crashedEarly = error?.stackTrace?.any {
            it.methodName == "onCreate" || it.methodName == "load" || it.methodName == "restore" || it.methodName == "decode"
        } == true
        return Signal(
            memory = error is OutOfMemoryError || "outofmemory" in text || "failed to allocate" in text || "heap size" in text,
            graphics = "egl" in text || "gles" in text || "glerror" in text || "vulkan" in text || "gpu" in text || "graphics" in text,
            early = crashedEarly,
            corrupt = "json" in text || "serializ" in text || "eof" in text || "notmarked" in text ||
                "indexoutofbounds" in text || ("nullpointer" in text && crashedEarly),
            callsIsland = "island" in text || "liveactivity" in text || "overlay" in text,
            frameFault = "nativecrash" in text || "sigsegv" in text || "abort" in text || "watchdog" in text || "unrespons" in text,
        )
    }

}
