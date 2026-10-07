package com.oea.launcher.engine

import android.content.Context
import android.content.Intent
import com.oea.launcher.model.OeaAppInfo
import com.oea.launcher.model.OeaAppModel
import com.oea.launcher.workspace.OeaWorkspace

class OeaLauncherEngine(private val context: Context) {
    val model=OeaAppModel(context); val workspace=OeaWorkspace(context); private var lastBoundApps:List<OeaAppInfo> = emptyList()
    fun start(){ model.load(); if(model.apps != lastBoundApps) { workspace.bind(model.apps); lastBoundApps = model.apps.toList() } }
    fun launchApp(packageName:String,className:String){context.startActivity(Intent(Intent.ACTION_MAIN).apply{addCategory(Intent.CATEGORY_LAUNCHER);setClassName(packageName,className);addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})}
}
