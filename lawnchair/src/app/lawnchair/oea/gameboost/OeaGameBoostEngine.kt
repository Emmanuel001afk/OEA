package app.lawnchair.oea.gameboost

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

sealed interface OeaObserved<out T> {
    data class Value<T>(val value: T, val source: String = "Android") : OeaObserved<T>
    data class Restricted(val reason: String) : OeaObserved<Nothing>
}

data class OeaGameTelemetry(
    val memory: OeaObserved<ActivityManager.MemoryInfo>,
    val batteryTemperatureC: OeaObserved<Float>,
    val powerSave: OeaObserved<Boolean>
)

class OeaGameBoostEngine(private val context: Context) {
    private var active: OeaGameProfile? = null

    fun enter(profile: OeaGameProfile) { active = profile }
    fun exit() { active = null }

    fun telemetry(): OeaGameTelemetry {
        val manager = context.getSystemService(ActivityManager::class.java)
        if (manager == null) return OeaGameTelemetry(
            OeaObserved.Restricted("ActivityManager unavailable"),
            OeaObserved.Restricted("BatteryManager unavailable"),
            OeaObserved.Restricted("PowerManager unavailable")
        )
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temp = if (rawTemp == null || rawTemp == Int.MIN_VALUE) OeaObserved.Restricted("Battery temperature unavailable")
            else OeaObserved.Value(rawTemp / 10f)
        val power = context.getSystemService(PowerManager::class.java)
        val save = power?.isPowerSaveMode
        return OeaGameTelemetry(
            OeaObserved.Value(memory),
            temp,
            if (save == null) OeaObserved.Restricted("Power state unavailable") else OeaObserved.Value(save)
        )
    }
}
