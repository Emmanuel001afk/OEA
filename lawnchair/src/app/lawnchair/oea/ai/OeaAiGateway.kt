package app.lawnchair.oea.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object OeaAiGateway {
    suspend fun complete(context:Context,prompt:String):Result<String> = withContext(Dispatchers.IO) {
        val c=OeaAiStore.get(context)
        if(!c.enabled) return@withContext Result.failure(IllegalStateException("OEA AI is disabled"))
        if(c.endpoint.isBlank()) return@withContext Result.failure(IllegalStateException("No AI API endpoint configured"))
        runCatching {
            val x=(URL(c.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod="POST"; connectTimeout=15000; readTimeout=30000; doOutput=true
                setRequestProperty("Content-Type","application/json")
                if(c.apiKey.isNotBlank()) setRequestProperty("Authorization","Bearer "+c.apiKey)
            }
            val promptJson=prompt.replace("\\","\\\\").replace(""","\\"").replace("
","\\n")
            val model=if(c.model.isBlank()) "" else ","model":""+c.model.replace(""","\\"")+"""
            x.outputStream.use{it.write(("{"prompt":""+promptJson+"""+model+"}").toByteArray())}
            val code=x.responseCode
            val body=(if(code in 200..299)x.inputStream else x.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            x.disconnect()
            if(code !in 200..299) error("AI API returned HTTP "+code+": "+body)
            body
        }
    }
}
