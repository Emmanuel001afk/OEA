package com.oea.launcher.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.oea.launcher.data.OeaDataStore

class OeaEngine private constructor(private val context: Context) {
    sealed interface Result { data class Success(val message: String): Result; data class Failure(val message: String, val cause: Throwable?=null): Result }
    fun execute(action: Action): Result {
        val result = runCatching { when(action) {
            is Action.LaunchActivity -> launchActivity(action.packageName, action.className)
            Action.OpenHomeSettings -> { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Result.Success("Opened Home settings") }
        }}.getOrElse { Result.Failure(it.message ?: "OEA action failed", it) }
        OeaDataStore.get(context).recordAction(action.describe(), result.toMessage()); return result
    }
    private fun launchActivity(packageName:String,className:String):Result {
        if(packageName.isBlank()||className.isBlank()) return Result.Failure("Invalid launch target")
        context.startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER); component=ComponentName(packageName,className); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return Result.Success("Opened $packageName")
    }
    private fun Result.toMessage()=when(this){is Result.Success->message;is Result.Failure->message}
    companion object { fun get(context:Context)=OeaEngine(context.applicationContext) }
    sealed interface Action { fun describe():String; data class LaunchActivity(val packageName:String,val className:String):Action{override fun describe()="launch:$packageName/$className"}; data object OpenHomeSettings:Action{override fun describe()="open-home-settings"} }
}
