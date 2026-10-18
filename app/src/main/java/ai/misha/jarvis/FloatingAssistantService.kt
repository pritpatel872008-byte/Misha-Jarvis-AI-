package ai.misha.jarvis

import android.Manifest
import android.app.Notification
import android.app.admin.DevicePolicyManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openwakeword.OpenWakeWord
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** User-controlled floating assistant. The microphone is used only after the user taps the mic button. */
class FloatingAssistantService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var popup: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var startX = 0
    private var startY = 0
    private var downX = 0f
    private var downY = 0f
    private var recognizer: SpeechRecognizer? = null
    private var wakeWordDetector: OpenWakeWord? = null
    @Volatile private var wakeWordActive = false
    private var wakeWordModelAvailable = false
    private var tts: TextToSpeech? = null
    private var transcript: TextView? = null
    private var input: EditText? = null
    private var sendButton: Button? = null
    private var micButton: Button? = null
    private var busy = false
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
                tts?.let { engine -> MishaVoiceProfile.configure(engine, if (tag == "ja-JP") Locale.JAPANESE else Locale.US) }
            }
        }
        wakeWordModelAvailable = hasHeyMishaModel()
        updateNotificationForWakeWord()
        if (Settings.canDrawOverlays(this)) {
            attachBubble()
            if (wakeWordModelAvailable && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startWakeWordDetector()
        } else stopSelf()
    }

    /**
     * Uses an actual on-device openWakeWord classifier. The model must be trained specifically
     * for "Hey Misha"; a generic Hey Jarvis model is deliberately not substituted.
     */
    private fun hasHeyMishaModel(): Boolean = try {
        assets.open(HEY_MISHA_MODEL_ASSET).use { true }
    } catch (_: Exception) { false }

    private fun startWakeWordDetector() {
        if (wakeWordActive || !wakeWordModelAvailable || busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            wakeWordDetector?.release()
            val detector = OpenWakeWord.Builder(applicationContext)
                .setModelAsset(HEY_MISHA_MODEL_ASSET)
                .setThreshold(0.62f)
                .setDebounceMs(2500L)
                .build()
            wakeWordDetector = detector
            wakeWordActive = true
            detector.start { score ->
                if (score >= 0.62f && wakeWordActive) {
                    wakeWordActive = false
                    try { detector.stop() } catch (_: Exception) {}
                    android.os.Handler(mainLooper).post {
                        if (popup == null) showPopup()
                        transcript?.text = "I heard Hey Misha. Listening to your request…"
                        android.os.Handler(mainLooper).postDelayed({ listenOnce() }, 450)
                    }
                }
            }
        } catch (_: Exception) {
            wakeWordActive = false
            wakeWordModelAvailable = false
            wakeWordDetector = null
            updateNotificationForWakeWord()
        }
    }

    private fun stopWakeWordDetector() {
        wakeWordActive = false
        try { wakeWordDetector?.stop() } catch (_: Exception) {}
    }

    private fun updateNotificationForWakeWord() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Misha floating assistant", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Misha floating assistant is active")
            .setContentText(if (wakeWordModelAvailable) "Hey Misha wake-word listening is active on-device. Tap M to chat." else "Wake-word model not installed. Tap M to chat or tap Mic.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopPendingIntent())
            .build()
    }

    private fun stopPendingIntent(): android.app.PendingIntent {
        val intent = Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP)
        return android.app.PendingIntent.getService(this, 74, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    private fun attachBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "M"; textSize = 23f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(157, 123, 255), Color.rgb(67, 42, 145))).apply {
                shape = GradientDrawable.OVAL; setStroke(2, Color.rgb(220, 195, 255))
            }
            contentDescription = "Open Misha assistant"; elevation = 12f
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(dp(60), dp(60), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(180) }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) < dp(8).toFloat() && abs(event.rawY - downY) < dp(8).toFloat()) {
                        if (popup == null) { showPopup(); android.os.Handler(mainLooper).postDelayed({ listenOnce() }, 350) }
                        else listenOnce()
                    }
                    true
                }
                else -> true
            }
        }
        try { windowManager?.addView(view, params); bubble = view; bubbleParams = params }
        catch (_: Exception) { stopSelf() }
    }

    private fun showPopup() {
        if (popup != null) { hidePopup(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.rgb(15, 15, 31)); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.rgb(157, 123, 255)) }
            elevation = 20f
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "MISHA  •  What can I help you with?"; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(Button(this).apply { text = "×"; setOnClickListener { hidePopup() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        panel.addView(header)
        transcript = TextView(this).apply {
            text = "Ready. Type a request or tap the mic. Voice commands are submitted automatically."
            textSize = 13f; setTextColor(Color.rgb(220, 218, 238)); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(transcript) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(150)))
        input = EditText(this).apply {
            hint = "Type in English or Japanese…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            setSingleLine(false); minLines = 1; maxLines = 4
            background = GradientDrawable().apply { setColor(Color.rgb(29, 28, 49)); cornerRadius = dp(12).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        panel.addView(input, LinearLayout.LayoutParams(-1, dp(60)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        micButton = Button(this).apply { text = "🎙 Mic"; setOnClickListener { listenOnce() } }
        sendButton = Button(this).apply { text = "Send"; setOnClickListener { submit(input?.text?.toString().orEmpty()) } }
        actions.addView(micButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(sendButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(actions)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams((resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(280)),
            dp(350), type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        try {
            windowManager?.addView(panel, params); popup = panel; popupParams = params
            panel.isFocusableInTouchMode = true; panel.requestFocus()
        } catch (_: Exception) { transcript?.text = "Couldn't open the chat overlay. Check Display over other apps permission." }
    }

    private fun hidePopup() {
        try { popup?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        popup = null; popupParams = null; input = null; transcript = null; sendButton = null; micButton = null
        try { recognizer?.cancel() } catch (_: Exception) {}
        startWakeWordDetector()
    }

    private fun listenOnce() {
        stopWakeWordDetector()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            transcript?.text = "Microphone permission is missing. Open Misha and allow Microphone in Android Settings."; return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { transcript?.text = "Speech recognition is unavailable on this phone."; return }
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        micButton?.isEnabled = false
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { transcript?.text = "Listening… speak now." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { transcript?.text = "Voice recognition failed ($error). Please try again or type."; micButton?.isEnabled = true; startWakeWordDetector() }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                micButton?.isEnabled = true
                if (spoken.isNullOrBlank()) transcript?.text = "I didn't catch that. Try again."
                else { input?.setText(spoken); submit(spoken) }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val language = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US") ?: "en-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { micButton?.isEnabled = true; transcript?.text = "Couldn't start speech recognition. Try again." }
    }

    private fun speakAndResumeWakeWord(engine: TextToSpeech, text: String) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { android.os.Handler(mainLooper).post { startWakeWordDetector() } }
            override fun onDone(utteranceId: String?) { android.os.Handler(mainLooper).post { startWakeWordDetector() } }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }
    private fun submit(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank() || busy) return
        val deviceResult = handleDeviceCommand(clean)
        if (deviceResult != null) {
            transcript?.text = "You: $clean\\n\\nMisha: $deviceResult"
            val tag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
            tts?.let { engine ->
                MishaVoiceProfile.configure(engine, if (tag == "ja-JP") Locale.JAPANESE else Locale.US)
                speakAndResumeWakeWord(engine, deviceResult)
            }
            return
        }
        busy = true; sendButton?.isEnabled = false; micButton?.isEnabled = false
        transcript?.text = "You: $clean\n\nMisha is thinking…"
        val languageTag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
        val languagePrompt = if (languageTag == "ja-JP") "Reply in Japanese. User request: $clean" else "Reply in English. User request: $clean"
        val historySnapshot = conversationHistory.toList()
        executor.execute {
            var connection: HttpURLConnection? = null
            val result = try {
                connection = (URL("https://misha-voice-ai-api.vercel.app/api/ai/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 20000
                    setRequestProperty("Content-Type", "application/json"); doOutput = true
                }
                val messages = JSONArray()
                historySnapshot.forEach { (role, content) -> messages.put(JSONObject().put("role", role).put("content", content)) }
                messages.put(JSONObject().put("role", "user").put("content", languagePrompt))
                connection.outputStream.use { it.write(JSONObject().put("messages", messages).toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) "Assistant service returned HTTP $code: ${body.take(180)}"
                else parseReply(body)
            } catch (_: Exception) { "I couldn't reach the assistant service. Check your internet connection and try again." }
            finally { connection?.disconnect() }
            android.os.Handler(mainLooper).post {
                busy = false; sendButton?.isEnabled = true; micButton?.isEnabled = true
                transcript?.text = "You: $clean\n\nMisha: $result"
                conversationHistory.add("user" to languagePrompt)
                conversationHistory.add("assistant" to result)
                while (conversationHistory.size > 20) conversationHistory.removeAt(0)
                val tag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
                tts?.let { engine ->
                    MishaVoiceProfile.configure(engine, if (tag == "ja-JP") Locale.JAPANESE else Locale.US)
                    speakAndResumeWakeWord(engine, result)
                }
            }
        }
    }

    private fun handleDeviceCommand(prompt: String): String? {
        val clean = prompt.trim()
        val lower = clean.lowercase(Locale.ROOT)
        return try {
            val compound = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+?)\\s+and\\s+(?:search(?:\\s+for)?|look\\s+up)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
            if (compound != null) {
                val appName = compound.groupValues[1].trim()
                var query = compound.groupValues[2].trim()
                val followRequested = Regex("\\s+and\\s+(?:follow|subscribe)(?:\\s+(?:the\\s+)?(?:account|channel|profile))?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(query)
                query = query.replace(Regex("\\s+and\\s+(?:follow|subscribe)(?:\\s+(?:the\\s+)?(?:account|channel|profile))?\\s*$", RegexOption.IGNORE_CASE), "").trim()
                if (query.isBlank()) return "Tell me what you want me to search for."
                if (!openAppSearch(appName, query)) return "I couldn't open $appName or its search. Check that the app or a browser is installed."
                if (!followRequested) "Opening $appName and searching for '$query'."
                else if (!MishaAccessibilityService.isEnabled()) "Search opened for '$query'. Enable Misha Assistant Controls in Accessibility settings and repeat the command to allow the follow/subscribe step."
                else {
                    android.os.Handler(mainLooper).postDelayed({
                        val tappedResult = MishaAccessibilityService.perform("tap $query")
                        android.os.Handler(mainLooper).postDelayed({
                            val tappedFollow = MishaAccessibilityService.perform("tap Follow") || MishaAccessibilityService.perform("tap Subscribe")
                            if (tappedResult && tappedFollow) tts?.speak("I tapped the matching result and follow or subscribe control. Please check the app to confirm the account state.", TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                        }, 1200)
                    }, 1600)
                    "Searching for '$query'. I'll try the visible result and Follow/Subscribe control; app layouts vary, so check the account state afterward."
                }
            } else when {
                Regex("^(?:please\\s+)?(?:take|capture)\\s+(?:a\\s+)?(?:picture|photo|photograph)$", RegexOption.IGNORE_CASE).matches(clean) -> {
                    startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening the camera to take a picture. Android keeps the shutter under your control; tap the shutter to capture it."
                }
                Regex("^(?:please\\s+)?(?:record|take)\\s+(?:a\\s+)?video$", RegexOption.IGNORE_CASE).matches(clean) -> {
                    startActivity(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening the camera in video mode. Tap Record to start and Stop to finish."
                }
                lower in listOf("flashlight on", "turn on flashlight", "switch on flashlight", "torch on", "turn on torch", "flashlight off", "turn off flashlight", "switch off flashlight", "torch off", "turn off torch") -> {
                    val turnOn = lower.endsWith(" on") || lower.startsWith("turn on ") || lower.startsWith("switch on ")
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return "Android reports Camera permission is denied for Misha. Open App permissions, allow Camera, then repeat the flashlight command."
                    }
                    val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                    val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (cameraId == null) "This phone does not expose an available camera flashlight."
                    else {
                        cameraManager.setTorchMode(cameraId, turnOn)
                        if (turnOn) "Flashlight is on." else "Flashlight is off."
                    }
                }
                lower in listOf("lock phone", "lock my phone", "lock the phone", "lock screen", "lock my screen") -> {
                    val policy = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(this, MishaDeviceAdminReceiver::class.java)
                    if (policy.isAdminActive(admin)) {
                        policy.lockNow()
                        "Locking the phone now."
                    } else {
                        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow Misha to lock the phone only when you explicitly request it.")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Please approve Misha's device-admin permission, then repeat 'lock my phone'."
                    }
                }
                lower in listOf("go back", "press back", "back") -> if (MishaAccessibilityService.perform("back")) "Went back." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("go home", "home screen", "go to home screen") -> if (MishaAccessibilityService.perform("home")) "Returned to Home." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("show notifications", "open notifications") -> if (MishaAccessibilityService.perform("notifications")) "Opened notifications." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("open quick settings", "show quick settings", "open flashlight settings", "open torch settings") ->
                    if (MishaAccessibilityService.perform("quick settings")) "Opened Quick Settings. Use the Flashlight tile." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("open settings", "phone settings", "open phone settings") -> {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opening Android Settings."
                }
                lower.startsWith("open ") && lower.endsWith(" settings") -> openSystemSettings(lower)
                lower.startsWith("tap ") -> if (MishaAccessibilityService.perform(clean)) "Tapped a visible item matching that text." else "No visible matching control was clicked. Check Accessibility permission and the exact text."
                lower in listOf("what's on my screen", "what is on my screen", "read my screen") -> MishaAccessibilityService.currentScreen(this)
                lower.startsWith("open ") && lower.contains(" search ") -> {
                    val appName = clean.substringAfter("open ").substringBefore(" search ").trim()
                    val query = clean.substringAfter(" search ").replaceFirst(Regex("^for\\s+", RegexOption.IGNORE_CASE), "").trim()
                    if (appName.isBlank() || query.isBlank()) "Say: open Chrome and search for India weather."
                    else if (openAppSearch(appName, query)) "Opening $appName and searching for '$query'." else "I couldn't open $appName or its search."
                }
                lower.startsWith("search ") || lower.startsWith("google ") || lower.startsWith("look up ") -> {
                    val query = when {
                        lower.startsWith("search ") -> clean.substringAfter("search ").replaceFirst(Regex("^for\\s+", RegexOption.IGNORE_CASE), "").trim()
                        lower.startsWith("google ") -> clean.substringAfter("google ").trim()
                        else -> clean.substringAfter("look up ").trim()
                    }
                    if (query.isBlank()) "What would you like me to search for?" else { openSearch(query, null); "Searching the web for '$query'." }
                }
                lower in listOf("open youtube", "launch youtube") -> { openAppSearch("YouTube", ""); "Opening YouTube." }
                lower in listOf("open instagram", "launch instagram") -> {
                    val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                    if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening Instagram."
                }
                lower.contains("weather") -> { openSearch("current weather near me", null); "Searching the web for current weather." }
                (lower.startsWith("open ") && !lower.startsWith("open whatsapp and send message ") && !lower.startsWith("open whatsapp and message ") && !lower.startsWith("open whatsapp and text ")) || lower.startsWith("launch ") || lower.startsWith("start app ") -> {
                    val appName = when {
                        lower.startsWith("open ") -> clean.substringAfter("open ").trim()
                        lower.startsWith("launch ") -> clean.substringAfter("launch ").trim()
                        else -> clean.substringAfter("start app ").trim()
                    }
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val match = packageManager.queryIntentActivities(launcherIntent, 0).firstOrNull { it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true) }
                    if (match == null || appName.isBlank()) "I couldn't find an installed app matching '$appName'."
                    else {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(match.activityInfo.packageName, match.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opening ${match.loadLabel(packageManager)}."
                    }
                }
                lower.startsWith("whatsapp ") || lower.startsWith("send whatsapp ") || lower.startsWith("open whatsapp and send message ") || lower.startsWith("open whatsapp and message ") || lower.startsWith("open whatsapp and text ") || lower.startsWith("message on whatsapp ") || lower.startsWith("text on whatsapp ") -> {
                    val request = clean.replaceFirst(Regex("^(?:(?:open\\s+)?whatsapp\\s+and\\s+(?:send\\s+)?(?:message|text)|(?:message|text)\\s+on\\s+whatsapp|(?:send\\s+)?whatsapp)\\s*", RegexOption.IGNORE_CASE), "")
                    val recipientAndMessage: Pair<String, String>? = when {
                        Regex("^(?:message|text)\\s+(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val match = Regex("^(?:message|text)\\s+(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            match.groupValues[2].trim() to match.groupValues[1].trim()
                        }
                        Regex("^(.+?)\\s+to\\s+(.+?)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val match = Regex("^(.+?)\\s+to\\s+(.+?)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            match.groupValues[2].trim() to match.groupValues[1].trim()
                        }
                        Regex("^to\\s+(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val match = Regex("^to\\s+(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            match.groupValues[1].trim() to match.groupValues[2].trim()
                        }
                        Regex("^(.+)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val match = Regex("^(.+)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            match.groupValues[2].trim() to match.groupValues[1].trim()
                        }
                        Regex("^(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request) != null -> {
                            val match = Regex("^(.+?)\\s+message\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(request)!!
                            match.groupValues[1].trim() to match.groupValues[2].trim()
                        }
                        else -> null
                    }
                    if (recipientAndMessage == null || recipientAndMessage.first.isBlank() || recipientAndMessage.second.isBlank()) "Say: WhatsApp to CONTACT NAME message TEXT, or open WhatsApp and send message TEXT to CONTACT NAME."
                    else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) "Allow Contacts permission in Misha first, then retry this message."
                    else {
                        val recipient = recipientAndMessage.first.trim()
                        val messageText = recipientAndMessage.second.trim()
                        val phone = findContactNumber(recipient)
                        if (phone == null) "I couldn't find a saved contact matching '$recipient'. Check the exact name."
                        else {
                            val digits = phone.filter(Char::isDigit).let { raw ->
                                if (raw.startsWith("00")) raw.drop(2) else raw
                            }
                            val normalizedNumber = when {
                                digits.length == 10 -> "91$digits"
                                digits.length == 12 && digits.startsWith("91") -> digits
                                digits.length in 11..15 && !digits.startsWith("0") -> digits
                                else -> ""
                            }
                            if (normalizedNumber.isBlank()) return "The saved number for '$recipient' is incomplete or invalid ($phone). Fix that contact in your phone's Contacts app, then try again."
                            val uri = android.net.Uri.parse("https://wa.me/$normalizedNumber?text=" + android.net.Uri.encode(messageText))
                            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (packageManager.getLaunchIntentForPackage("com.whatsapp") != null) intent.setPackage("com.whatsapp")
                            startActivity(intent)
                            if (MishaAccessibilityService.isEnabled()) {
                                android.os.Handler(mainLooper).postDelayed({
                                    val screen = MishaAccessibilityService.currentScreen(this)
                                    val expectedPackage = screen.contains("com.whatsapp", ignoreCase = true)
                                    val messageVisible = screen.contains(messageText.take(minOf(24, messageText.length)), ignoreCase = true)
                                    if (expectedPackage && messageVisible) {
                                        val tapped = MishaAccessibilityService.perform("tap Send")
                                        if (!tapped) android.os.Handler(mainLooper).postDelayed({ MishaAccessibilityService.perform("tap Send") }, 1000)
                                    }
                                }, 3000)
                                "Opened WhatsApp for $recipient and will try to press Send after the chat loads. Check the conversation to confirm delivery."
                            } else "Opened WhatsApp for $recipient with your message ready. Enable Misha Assistant Controls in Accessibility settings for the optional send attempt."
                        }
                    }
                }
                lower.startsWith("call ") || lower.startsWith("dial ") -> {
                    val target = clean.substringAfter(' ').trim()
                    val number = if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) findContactNumber(target) else null
                    val dialTarget = number ?: target.takeIf { it.any(Char::isDigit) }
                    if (dialTarget == null) "I couldn't find that saved contact. Allow Contacts permission in Misha, then try the exact name."
                    else { val uri = android.net.Uri.parse("tel:" + android.net.Uri.encode(dialTarget)); if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) { startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Calling $target now." } else { startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Phone permission is not granted, so I opened the dialer. Allow Phone permission in Android Settings to place calls by voice." } }
                }
                else -> null
            }
        } catch (error: SecurityException) {
            if (lower.contains("flashlight") || lower.contains("torch")) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Android blocked camera/flashlight access. Recheck Misha → App info → Permissions → Camera, and ensure Camera access is enabled in Quick Settings."
            } else "Android blocked this phone action. Recheck the required permission in Misha's App info."
        } catch (error: Exception) { "That phone action failed (${error.javaClass.simpleName}). Check Misha's app permissions and try again." }
    }

    private fun openSystemSettings(command: String): String {
        val action = when {
            command.contains("wi-fi") || command.contains("wifi") -> Settings.ACTION_WIFI_SETTINGS
            command.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            command.contains("location") || command.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            command.contains("display") || command.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
            command.contains("sound") || command.contains("volume") -> Settings.ACTION_SOUND_SETTINGS
            command.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            command.contains("notification") -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            command.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            command.contains("apps") || command.contains("applications") -> Settings.ACTION_APPLICATION_SETTINGS
            command.contains("flashlight") || command.contains("torch") -> {
                if (MishaAccessibilityService.perform("quick settings")) return "Opened Quick Settings. Use the Flashlight tile."
                return "Enable Misha Assistant Controls in Accessibility settings first."
            }
            else -> Settings.ACTION_SETTINGS
        }
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opening the requested Android settings."
    }

    private fun openAppSearch(appName: String, query: String): Boolean {
        val name = appName.trim().lowercase(Locale.ROOT)
        if (name.isBlank()) return false
        val encoded = android.net.Uri.encode(query)
        val packageName = when {
            name.contains("youtube") -> "com.google.android.youtube"
            name.contains("instagram") -> "com.instagram.android"
            name.contains("chrome") -> "com.android.chrome"
            else -> null
        }
        if (query.isBlank()) {
            val launch = packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                ?: findLauncherIntentByLabel(appName)
                ?: packageManager.getLaunchIntentForPackage("com.android.chrome")
                ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/"))
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val target = when {
            name.contains("youtube") -> "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAg%3D%3D"
            name.contains("instagram") -> "https://www.instagram.com/explore/search/keyword/?q=$encoded"
            name.contains("chrome") -> "https://www.google.com/search?q=$encoded"
            else -> null
        }
        if (target != null) {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null && packageManager.getLaunchIntentForPackage(packageName) != null) intent.setPackage(packageName)
            startActivity(intent)
            return true
        }

        // For apps without a public search deep link, launch the real installed app and
        // use user-enabled Accessibility to find its search control and enter the query.
        val launch = findLauncherIntentByLabel(appName) ?: return false
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (MishaAccessibilityService.isEnabled()) {
            android.os.Handler(mainLooper).postDelayed({
                val foundSearch = MishaAccessibilityService.perform("tap Search") ||
                    MishaAccessibilityService.perform("tap Search here") ||
                    MishaAccessibilityService.perform("tap Search")
                if (foundSearch) {
                    android.os.Handler(mainLooper).postDelayed({
                        MishaAccessibilityService.perform("type $query")
                        MishaAccessibilityService.perform("enter")
                    }, 500)
                }
            }, 1400)
        }
        return true
    }

    private fun findLauncherIntentByLabel(appName: String): Intent? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true)
        } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
    }

    private fun openSearch(query: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null && packageManager.getLaunchIntentForPackage(packageName) != null) intent.setPackage(packageName)
        else if (packageManager.getLaunchIntentForPackage("com.android.chrome") != null) intent.setPackage("com.android.chrome")
        startActivity(intent)
    }

    private fun findContactNumber(name: String): String? {
        val wanted = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        if (wanted.isBlank()) return null
        return try {
            val candidates = mutableListOf<Triple<String, String, Int>>()
            contentResolver.query(
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
        } catch (_: Exception) { null }
    }

    private fun parseReply(body: String): String = try {
        val obj = JSONObject(body)
        listOf("reply", "response", "message", "output_text", "answer").firstNotNullOfOrNull { key ->
            obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
        } ?: obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: body.take(500)
    } catch (_: Exception) { body.take(500) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hidePopup()
        stopWakeWordDetector()
        try { wakeWordDetector?.release() } catch (_: Exception) {}
        wakeWordDetector = null
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        executor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "ai.misha.jarvis.action.STOP_FLOATING"
        private const val CHANNEL_ID = "misha_floating_assistant"
        private const val NOTIFICATION_ID = 7401
        private const val HEY_MISHA_MODEL_ASSET = "hey_misha.onnx"
    }
}
