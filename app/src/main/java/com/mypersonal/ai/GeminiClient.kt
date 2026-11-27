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
    private var cachedModelPath: String? = null
    private var cachedForKey: String? = null

    private fun extractGoogleError(bodyStr: String): String {
        return try {
            JSONObject(bodyStr)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown API error"
        } catch (_: Exception) {
            "Invalid server response"
        }
    }

    private fun resolveActiveModel(cleanKey: String): Pair<String?, String?> {
        if (cachedForKey == cleanKey && cachedModelPath != null) {
            return Pair(cachedModelPath, null)
        }

        val listRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey")
            .get()
            .build()

        return try {
            client.newCall(listRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Pair(null, "API Error (${response.code}): ${extractGoogleError(bodyStr)}")
                }

                val models = JSONObject(bodyStr).optJSONArray("models") ?: JSONArray()
                var bestFlashModel: String? = null
                var fallbackGeminiModel: String? = null

                for (i in 0 until models.length()) {
                    val obj = models.optJSONObject(i) ?: continue
                    val name = obj.optString("name") // e.g., "models/gemini-..."
                    val methods = obj.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()

                    if (methods.contains("generateContent") && name.contains("gemini", ignoreCase = true)) {
                        if (fallbackGeminiModel == null) {
                            fallbackGeminiModel = name
                        }
                        if (name.contains("flash", ignoreCase = true) &&
                            !name.contains("lite", ignoreCase = true) &&
                            !name.contains("image", ignoreCase = true) &&
                            !name.contains("audio", ignoreCase = true)
                        ) {
                            bestFlashModel = name
                            break
                        }
                    }
                }

                val selected = bestFlashModel ?: fallbackGeminiModel
                if (selected != null) {
                    cachedModelPath = selected
                    cachedForKey = cleanKey
                    Pair(selected, null)
                } else {
                    Pair(null, "Is API key ke liye koi supported Gemini text model nahi mila.")
                }
            }
        } catch (e: Exception) {
            Pair(null, "Network error: ${e.localizedMessage ?: "Internet check karein"}")
        }
    }

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

        val (modelPath, resolveError) = resolveActiveModel(cleanKey)
        if (modelPath == null) {
            return@withContext resolveError ?: "Model load nahi ho paya."
        }

        val memoryBlock = if (memories.isNotEmpty()) {
            val facts = memories.joinToString("\n") { "- ${it.fact}" }
            "\n\nSaved personal facts about the user (use these naturally when relevant):\n$facts"
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

        val url = "https://generativelanguage.googleapis.com/v1beta/$modelPath:generateContent?key=$cleanKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    cachedModelPath = null
                    return@withContext "API Error (${response.code}): ${extractGoogleError(bodyStr)}"
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
