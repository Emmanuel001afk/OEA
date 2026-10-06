package com.oea.launcher.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object OeaAiGateway {
    suspend fun complete(context: Context, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val config = OeaAiStore.get(context)
        if (!config.enabled) return@withContext Result.failure(IllegalStateException("OEA AI is disabled"))
        if (config.endpoint.isBlank()) return@withContext Result.failure(IllegalStateException("No AI API endpoint configured"))

        runCatching {
            val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + config.apiKey)
            }

            fun jsonEscape(value: String): String = buildString(value.length + 8) {
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }

            val modelField = if (config.model.isBlank()) "" else ",\"model\":\"" + jsonEscape(config.model) + "\""
            val body = "{\"prompt\":\"" + jsonEscape(prompt) + "\"" + modelField + "}"
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) error("AI API returned HTTP $code: $response")
            response
        }
    }
}
