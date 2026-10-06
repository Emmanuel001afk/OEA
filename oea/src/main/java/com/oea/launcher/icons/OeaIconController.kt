package com.oea.launcher.icons
import android.content.Context
import android.graphics.drawable.Drawable
class OeaIconController(private val context:Context){private val cache=mutableMapOf<String,Drawable.ConstantState?>();fun icon(packageName:String):Drawable?{if(!cache.containsKey(packageName))cache[packageName]=runCatching{context.packageManager.getApplicationIcon(packageName).constantState}.getOrNull();return cache[packageName]?.newDrawable(context.resources)}}
