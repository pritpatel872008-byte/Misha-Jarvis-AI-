package ai.misha.jarvis

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { MishaApp(::speak, ::askBackend, ::lockPhone, ::openYouTube, ::setVoiceLanguage, ::enableFloatingBubble) }
    }
    private fun lockPhone() {
        val policy = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MishaDeviceAdminReceiver::class.java)
        if (policy.isAdminActive(admin)) {
            try { policy.lockNow() } catch (_: SecurityException) { speak("Phone lock permission is not active.") }
        } else startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin).putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow Misha to lock this phone only when you tap Lock my phone."))
    }
    private fun openYouTube() { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/"))) }
    private fun enableFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { speak("Please enable Display over other apps for Misha in Android Settings.") }
            return
        }
        try {
            startService(Intent(this, MishaBubbleService::class.java))
            speak("Floating Misha bubble enabled. Tap it to return to me.")
        } catch (_: Exception) {
            speak("I couldn't start the floating bubble. Check overlay permission and try again.")
        }
    }
    private fun setVoiceLanguage(languageTag: String) {
        val engine = tts ?: return
        getSharedPreferences("misha_voice", MODE_PRIVATE).edit().putString("language", languageTag).apply()
        val result = engine.setLanguage(Locale.forLanguageTag(languageTag))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            speak("That voice is not installed. Open Android Text-to-Speech settings and install a compatible voice.")
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
        } else speak("Voice language updated.")
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageTag = getSharedPreferences("misha_voice", MODE_PRIVATE).getString("language", "en-US") ?: "en-US"
            val result = tts?.setLanguage(Locale.forLanguageTag(languageTag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.getDefault()
        }
    }
    private fun speak(text: String) { runOnUiThread { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) } }
    private fun askBackend(prompt: String, done: (String) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            var conn: HttpURLConnection? = null
            val answer = try {
                conn = (URL("https://misha-voice-ai-api.vercel.app/api/ai/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json"); doOutput = true
                }
                conn.outputStream.use { it.write(JSONObject().put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = try {
                        val error = JSONObject(body)
                        val value = error.opt("detail") ?: error.opt("message") ?: error.opt("error")
                        when (value) {
                            is org.json.JSONArray -> (0 until value.length()).joinToString("; ") { i ->
                                val item = value.optJSONObject(i)
                                if (item != null) listOf(item.optString("loc").takeIf { it.isNotBlank() && it != "null" }, item.optString("msg").takeIf { it.isNotBlank() && it != "null" }).filterNotNull().joinToString(": ") else value.optString(i)
                            }
                            is org.json.JSONObject -> value.toString()
                            else -> value?.toString()
                        }
                    } catch (_: Exception) { null }
                    "Assistant service HTTP $code" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${body.take(300)}")
                }
                else parseAnswer(body).ifBlank { "The assistant service returned no readable answer." }
            } catch (_: Exception) { "I couldn't reach the assistant service. Check your internet connection and try again." }
            finally { conn?.disconnect() }
            runOnUiThread { done(answer) }
        }
    }
    private fun parseAnswer(body: String): String = try {
        val j = JSONObject(body)
        listOf("reply", "response", "message", "output_text", "answer").firstNotNullOfOrNull { key -> j.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            ?: j.optJSONObject("choices")?.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: body.take(500)
    } catch (_: Exception) { body.take(500) }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

private val Night = Color(0xFF090A16)
private val Violet = Color(0xFF9D7BFF)
private data class Message(val id: String = UUID.randomUUID().toString(), val prompt: String, val answer: String)

@Composable
private fun MishaApp(speak: (String) -> Unit, askBackend: (String, (String) -> Unit) -> Unit, lockPhone: () -> Unit, openYouTube: () -> Unit, setVoiceLanguage: (String) -> Unit, enableFloatingBubble: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready when you are.") }
    var runVoiceCommand by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadHistory(context)) }
    val recognizer = remember {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) null else try {
            val google = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
            context.packageManager.getPackageInfo("com.google.android.googlequicksearchbox", 0)
            SpeechRecognizer.createSpeechRecognizer(context, google)
        } catch (_: Exception) { SpeechRecognizer.createSpeechRecognizer(context) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(recognizer, context, { input = it; listening = false; status = "Voice captured."; runVoiceCommand = true }, { listening = false; status = it })
        else status = "Microphone permission is required for voice input."
    }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        val lower = prompt.lowercase(Locale.ROOT)
        try {
            when {
                lower == "open youtube" || lower.startsWith("open youtube") || lower == "youtube kholo" || lower == "youtube open karo" -> { openYouTube(); status = "Opening YouTube."; input = ""; return }
                lower.contains("open instagram") || lower.contains("instagram kholo") || lower.contains("instagram open karo") || lower == "instagram" -> { launchInstalledApp(context, "com.instagram.android", "Instagram", { status = "Opening Instagram."; input = "" }, { status = it }); return }
                lower.contains("open chrome") || lower.contains("chrome kholo") || lower.contains("chrome open karo") || lower == "chrome" -> { launchInstalledApp(context, "com.android.chrome", "Chrome", { status = "Opening Chrome."; input = "" }, { status = it }); return }
                lower.contains("open settings") || lower.contains("settings kholo") || lower.contains("settings open karo") || lower == "phone settings" -> { try { context.startActivity(Intent(Settings.ACTION_SETTINGS)); status = "Opening Android Settings."; input = "" } catch (_: Exception) { status = "Android Settings could not be opened." }; return }
                lower.contains("hindi mein bolo") || lower.contains("hindi voice") || lower.contains("speak hindi") -> { setVoiceLanguage("hi-IN"); status = "Switching to Hindi voice."; input = ""; return }
                lower.contains("speak english") || lower.contains("english voice") || lower.contains("english mein bolo") -> { setVoiceLanguage("en-US"); status = "Switching to English voice."; input = ""; return }
                lower.contains("speak japanese") || lower.contains("japanese voice") || lower.contains("nihongo") -> { setVoiceLanguage("ja-JP"); status = "Switching to Japanese voice."; input = ""; return }
                lower.contains("voice settings") || lower.contains("tts settings") || lower.contains("change voice") -> { try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }; status = "Choose your installed system voice engine and voice."; input = ""; return }
                lower.contains("weather") -> { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://weather.google.com/"))); status = "Opening live weather."; input = ""; return }
                lower.startsWith("search ") || lower.startsWith("google ") -> {
                    val query = prompt.substringAfter(' ').trim()
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))))
                    status = "Opening Google search."; input = ""; return
                }
                lower.startsWith("call ") || lower.startsWith("dial ") -> {
                    val number = prompt.substringAfter(' ').trim()
                    context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(number))))
                    status = "Dialer opened. Review the number and place the call yourself."; input = ""; return
                }
                lower == "lock my phone" || lower == "lock phone" -> { lockPhone(); status = "Device lock requested. Activate device admin if prompted."; input = ""; return }
                lower.contains("whatsapp") -> {
                    val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (launch != null) context.startActivity(launch) else context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.whatsapp.com/")))
                    status = "Opening WhatsApp. Review and send messages yourself."; input = ""; return
                }
            }
        } catch (_: Exception) { status = "That action couldn't be opened on this device."; return }
        input = ""; busy = true; status = "Connecting to Misha AI…"
        askBackend(prompt) { answer ->
            history = (history + Message(prompt = prompt, answer = answer)).takeLast(100)
            saveHistory(context, history)
            busy = false; status = "Response received."; speak(answer)
        }
    }
    LaunchedEffect(runVoiceCommand) {
        if (runVoiceCommand) {
            runVoiceCommand = false
            send(input)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Night, surface = Color(0xFF15162B), onSurface = Color(0xFFF5F1FF))) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF201544), Night, Night))).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Violet, Color(0xFFDE91D7))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("M", fontWeight = FontWeight.Bold, fontSize = 23.sp, color = Color.White) }
                    Spacer(Modifier.width(11.dp))
                    Column { Text("MISHA", fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp); Text("PERSONAL AI · JARVIS MODE", fontSize = 9.sp, color = Color(0xFFAAA4CF), letterSpacing = 1.sp) }
                }
                TextButton(onClick = { history = emptyList(); context.getSharedPreferences("misha_history", 0).edit().remove("items").apply(); status = "Saved conversation history cleared." }) { Text("Clear", color = Color(0xFFBDB0F8)) }
            }
            Spacer(Modifier.height(28.dp))
            Text("YOUR ASSISTANT", color = Color(0xFFB7A5FF), letterSpacing = 2.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("At your command,\nPrit.", fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Think it. Say it. Let's get it done.", color = Color(0xFFAAA8C3), fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(174.dp).background(Brush.radialGradient(listOf(Color(0xFFD6C4FF), Violet, Color(0xFF34236E), Night)), CircleShape), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startListening(recognizer, context, { recognized -> listening = false; input = recognized; status = "Voice captured."; send(recognized) }, { listening = false; status = it }); listening = true; status = "Listening…"
                        } else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D2B85)), modifier = Modifier.size(104.dp)) {
                        Text(if (listening) "•••" else "M", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("●  $status", fontSize = 10.sp, color = Color(0xFFB9B0E6), modifier = Modifier.padding(bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("What can you do?", "Lock my phone", "Help me").forEach { action ->
                    OutlinedButton(onClick = { when { action.contains("Lock") -> { lockPhone(); status = "Activate device admin if prompted, then tap Lock my phone again." }; else -> send(action) } }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) { Text(action, fontSize = 10.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { openYouTube(); status = "Opening YouTube." }, modifier = Modifier.weight(1f)) { Text("Open YouTube") }
                OutlinedButton(onClick = { enableFloatingBubble(); status = if (Settings.canDrawOverlays(context)) "Floating Misha bubble enabled." else "Allow overlay permission, then tap this again." }, modifier = Modifier.weight(1f)) { Text("Floating Misha") }
            }
            OutlinedButton(onClick = { try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }; status = "Select the installed system voice engine in Android settings." }, modifier = Modifier.fillMaxWidth()) { Text("Voice engine settings") }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Ask Misha anything…") }, shape = RoundedCornerShape(16.dp), trailingIcon = { TextButton(enabled = !busy, onClick = { send(input) }) { Text(if (busy) "…" else "Send") } }, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            Text("RECENT CONVERSATION", color = Color(0xFF9D91CB), letterSpacing = 1.5.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (history.isEmpty()) {
                Text("No conversations yet. Ask Misha something to begin.", color = Color(0xFF85839F), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(history.reversed(), key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17182D)), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) { Text("YOU", color = Violet, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(item.prompt, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); Text("MISHA", color = Color(0xFFDDA8F1), fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(item.answer, fontSize = 12.sp) }
                    }
                }
            }
            Spacer(Modifier.weight(1f, fill = true))
            Text("Actions that affect your phone or accounts require permission and confirmation.", fontSize = 9.sp, color = Color(0xFF77758F), lineHeight = 13.sp)
        }
    }
}

