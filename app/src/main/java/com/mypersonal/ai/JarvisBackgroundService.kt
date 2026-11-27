package com.mypersonal.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class JarvisBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_always_on_channel"
        const val NOTIF_ID = 1001
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var dbHelper: MemoryDbHelper

    private var isAwaitingCommand = false
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dbHelper = MemoryDbHelper(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Listening for wake word..."))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }

        mainHandler.postDelayed({ startListeningCycle() }, 500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListeningCycle() {
        if (!isRunning || isProcessing) return
        try {
            speechRecognizer?.destroy()
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        scheduleRestart(600)
                    }

                    override fun onResults(results: Bundle?) {
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        handleSpokenText(spoken)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleRestart(1500)
        }
    }

    private fun handleSpokenText(spoken: String) {
        if (spoken.isEmpty()) {
            scheduleRestart(400)
            return
        }

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val wakeWord = (prefs.getString("wake_word", "jarvis") ?: "jarvis").lowercase().trim()
        val lower = spoken.lowercase()

        if (!isAwaitingCommand) {
            if (lower.contains(wakeWord)) {
                // Check if user spoke wake word + command in one sentence: e.g., "Hey Jarvis open YouTube"
                val afterWake = lower.substringAfter(wakeWord).trim()
                if (afterWake.length > 2) {
                    processCommand(afterWake)
                } else {
                    isAwaitingCommand = true
                    updateNotification("Awake! Listening for your command...")
                    speakAndResume("Yes boss, bolo?", resumeAfter = true)
                }
            } else {
                scheduleRestart(300)
            }
        } else {
            isAwaitingCommand = false
            updateNotification("Listening for '$wakeWord'...")
            processCommand(spoken)
        }
    }

    private fun processCommand(command: String) {
        isProcessing = true
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // First check if it's a direct phone action (WhatsApp, YouTube, Chrome, App Open, Alarm)
        val actionReply = ActionExecutor.tryExecuteCommand(this, command)
        if (actionReply != null) {
            dbHelper.addMessage("user", command)
            dbHelper.addMessage("model", actionReply)
            isProcessing = false
            speakAndResume(actionReply, resumeAfter = true)
            return
        }

        // Otherwise ask Gemini AI
        val apiKey = prefs.getString("api_key", "") ?: ""
        val langMode = prefs.getString("lang_mode", "Hinglish") ?: "Hinglish"
        val basePrompt = prefs.getString("system_prompt", "") ?: ""
        val langInstruction = if (langMode == "English") {
            " Respond strictly in natural, smart English."
        } else {
            " Respond in natural Hinglish (mix of Hindi and English) like a smart, chill friend."
        }

        dbHelper.addMessage("user", command)
        serviceScope.launch {
            val reply = GeminiClient.sendMessage(
                apiKey = apiKey,
                systemPrompt = basePrompt + langInstruction,
                memories = dbHelper.getAllMemories(),
                history = dbHelper.getRecentMessages(12),
                userMessage = command
            )
            dbHelper.addMessage("model", reply)
            isProcessing = false
            speakAndResume(reply, resumeAfter = true)
        }
    }

    private fun speakAndResume(text: String, resumeAfter: Boolean) {
        val clean = text.replace(Regex("[*#`_~]"), "").trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "bg_reply")
        if (resumeAfter) {
            val estimatedMs = (clean.length * 65L).coerceIn(1500L, 9000L)
            scheduleRestart(estimatedMs)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ startListeningCycle() }, delayMs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Always-On Voice",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_jarvis_logo)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        dbHelper.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
