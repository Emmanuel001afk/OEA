package app.lawnchair.oea.agent

import android.content.Context
import app.lawnchair.oea.engine.OeaEngine
import java.util.Locale

/**
 * OEA deterministic task/command layer.
 *
 * This is deliberately rule-based. An AI layer can later propose actions, but execution
 * remains owned by OEA and goes through OeaEngine.
 */
class OeaAgent private constructor(private val context: Context) {
    private val engine = OeaEngine.get(context)

    fun handle(command: String): OeaEngine.Result {
        val normalized = command.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return OeaEngine.Result.Failure("Empty command")

        if (normalized == "home settings" || normalized == "open home settings") {
            return engine.execute(OeaEngine.Action.OpenHomeSettings)
        }

        val target = normalized
            .removePrefix("open ")
            .removePrefix("launch ")
            .trim()
        if (target.isEmpty()) return OeaEngine.Result.Failure("Tell OEA what to open")

        val resolve = context.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
            0,
        ).firstOrNull { info ->
            info.loadLabel(context.packageManager).toString().lowercase(Locale.ROOT) == target
                || info.activityInfo.packageName.lowercase(Locale.ROOT) == target
        } ?: return OeaEngine.Result.Failure("I couldn't find an app named \\$target")

        return engine.execute(OeaEngine.Action.LaunchPackage(resolve.activityInfo.packageName))
    }

    companion object {
        fun get(context: Context): OeaAgent = OeaAgent(context.applicationContext)
    }
}
