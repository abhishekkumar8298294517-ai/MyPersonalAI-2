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

    private fun getCandidateModels(cleanKey: String): Pair<List<String>, String?> {
        if (cachedForKey == cleanKey && cachedModelPath != null) {
            return Pair(listOf(cachedModelPath!!), null)
        }

        val listRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .addHeader("x-goog-api-key", cleanKey)
            .get()
            .build()

        return try {
            client.newCall(listRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Pair(emptyList(), "API Error (${response.code}): ${extractGoogleError(bodyStr)}")
                }

                val models = JSONObject(bodyStr).optJSONArray("models") ?: JSONArray()
                val flashModels = mutableListOf<String>()
                val otherGeminiModels = mutableListOf<String>()

                for (i in 0 until models.length()) {
                    val obj = models.optJSONObject(i) ?: continue
                    val name = obj.optString("name") // e.g., "models/gemini-..."
                    val methods = obj.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()

                    if (methods.contains("generateContent") &&
                        name.contains("gemini", ignoreCase = true) &&
                        !name.contains("image", ignoreCase = true) &&
                        !name.contains("audio", ignoreCase = true) &&
                        !name.contains("tts", ignoreCase = true) &&
                        !name.contains("robotics", ignoreCase = true) &&
                        !name.contains("computer-use", ignoreCase = true)
                    ) {
                        if (name.contains("flash", ignoreCase = true)) {
                            flashModels.add(name)
                        } else {
                            otherGeminiModels.add(name)
                        }
                    }
                }

                // Prefer newest flash models first, then other gemini models
                val ordered = (flashModels.reversed() + otherGeminiModels.reversed()).distinct()
                if (ordered.isEmpty()) {
                    Pair(emptyList(), "Is API key ke liye koi supported Gemini text model nahi mila.")
                } else {
                    Pair(ordered, null)
                }
            }
        } catch (e: Exception) {
            Pair(emptyList(), "Network error: ${e.localizedMessage ?: "Internet check karein"}")
        }
    }

    suspend fun sendMessage(
        apiKey: String,
        systemPrompt: String,
        memories: List<MemoryItem>,
        history: List<ChatMessage>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.replace(Regex("\\s+"), "").trim()
        if (cleanKey.isEmpty()) {
            return@withContext "Pehle Settings icon par tap karke apni Gemini API key save karein."
        }

        val (modelsToTry, listError) = getCandidateModels(cleanKey)
        if (modelsToTry.isEmpty()) {
            return@withContext listError ?: "Models fetch nahi ho paye."
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

        val bodyBytes = requestBodyJson.toString().toRequestBody(jsonMediaType)
        var lastError = "Koi bhi model respond nahi kar paya."

        for (modelPath in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/$modelPath:generateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .post(bodyBytes)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val text = JSONObject(bodyStr)
                            .optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text")

                        if (!text.isNullOrBlank()) {
                            cachedModelPath = modelPath
                            cachedForKey = cleanKey
                            return@withContext text.trim()
                        }
                    } else {
                        lastError = "API Error (${response.code}) [$modelPath]: ${extractGoogleError(bodyStr)}"
                        if (response.code == 400 || response.code == 403) {
                            return@withContext lastError
                        }
                        // If 404 or 429, clear cache and try next model in list
                        cachedModelPath = null
                    }
                }
            } catch (e: Exception) {
                lastError = "Network error: ${e.localizedMessage ?: "Internet check karein"}"
            }
        }

        lastError
    }
}
