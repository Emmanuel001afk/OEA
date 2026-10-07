package com.oea.launcher.model

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import com.oea.launcher.OeaLauncherActivity

data class OeaAppInfo(val packageName:String,val className:String,val label:String)
class OeaAppModel(private val context:Context){
 var apps:List<OeaAppInfo> = emptyList(); private set
 fun load(){ val intent=Intent(Intent.ACTION_MAIN).apply{addCategory(Intent.CATEGORY_LAUNCHER)}; apps=context.packageManager.queryIntentActivities(intent,0).map{info:ResolveInfo->OeaAppInfo(info.activityInfo.packageName,info.activityInfo.name,info.loadLabel(context.packageManager).toString())}.distinctBy{ComponentName(it.packageName,it.className)}.filterNot{it.packageName==context.packageName&&it.className==OeaLauncherActivity::class.java.name}.sortedBy{it.label.lowercase()} }
}
