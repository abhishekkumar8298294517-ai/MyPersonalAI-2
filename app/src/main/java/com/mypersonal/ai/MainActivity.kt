package com.mypersonal.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private lateinit var dbHelper: MemoryDbHelper

    private val defaultPrompt =
        "You are my personal, private AI assistant. Speak in natural Hinglish (mix of Hindi and English) like a smart, chill friend, but act with JARVIS-level efficiency. Keep replies short, direct, and helpful."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = MemoryDbHelper(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val lockEnabledInitially = prefs.getBoolean("lock_enabled", false)

        setContent {
            var isUnlocked by remember { mutableStateOf(!lockEnabledInitially) }

            LaunchedEffect(lockEnabledInitially) {
                if (lockEnabledInitially && !isUnlocked) {
                    authenticateUser(
                        onSuccess = { isUnlocked = true },
                        onFallback = { isUnlocked = true }
                    )
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                if (isUnlocked) {
                    AssistantScreen(
                        prefs = prefs,
                        dbHelper = dbHelper,
                        defaultPrompt = defaultPrompt,
                        onSpeak = { text -> speakText(text) },
                        onStopSpeak = { tts?.stop() },
                        onLockNow = { isUnlocked = false }
                    )
                } else {
                    LockedScreen(
                        onUnlockClick = {
                            authenticateUser(
                                onSuccess = { isUnlocked = true },
                                onFallback = { isUnlocked = true }
                            )
                        }
                    )
                }
            }
        }
    }

    private fun speakText(rawText: String) {
        val clean = rawText.replace(Regex("[*#`_~]"), "").trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ai_reply")
    }

    private fun authenticateUser(onSuccess: () -> Unit, onFallback: () -> Unit) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onFallback()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
            }
        )

        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Personal AI")
            .setSubtitle("Verify Fingerprint or PIN")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            infoBuilder.setAllowedAuthenticators(authenticators)
        } else {
            infoBuilder.setNegativeButtonText("Cancel")
        }

        prompt.authenticate(infoBuilder.build())
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        dbHelper.close()
        super.onDestroy()
    }
}

@Composable
fun LockedScreen(onUnlockClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F111A)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Personal AI Locked", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onUnlockClick) {
                Text("Unlock with Fingerprint / PIN")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    prefs: android.content.SharedPreferences,
    dbHelper: MemoryDbHelper,
    defaultPrompt: String,
    onSpeak: (String) -> Unit,
    onStopSpeak: () -> Unit,
    onLockNow: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var assistantName by remember { mutableStateOf(prefs.getString("assistant_name", "MYRAA") ?: "MYRAA") }
    var systemPrompt by remember { mutableStateOf(prefs.getString("system_prompt", defaultPrompt) ?: defaultPrompt) }
    var lockEnabled by remember { mutableStateOf(prefs.getBoolean("lock_enabled", false)) }

    var messages by remember { mutableStateOf(dbHelper.getRecentMessages(50)) }
    var memories by remember { mutableStateOf(dbHelper.getAllMemories()) }
    var inputText by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }

    fun sendUserMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || isThinking) return
        inputText = ""
        dbHelper.addMessage("user", clean)
        messages = dbHelper.getRecentMessages(50)
        isThinking = true

        scope.launch {
            val reply = GeminiClient.sendMessage(
                apiKey = apiKey,
                systemPrompt = systemPrompt,
                memories = memories,
                history = messages.dropLast(1),
                userMessage = clean
            )
            dbHelper.addMessage("model", reply)
            messages = dbHelper.getRecentMessages(50)
            isThinking = false
            onSpeak(reply)
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                sendUserMessage(spoken)
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F111A),
        topBar = {
            TopAppBar(
                title = { Text(assistantName, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF171A26),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { onStopSpeak() }) {
                        Icon(Icons.Default.VolumeOff, contentDescription = "Stop Voice", tint = Color.White)
                    }
                    IconButton(onClick = { showMemoryDialog = true }) {
                        Icon(Icons.Default.Psychology, contentDescription = "Memory", tint = Color(0xFF4FC3F7))
                    }
                    if (lockEnabled) {
                        IconButton(onClick = onLockNow) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "Namaste! Settings icon par tap karke apni Gemini API key daalein, phir Mic dabakar baat karein.",
                            color = Color.Gray,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(messages) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isUser) Color(0xFF1E88E5) else Color(0xFF232738),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Text(
                                text = msg.text,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 15.sp
                            )
                        }
                    }
                }
                if (isThinking) {
                    item {
                        Text("Soch raha hun...", color = Color(0xFF4FC3F7), modifier = Modifier.padding(8.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171A26))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Kuch likhein ya Mic dabayein...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = { sendUserMessage(inputText) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xFF1E88E5), CircleShape)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                } else {
                    IconButton(
                        onClick = {
                            onStopSpeak()
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
                            }
                            voiceLauncher.launch(intent)
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF00ACC1), CircleShape)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Speak", tint = Color.White)
                    }
                }
            }
        }
    }

    if (showSettings) {
        var tempKey by remember { mutableStateOf(apiKey) }
        var tempName by remember { mutableStateOf(assistantName) }
        var tempPrompt by remember { mutableStateOf(systemPrompt) }
        var tempLock by remember { mutableStateOf(lockEnabled) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("Gemini API Key") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Assistant Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tempPrompt,
                        onValueChange = { tempPrompt = it },
                        label = { Text("Personality Prompt") },
                        maxLines = 4
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Fingerprint / PIN Lock")
                        Switch(checked = tempLock, onCheckedChange = { tempLock = it })
                    }
                    TextButton(onClick = {
                        dbHelper.clearMessages()
                        messages = emptyList()
                    }) {
                        Text("Clear Chat History", color = Color(0xFFEF5350))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    apiKey = tempKey.trim()
                    assistantName = tempName.trim().ifEmpty { "MYRAA" }
                    systemPrompt = tempPrompt.trim().ifEmpty { defaultPrompt }
                    lockEnabled = tempLock
                    prefs.edit()
                        .putString("api_key", apiKey)
                        .putString("assistant_name", assistantName)
                        .putString("system_prompt", systemPrompt)
                        .putBoolean("lock_enabled", lockEnabled)
                        .apply()
                    showSettings = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Cancel") }
            }
        )
    }

    if (showMemoryDialog) {
        var newFact by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("Local Memory (On-Device)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newFact,
                            onValueChange = { newFact = it },
                            label = { Text("Add note / fact") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            if (newFact.isNotBlank()) {
                                dbHelper.addMemory(newFact)
                                memories = dbHelper.getAllMemories()
                                newFact = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(memories) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("• ${item.fact}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                                IconButton(onClick = {
                                    dbHelper.deleteMemory(item.id)
                                    memories = dbHelper.getAllMemories()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMemoryDialog = false }) { Text("Done") }
            }
        )
    }
}

