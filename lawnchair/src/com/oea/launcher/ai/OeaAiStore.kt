package com.oea.launcher.ai

import android.content.Context

data class OeaAiConfig(val enabled:Boolean=false,val endpoint:String="",val apiKey:String="",val model:String="")

object OeaAiStore {
    private const val P="oea_ai"
    private fun p(c:Context)=c.applicationContext.getSharedPreferences(P,Context.MODE_PRIVATE)
    fun get(c:Context)=OeaAiConfig(p(c).getBoolean("enabled",false),p(c).getString("endpoint","").orEmpty(),p(c).getString("apiKey","").orEmpty(),p(c).getString("model","").orEmpty())
    fun save(c:Context,v:OeaAiConfig)=p(c).edit().putBoolean("enabled",v.enabled).putString("endpoint",v.endpoint.trim()).putString("apiKey",v.apiKey).putString("model",v.model.trim()).apply()
}