private fun launchInstalledApp(context: android.content.Context, packageName: String, appName: String, onOpened: () -> Unit, onError: (String) -> Unit) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) { onError("$appName is not installed or is disabled."); return }
        context.startActivity(launchIntent)
        onOpened()
    } catch (_: Exception) { onError("$appName could not be opened. Check that it is installed and enabled.") }
}

private fun startListening(recognizer: SpeechRecognizer?, context: android.content.Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    if (recognizer == null) { onError("Speech recognition is unavailable on this device."); return }
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { onError("Voice recognition failed ($error). Try typing or speak again.") }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onError("No speech was recognized. Please try again.") else onResult(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }
    try { recognizer.startListening(intent) } catch (_: Exception) { onError("Couldn't start voice recognition. Please retry.") }
}

private fun saveHistory(context: android.content.Context, history: List<Message>) {
    val array = org.json.JSONArray()
    history.forEach { array.put(JSONObject().put("id", it.id).put("prompt", it.prompt).put("answer", it.answer)) }
    context.getSharedPreferences("misha_history", 0).edit().putString("items", array.toString()).apply()
}
private fun loadHistory(context: android.content.Context): List<Message> = try {
    val array = org.json.JSONArray(context.getSharedPreferences("misha_history", 0).getString("items", "[]"))
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        Message(item.optString("id", UUID.randomUUID().toString()), item.optString("prompt"), item.optString("answer"))
    }.takeLast(100)
} catch (_: Exception) { emptyList() }
