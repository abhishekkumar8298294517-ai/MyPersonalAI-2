package com.mypersonal.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendMessage(
        apiKey: String,
        systemPrompt: String,
        memories: List<MemoryItem>,
        history: List<ChatMessage>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext "Pehle Settings icon par tap karke apni Gemini API key save karein."
        }

        val memoryBlock = if (memories.isNotEmpty()) {
            val facts = memories.joinToString("\n") { "- ${it.fact}" }
            "\n\nSaved personal facts about the user (use naturally when relevant):\n$facts"
        } else {
            ""
        }

        val fullSystemInstruction = systemPrompt.trim() + memoryBlock

        val contentsArray = JSONArray()
        history.takeLast(10).forEach { msg ->
            contentsArray.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                }
            )
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }
        )

        val requestBodyJson = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", fullSystemInstruction))
                )
            )
            put("contents", contentsArray)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$cleanKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext "API Error (${response.code}): Apni API key ya internet check karein."
                }
                val json = JSONObject(bodyStr)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")

                if (text.isNullOrBlank()) {
                    "Mujhe abhi koi response nahi mila, dubara try karein."
                } else {
                    text.trim()
                }
            }
        } catch (e: Exception) {
            "Network error: ${e.localizedMessage ?: "Internet check karein"}"
        }
    }
}
