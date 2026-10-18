package ai.misha.jarvis

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        tts = if (packageManager.getLaunchIntentForPackage("com.google.android.tts") != null) TextToSpeech(this, this, "com.google.android.tts") else TextToSpeech(this, this)
        setContent { MishaApp(::speak, ::askBackend, ::lockPhone, ::openYouTube) }
    }
    private fun lockPhone() {
        val policy = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MishaDeviceAdminReceiver::class.java)
        if (policy.isAdminActive(admin)) {
            try { policy.lockNow() } catch (_: SecurityException) { speak("Phone lock permission is not active.") }
        } else startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin).putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow Misha to lock this phone only when you tap Lock my phone."))
    }
    private fun openYouTube() { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/"))) }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val tag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US") ?: "en-US"
            MishaVoiceProfile.configure(tts ?: return, if (tag == "ja-JP") Locale.JAPANESE else Locale.US)
        }
    }
    private fun speak(text: String) {
        runOnUiThread {
            val languageTag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US") ?: "en-US"
            val locale = if (languageTag == "ja-JP") Locale.JAPANESE else Locale.US
            val engine = tts ?: return@runOnUiThread
            MishaVoiceProfile.configure(engine, locale)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }
    private fun askBackend(prompt: String, done: (String) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            var conn: HttpURLConnection? = null
            val answer = try {
                conn = (URL("https://misha-voice-ai-api.vercel.app/api/ai/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 20000
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
private fun MishaApp(speak: (String) -> Unit, askBackend: (String, (String) -> Unit) -> Unit, lockPhone: () -> Unit, openYouTube: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready when you are.") }
    var language by remember { mutableStateOf(context.getSharedPreferences("misha_settings", 0).getString("language", "en-US") ?: "en-US") }
    var bubbleEnabled by remember { mutableStateOf(context.getSharedPreferences("misha_setup", 0).getBoolean("bubble_enabled", false)) }
    var showPermissionSetup by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadHistory(context)) }
    val recognizer = remember {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) null else try {
            val google = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
            context.packageManager.getPackageInfo("com.google.android.googlequicksearchbox", 0)
            SpeechRecognizer.createSpeechRecognizer(context, google)
        } catch (_: Exception) { SpeechRecognizer.createSpeechRecognizer(context) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening(recognizer, context, { input = it; listening = false; status = "Voice captured. Tap the mic again to submit this first post-permission command." }, { listening = false; status = it }, language)
        else status = "Microphone permission is required for voice input. Enable it in Android Settings."
    }
    val firstLaunchPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        status = if (results[Manifest.permission.RECORD_AUDIO] == true) "Setup complete. Spoken commands are submitted automatically." else "Microphone permission is off. Enable it in Android Settings to use voice."
        if (!Settings.canDrawOverlays(context)) status += " Enable the floating bubble below when ready."
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("misha_setup", 0)
        if (prefs.getInt("permission_setup_version", 0) < 2) {
            prefs.edit().putInt("permission_setup_version", 2).apply()
            showPermissionSetup = true
        }
    }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        val lower = prompt.lowercase(Locale.ROOT)
        try {
            val compoundSearch = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+?)\\s+and\\s+(?:search(?:\\s+for)?|look\\s+up)\\s+(.+)$", RegexOption.IGNORE_CASE).find(prompt)
            if (compoundSearch != null) {
                val appName = compoundSearch.groupValues[1].trim()
                var query = compoundSearch.groupValues[2].trim()
                val followRequested = Regex("\\s+and\\s+(?:follow|subscribe)(?:\\s+(?:the\\s+)?(?:account|channel|profile))?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(query)
                query = query.replace(Regex("\\s+and\\s+(?:follow|subscribe)(?:\\s+(?:the\\s+)?(?:account|channel|profile))?\\s*$", RegexOption.IGNORE_CASE), "").trim()
                val app = appName.lowercase(Locale.ROOT)
                val encoded = android.net.Uri.encode(query)
                val target = when {
                    app.contains("youtube") -> "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAg%3D%3D"
                    app.contains("instagram") -> "https://www.instagram.com/explore/search/keyword/?q=$encoded"
                    app.contains("chrome") -> "https://www.google.com/search?q=$encoded"
                    else -> null
                }
                if (target != null) {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
                    val pkg = when {
                        app.contains("youtube") -> "com.google.android.youtube"
                        app.contains("instagram") -> "com.instagram.android"
                        app.contains("chrome") -> "com.android.chrome"
                        else -> null
                    }
                    if (pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null) intent.setPackage(pkg)
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val match = context.packageManager.queryIntentActivities(launcher, 0).firstOrNull {
                        it.loadLabel(context.packageManager).toString().contains(appName, ignoreCase = true)
                    }
                    if (match == null) {
                        status = "I couldn't find an installed app matching '$appName'."
                        input = ""; return
                    }
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(match.activityInfo.packageName, match.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    if (MishaAccessibilityService.isEnabled()) android.os.Handler(context.mainLooper).postDelayed({
                        if (MishaAccessibilityService.perform("tap Search")) {
                            android.os.Handler(context.mainLooper).postDelayed({
                                MishaAccessibilityService.perform("type $query")
                                MishaAccessibilityService.perform("enter")
                            }, 500)
                        }
                    }, 1400)
                }
                if (followRequested && MishaAccessibilityService.isEnabled()) {
                    android.os.Handler(context.mainLooper).postDelayed({
                        val selected = MishaAccessibilityService.perform("tap $query")
                        android.os.Handler(context.mainLooper).postDelayed({
                            val followed = MishaAccessibilityService.perform("tap Follow") || MishaAccessibilityService.perform("tap Subscribe")
                            if (selected && followed) speak("I tapped the visible Follow or Subscribe control. Please check the account state.")
                        }, 1200)
                    }, 1800)
                    status = "Searching for '$query' and attempting the visible Follow/Subscribe action. Check the account state afterward."
                } else if (followRequested) status = "Search opened for '$query'. Enable Misha Assistant Controls in Accessibility settings to allow follow/subscribe automation."
                else status = "Opening $appName and searching for '$query'."
                input = ""; return
            }
            when {
                lower in listOf("flashlight on", "turn on flashlight", "switch on flashlight", "torch on", "turn on torch", "flashlight off", "turn off flashlight", "switch off flashlight", "torch off", "turn off torch") -> {
                    val turnOn = lower.endsWith(" on") || lower.startsWith("turn on ") || lower.startsWith("switch on ")
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        firstLaunchPermissions.launch(arrayOf(Manifest.permission.CAMERA))
                        status = "Camera permission is not currently granted to Misha. Approve it, then repeat the flashlight command."
                        input = ""; return
                    }
                    val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (cameraId == null) status = "This phone does not expose an available camera flashlight."
                    else {
                        cameraManager.setTorchMode(cameraId, turnOn)
                        status = if (turnOn) "Flashlight is on." else "Flashlight is off."
                        speak(status)
                    }
                    input = ""; return
                }
                lower == "open youtube" || lower.startsWith("open youtube") -> { openYouTube(); status = "Opening YouTube."; input = ""; return }
                lower == "open instagram" || lower == "launch instagram" || lower == "start instagram" -> {
                    val launch = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                    if (launch != null) context.startActivity(launch) else context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/")))
                    status = "Opening Instagram."; input = ""; return
                }
                lower.startsWith("search instagram ") || lower.startsWith("find on instagram ") -> {
                    val query = prompt.substringAfter(' ').substringAfter(' ').trim()
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode("site:instagram.com $query"))))
                    status = "Searching public Instagram results on the web."; input = ""; return
                }
                lower.startsWith("open ") && lower.endsWith(" settings") -> {
                    val action = when {
                        lower.contains("wi-fi") || lower.contains("wifi") -> Settings.ACTION_WIFI_SETTINGS
                        lower.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
                        lower.contains("location") || lower.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        lower.contains("display") || lower.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
                        lower.contains("sound") || lower.contains("volume") -> Settings.ACTION_SOUND_SETTINGS
                        lower.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                        lower.contains("notification") -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        lower.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                        lower.contains("flashlight") || lower.contains("torch") -> null
                        else -> Settings.ACTION_SETTINGS
                    }
                    if (action == null) status = if (MishaAccessibilityService.perform("quick settings")) "Opened Quick Settings. Use the Flashlight tile." else "Enable Misha Assistant Controls in Accessibility settings first."
                    else { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); status = "Opening the requested Android settings." }
                    input = ""; return
                }
                (lower.startsWith("open ") && !lower.startsWith("open whatsapp and send message ") && !lower.startsWith("open whatsapp and message ") && !lower.startsWith("open whatsapp and text ")) || lower.startsWith("launch ") || lower.startsWith("start app ") -> {
                    val appName = when {
                        lower.startsWith("open ") -> prompt.substringAfter(' ').trim()
                        lower.startsWith("launch ") -> prompt.substringAfter(' ').trim()
                        else -> prompt.substringAfter("start app ").trim()
                    }
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val matches = context.packageManager.queryIntentActivities(launcherIntent, 0).filter {
                        it.loadLabel(context.packageManager).toString().contains(appName, ignoreCase = true)
                    }
                    val chosen = matches.firstOrNull()
                    if (chosen != null && appName.isNotBlank()) {
                        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(chosen.activityInfo.packageName, chosen.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        status = "Opening ${chosen.loadLabel(context.packageManager)}."
                    } else status = "I couldn't find an installed app matching '$appName'."
                    input = ""; return
                }
                lower.startsWith("search ") || lower.startsWith("google ") || lower.startsWith("look up ") -> {
                    val query = when {
                        lower.startsWith("search ") -> prompt.substringAfter("search ").replaceFirst(Regex("^for\\s+", RegexOption.IGNORE_CASE), "").trim()
                        lower.startsWith("google ") -> prompt.substringAfter("google ").trim()
                        else -> prompt.substringAfter("look up ").trim()
                    }
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    status = "Searching the web for '$query'."; input = ""; return
                }
                lower.contains("weather") -> { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode("current weather near me"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); status = "Searching the web for current weather."; input = ""; return }
                lower.startsWith("call ") || lower.startsWith("dial ") -> {
                    val target = prompt.substringAfter(' ').trim()
                    val contactNumber = if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) findContactNumber(context, target) else null
                    val number = contactNumber ?: target.takeIf { it.any(Char::isDigit) }
                    if (number == null) {
                        status = if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) "Allow Contacts permission in Android Settings, then say call followed by the saved contact name." else "No saved contact or phone number matched '$target'."
                        input = ""; return
                    }
                    val tel = android.net.Uri.parse("tel:" + android.net.Uri.encode(number))
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        context.startActivity(Intent(Intent.ACTION_CALL, tel))
                        status = "Calling $target now."
                    } else {
                        context.startActivity(Intent(Intent.ACTION_DIAL, tel))
                        status = "Phone permission is not granted, so I opened the dialer. Grant Phone permission in Android Settings to place calls by voice."
                    }
                    input = ""; return
                }
                lower == "lock my phone" || lower == "lock phone" -> { lockPhone(); status = "Device lock requested. Activate device admin if prompted."; input = ""; return }
                lower in listOf("go back", "press back", "back") -> {
                    status = if (MishaAccessibilityService.perform("back")) "Went back." else "Enable Misha Assistant Controls in Accessibility Settings first."
                    input = ""; return
                }
                lower in listOf("go home", "home screen", "go to home screen") -> {
                    status = if (MishaAccessibilityService.perform("home")) "Returned to Home." else "Enable Misha Assistant Controls in Accessibility Settings first."
                    input = ""; return
                }
                lower in listOf("show notifications", "open notifications") -> {
                    status = if (MishaAccessibilityService.perform("notifications")) "Opened notifications." else "Enable Misha Assistant Controls in Accessibility Settings first."
                    input = ""; return
                }
                lower in listOf("open quick settings", "show quick settings", "open flashlight settings", "open torch settings") -> {
                    status = if (MishaAccessibilityService.perform("quick settings")) "Opened Quick Settings." else "Enable Misha Assistant Controls in Accessibility Settings first."
                    input = ""; return
                }
                lower.startsWith("tap ") -> {
                    status = if (MishaAccessibilityService.perform(prompt)) "Tapped visible item matching the requested text." else "No visible matching control was clicked. Enable Accessibility and check the exact on-screen text."
                    input = ""; return
                }
                lower in listOf("what's on my screen", "what is on my screen", "read my screen") -> {
                    status = MishaAccessibilityService.currentScreen(context)
                    speak(status); input = ""; return
                }
                lower.startsWith("whatsapp ") || lower.startsWith("send whatsapp ") || lower.startsWith("open whatsapp and send message ") || lower.startsWith("open whatsapp and message ") || lower.startsWith("open whatsapp and text ") || lower.startsWith("message on whatsapp ") || lower.startsWith("text on whatsapp ") -> {
                    val request = prompt.replaceFirst(Regex("^(?:(?:open\\s+)?whatsapp\\s+and\\s+(?:send\\s+)?(?:message|text)|(?:message|text)\\s+on\\s+whatsapp|(?:send\\s+)?whatsapp)\\s*", RegexOption.IGNORE_CASE), "")
                    val parsed: Pair<String, String>? = when {
                        Regex("^to\\s+(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val m = Regex("^to\\s+(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            m.groupValues[1].trim() to m.groupValues[2].trim()
                        }
                        Regex("^(.+)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val m = Regex("^(.+)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            m.groupValues[2].trim() to m.groupValues[1].trim()
                        }
                        Regex("^(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val m = Regex("^(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            m.groupValues[1].trim() to m.groupValues[2].trim()
                        }
                        else -> null
                    }
                    if (parsed == null || parsed.first.isBlank() || parsed.second.isBlank()) {
                        status = "Say: WhatsApp to CONTACT NAME message TEXT, or open WhatsApp and send message TEXT to CONTACT NAME."
                        input = ""; return
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        status = "Allow Contacts permission in Android Settings, then retry the WhatsApp command."
                        input = ""; return
                    }
                    val contactName = parsed.first
                    val messageText = parsed.second
                    val phone = findContactNumber(context, contactName)
                    if (phone == null) {
                        status = "I couldn't find a saved contact matching '$contactName'. Check the exact name."
                        input = ""; return
                    }
                    val digits = phone.filter(Char::isDigit)
                    val international = if (digits.startsWith("91") || digits.length > 10) digits else "91$digits"
                    val uri = android.net.Uri.parse("https://wa.me/$international?text=" + android.net.Uri.encode(messageText))
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    if (MishaAccessibilityService.isEnabled()) {
                        android.os.Handler(context.mainLooper).postDelayed({
                            val screen = MishaAccessibilityService.currentScreen(context)
                            if (screen.contains("com.whatsapp", ignoreCase = true) && screen.contains(messageText.take(minOf(24, messageText.length)), ignoreCase = true)) {
                                val tapped = MishaAccessibilityService.perform("tap Send")
                                if (!tapped) android.os.Handler(context.mainLooper).postDelayed({ MishaAccessibilityService.perform("tap Send") }, 1000)
                            }
                        }, 3000)
                        status = "Opened WhatsApp for $contactName and will try to press Send after the chat loads. Check the conversation to confirm delivery."
                    } else status = "Opened WhatsApp for $contactName with the message ready. Enable Misha Assistant Controls in Accessibility settings for the optional send attempt."
                    input = ""; return
                }
                lower.contains("whatsapp") -> {
                    val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (launch != null) context.startActivity(launch) else context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.whatsapp.com/")))
                    status = "Opening WhatsApp."; input = ""; return
                }
            }
        } catch (_: Exception) { status = "That action couldn't be opened on this device."; return }
        input = ""; busy = true; status = "Connecting to Misha AI…"
        val languageInstruction = if (language == "ja-JP") "Reply in Japanese. " else "Reply in English. "
        val recentContext = history.takeLast(8).joinToString("\n") { "Previous user: ${it.prompt}\nPrevious Misha: ${it.answer}" }
        val contextualPrompt = languageInstruction + (if (recentContext.isBlank()) "" else "Recent conversation context:\n$recentContext\n") + "Current user request: $prompt"
        askBackend(contextualPrompt) { answer ->
            history = (history + Message(prompt = prompt, answer = answer)).takeLast(100)
            saveHistory(context, history)
            busy = false; status = "Response received."; speak(answer)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Night, surface = Color(0xFF15162B), onSurface = Color(0xFFF5F1FF))) {
    if (showPermissionSetup) {
        AlertDialog(
            onDismissRequest = { showPermissionSetup = false },
            title = { Text("Misha phone access setup") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review each permission before enabling it.")
                    Text("• Microphone — voice commands; recording starts only after you tap the mic.")
                    Text("• Contacts — find the right saved person for calling or WhatsApp.")
                    Text("• Notifications — show the active background-assistant status.")
                    Text("• Display over other apps — show the floating chat bubble.")
                    Text("• Accessibility — read visible screen text on request and perform explicit Back/Home/notification or text-target actions.")
                    Text("• Device admin — lock the phone only when you explicitly request it.")
                    Text("Android requires separate approval screens for overlay, Accessibility and device admin. Misha cannot silently grant these.")
                    TextButton(onClick = {
                        showPermissionSetup = false
                        try { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))) }
                        catch (_: Exception) { status = "Open Android Settings → Display over other apps → Misha." }
                    }) { Text("Open floating-overlay settings") }
                    TextButton(onClick = {
                        showPermissionSetup = false
                        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        catch (_: Exception) { status = "Open Android Settings → Accessibility to enable Misha Assistant Controls." }
                    }) { Text("Open Accessibility settings") }
                    TextButton(onClick = {
                        showPermissionSetup = false
                        val policy = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val admin = ComponentName(context, MishaDeviceAdminReceiver::class.java)
                        if (!policy.isAdminActive(admin)) {
                            context.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Misha can lock the phone only when you explicitly request it."))
                        } else status = "Device admin is already enabled."
                    }) { Text("Open device-admin setup") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionSetup = false
                    val missing = buildList {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
                        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CALL_PHONE)
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
                    }
                    if (missing.isNotEmpty()) firstLaunchPermissions.launch(missing.toTypedArray()) else status = "Runtime permissions are already granted."
                }) { Text("Grant runtime permissions") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionSetup = false }) { Text("Later") }
            }
        )
    }
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF201544), Night, Night))).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Violet, Color(0xFFDE91D7))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("M", fontWeight = FontWeight.Bold, fontSize = 23.sp, color = Color.White) }
                    Spacer(Modifier.width(11.dp))
                    Column { Text("MISHA", fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp); Text("PERSONAL AI · JARVIS MODE", fontSize = 9.sp, color = Color(0xFFAAA4CF), letterSpacing = 1.sp) }
                }
                TextButton(onClick = { history = emptyList(); context.getSharedPreferences("misha_history", 0).edit().remove("items").apply(); status = "Saved conversation history cleared." }) { Text("Clear", color = Color(0xFFBDB0F8)) }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = language == "en-US", onClick = {
                    language = "en-US"; context.getSharedPreferences("misha_settings", 0).edit().putString("language", language).apply()
                    status = "English selected."
                }, label = { Text("English") })
                FilterChip(selected = language == "ja-JP", onClick = {
                    language = "ja-JP"; context.getSharedPreferences("misha_settings", 0).edit().putString("language", language).apply()
                    status = "日本語を選択しました。"
                }, label = { Text("日本語") })
            }
            OutlinedButton(onClick = { showPermissionSetup = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Permissions & phone-control setup")
            }
            Spacer(Modifier.height(12.dp))
            Text("YOUR ASSISTANT", color = Color(0xFFB7A5FF), letterSpacing = 2.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("What can I help you with?", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Speak or type in English or Japanese. Voice commands submit automatically.", color = Color(0xFFAAA8C3), fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(174.dp).background(Brush.radialGradient(listOf(Color(0xFFD6C4FF), Violet, Color(0xFF34236E), Night)), CircleShape), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startListening(recognizer, context, { listening = false; send(it) }, { listening = false; status = it }, language); listening = true; status = "Listening…"
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
            OutlinedButton(onClick = { openYouTube(); status = "Opening YouTube." }, modifier = Modifier.fillMaxWidth()) { Text("Open YouTube") }
            OutlinedButton(
                onClick = {
                    val prefs = context.getSharedPreferences("misha_setup", 0)
                    if (bubbleEnabled) {
                        context.stopService(Intent(context, FloatingAssistantService::class.java))
                        bubbleEnabled = false
                        prefs.edit().putBoolean("bubble_enabled", false).apply()
                        status = "Floating assistant disabled."
                    } else if (Settings.canDrawOverlays(context)) {
                        try {
                            val serviceIntent = Intent(context, FloatingAssistantService::class.java)
                            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
                            bubbleEnabled = true
                            prefs.edit().putBoolean("bubble_enabled", true).apply()
                            status = "Floating assistant enabled. Tap the M bubble to open Misha."
                        } catch (_: Exception) { status = "Couldn't start the floating assistant. Check overlay permission and try again." }
                    } else {
                        status = "Allow Display over other apps, return to Misha, then tap Enable Floating Assistant."
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { status = "Open Android Settings and allow Display over other apps for Misha." }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (bubbleEnabled) "Disable Floating Assistant" else "Enable Floating Assistant") }
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

private fun findContactNumber(context: android.content.Context, name: String): String? {
    val wanted = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    if (wanted.isBlank()) return null
    return try {
        val candidates = mutableListOf<Triple<String, String, Int>>()
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? COLLATE NOCASE",
            arrayOf("%$name%"),
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val display = cursor.getString(nameColumn).orEmpty().trim()
                val number = cursor.getString(numberColumn).orEmpty().trim()
                val normalized = display.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
                if (number.isBlank()) continue
                val score = when {
                    normalized == wanted -> 0
                    normalized.startsWith(wanted) -> 1
                    normalized.contains(wanted) -> 2
                    wanted.split(" ").all { it in normalized } -> 3
                    else -> 4
                }
                candidates.add(Triple(display, number, score))
            }
        }
        candidates.minByOrNull { it.third }?.second
    } catch (_: SecurityException) { null } catch (_: Exception) { null }
}

private fun startListening(recognizer: SpeechRecognizer?, context: android.content.Context, onResult: (String) -> Unit, onError: (String) -> Unit, languageTag: String = "en-US") {
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
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
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
