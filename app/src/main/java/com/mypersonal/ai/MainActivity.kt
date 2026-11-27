package com.mypersonal.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
        "You are JARVIS, my personal private AI assistant. Act with JARVIS-level efficiency and keep replies crisp, smart, and helpful."

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
            .setTitle("Unlock JARVIS AI")
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
fun JarvisCoreOrb(isActive: Boolean, isBgListening: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (isActive) 1.15f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 600 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseRadius = size.minDimension / 2.4f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00E5FF).copy(alpha = 0.35f),
                        Color.Transparent
                    )
                ),
                radius = baseRadius * pulse * 1.25f
            )
            drawCircle(
                color = if (isBgListening) Color(0xFF00E5FF) else Color(0xFF4FC3F7),
                radius = baseRadius * pulse,
                style = Stroke(width = 6f)
            )
            drawCircle(
                color = Color(0xFFFFD54F),
                radius = baseRadius * 0.65f,
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                radius = baseRadius * 0.32f * pulse
            )
        }
    }
}

@Composable
fun LockedScreen(onUnlockClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0E17)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisCoreOrb(isActive = false, isBgListening = false)
            Spacer(modifier = Modifier.height(16.dp))
            Text("JARVIS AI Locked", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var assistantName by remember { mutableStateOf(prefs.getString("assistant_name", "JARVIS") ?: "JARVIS") }
    var wakeWord by remember { mutableStateOf(prefs.getString("wake_word", "jarvis") ?: "jarvis") }
    var langMode by remember { mutableStateOf(prefs.getString("lang_mode", "Hinglish") ?: "Hinglish") }
    var systemPrompt by remember { mutableStateOf(prefs.getString("system_prompt", defaultPrompt) ?: defaultPrompt) }
    var lockEnabled by remember { mutableStateOf(prefs.getBoolean("lock_enabled", false)) }
    var bgWakeEnabled by remember { mutableStateOf(prefs.getBoolean("bg_wake_enabled", false)) }

    var messages by remember { mutableStateOf(dbHelper.getRecentMessages(50)) }
    var memories by remember { mutableStateOf(dbHelper.getAllMemories()) }
    var inputText by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val micGranted = perms[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted && bgWakeEnabled) {
            val svc = Intent(context, JarvisBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }

    LaunchedEffect(bgWakeEnabled) {
        val svc = Intent(context, JarvisBackgroundService::class.java)
        if (bgWakeEnabled) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } else {
                val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    req.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(req.toTypedArray())
            }
        } else {
            context.stopService(svc)
        }
    }

    fun sendUserMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || isThinking) return
        inputText = ""
        dbHelper.addMessage("user", clean)
        messages = dbHelper.getRecentMessages(50)

        // Check phone automation first (WhatsApp, YouTube, Chrome, Open App, Alarm)
        val actionResult = ActionExecutor.tryExecuteCommand(context, clean)
        if (actionResult != null) {
            dbHelper.addMessage("model", actionResult)
            messages = dbHelper.getRecentMessages(50)
            onSpeak(actionResult)
            return
        }

        isThinking = true
        val langSuffix = if (langMode == "English") {
            " Respond strictly in natural, smart English."
        } else {
            " Respond in natural Hinglish (mix of Hindi and English) like a smart, chill friend."
        }

        scope.launch {
            val reply = GeminiClient.sendMessage(
                apiKey = apiKey,
                systemPrompt = systemPrompt + langSuffix,
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
        containerColor = Color(0xFF0B0E17),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(assistantName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (bgWakeEnabled) "Always-On: Say \"Hey $wakeWord\"" else "Tap Mic or type below",
                            fontSize = 11.sp,
                            color = if (bgWakeEnabled) Color(0xFF00E5FF) else Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF131826),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { onStopSpeak() }) {
                        Icon(Icons.Default.VolumeOff, contentDescription = "Stop Voice", tint = Color.White)
                    }
                    IconButton(onClick = { showMemoryDialog = true }) {
                        Icon(Icons.Default.Psychology, contentDescription = "Memory", tint = Color(0xFF00E5FF))
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
            // Animated JARVIS Core Orb Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                JarvisCoreOrb(isActive = isThinking, isBgListening = bgWakeEnabled)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "Try saying: \"YouTube par Phonk chalao\", \"WhatsApp par Hello bhejo\", ya Settings se Always-On Wake Word on karein.",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(12.dp)
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
                            color = if (isUser) Color(0xFF0288D1) else Color(0xFF1A2033),
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
                        Text("Processing...", color = Color(0xFF00E5FF), modifier = Modifier.padding(8.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131826))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Command bolein ya likhein...") },
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
                            .background(Color(0xFF0288D1), CircleShape)
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
        var tempWake by remember { mutableStateOf(wakeWord) }
        var tempLang by remember { mutableStateOf(langMode) }
        var tempPrompt by remember { mutableStateOf(systemPrompt) }
        var tempLock by remember { mutableStateOf(lockEnabled) }
        var tempBgWake by remember { mutableStateOf(bgWakeEnabled) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("JARVIS Settings") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                        value = tempWake,
                        onValueChange = { tempWake = it },
                        label = { Text("Wake Word (e.g., jarvis)") },
                        singleLine = true
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Voice Language:")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = tempLang == "Hinglish",
                                onClick = { tempLang = "Hinglish" },
                                label = { Text("Hinglish") }
                            )
                            FilterChip(
                                selected = tempLang == "English",
                                onClick = { tempLang = "English" },
                                label = { Text("English") }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Wake-Word Mic")
                            Text("Say 'Hey $tempWake' anytime", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = tempBgWake, onCheckedChange = { tempBgWake = it })
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Fingerprint / PIN Lock")
                        Switch(checked = tempLock, onCheckedChange = { tempLock = it })
                    }
                    OutlinedTextField(
                        value = tempPrompt,
                        onValueChange = { tempPrompt = it },
                        label = { Text("Personality Prompt") },
                        maxLines = 3
                    )
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
                    assistantName = tempName.trim().ifEmpty { "JARVIS" }
                    wakeWord = tempWake.trim().ifEmpty { "jarvis" }
                    langMode = tempLang
                    systemPrompt = tempPrompt.trim().ifEmpty { defaultPrompt }
                    lockEnabled = tempLock
                    bgWakeEnabled = tempBgWake
                    prefs.edit()
                        .putString("api_key", apiKey)
                        .putString("assistant_name", assistantName)
                        .putString("wake_word", wakeWord)
                        .putString("lang_mode", langMode)
                        .putString("system_prompt", systemPrompt)
                        .putBoolean("lock_enabled", lockEnabled)
                        .putBoolean("bg_wake_enabled", bgWakeEnabled)
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
