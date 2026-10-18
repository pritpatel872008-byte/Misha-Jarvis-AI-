package ai.misha.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
    private var tts: TextToSpeech? = null
    private var transcript: TextView? = null
    private var input: EditText? = null
    private var sendButton: Button? = null
    private var micButton: Button? = null
    private var busy = false
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
        if (Settings.canDrawOverlays(this)) attachBubble() else stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Misha floating assistant", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Misha floating assistant is active")
            .setContentText("Tap M to chat. Microphone starts only when you tap the mic.")
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
                    if (abs(event.rawX - downX) < dp(8).toFloat() && abs(event.rawY - downY) < dp(8).toFloat()) showPopup()
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
    }

    private fun listenOnce() {
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
            override fun onError(error: Int) { transcript?.text = "Voice recognition failed ($error). Please try again or type."; micButton?.isEnabled = true }
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

    private fun submit(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank() || busy) return
        val deviceResult = handleDeviceCommand(clean)
        if (deviceResult != null) {
            transcript?.text = "You: $clean\\n\\nMisha: $deviceResult"
            val tag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
            tts?.let { engine ->
                MishaVoiceProfile.configure(engine, if (tag == "ja-JP") Locale.JAPANESE else Locale.US)
                engine.speak(deviceResult, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            }
            return
        }
        busy = true; sendButton?.isEnabled = false; micButton?.isEnabled = false
        transcript?.text = "You: $clean\n\nMisha is thinking…"
        executor.execute {
            var connection: HttpURLConnection? = null
            val result = try {
                connection = (URL("https://misha-voice-ai-api.vercel.app/api/ai/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 20000
                    setRequestProperty("Content-Type", "application/json"); doOutput = true
                }
                val languageTag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
                val languagePrompt = if (languageTag == "ja-JP") "Reply in Japanese. User request: $clean" else "Reply in English. User request: $clean"
                connection.outputStream.use { it.write(JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", languagePrompt))).toString().toByteArray(Charsets.UTF_8)) }
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
                val tag = getSharedPreferences("misha_settings", MODE_PRIVATE).getString("language", "en-US")
                tts?.let { engine ->
                    MishaVoiceProfile.configure(engine, if (tag == "ja-JP") Locale.JAPANESE else Locale.US)
                    engine.speak(result, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
                }
            }
        }
    }

    private fun handleDeviceCommand(prompt: String): String? {
        val lower = prompt.lowercase(Locale.ROOT)
        return try {
            when {
                lower in listOf("go back", "press back", "back") ->
                    if (MishaAccessibilityService.perform("back")) "Went back." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("go home", "home screen", "go to home screen") ->
                    if (MishaAccessibilityService.perform("home")) "Returned to Home." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("show notifications", "open notifications") ->
                    if (MishaAccessibilityService.perform("notifications")) "Opened notifications." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower in listOf("open quick settings", "show quick settings") ->
                    if (MishaAccessibilityService.perform("quick settings")) "Opened Quick Settings." else "Enable Misha Assistant Controls in Accessibility settings first."
                lower.startsWith("tap ") ->
                    if (MishaAccessibilityService.perform(prompt)) "Tapped a visible item matching that text." else "No visible matching control was clicked. Check Accessibility permission and the exact text."
                lower in listOf("what's on my screen", "what is on my screen", "read my screen") ->
                    MishaAccessibilityService.currentScreen(this)
                lower == "open youtube" || lower == "launch youtube" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening YouTube."
                }
                lower == "open instagram" || lower == "launch instagram" -> {
                    val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                    if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening Instagram."
                }
                lower.startsWith("search ") || lower.startsWith("google ") -> {
                    val query = prompt.substringAfter(' ').trim()
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening Google search for $query."
                }
                lower.contains("weather") -> {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://weather.google.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening live weather."
                }
                lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start app ") -> {
                    val appName = when {
                        lower.startsWith("open ") -> prompt.substringAfter(' ').trim()
                        lower.startsWith("launch ") -> prompt.substringAfter(' ').trim()
                        else -> prompt.substringAfter("start app ").trim()
                    }
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val match = packageManager.queryIntentActivities(launcherIntent, 0).firstOrNull {
                        it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true)
                    }
                    if (match == null || appName.isBlank()) "I couldn't find an installed app matching '$appName'."
                    else {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Opening ${match.loadLabel(packageManager)}."
                    }
                }
                lower.startsWith("call ") || lower.startsWith("dial ") -> {
                    val target = prompt.substringAfter(' ').trim()
                    val number = if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                        findContactNumber(target) else null
                    val dialTarget = number ?: target.takeIf { it.any(Char::isDigit) }
                    if (dialTarget == null) "I couldn't find that saved contact. Allow Contacts permission in Misha, then try the exact name."
                    else {
                        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(dialTarget))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Found the number and opened the dialer. Review it and tap Call to confirm."
                    }
                }
                else -> null
            }
        } catch (_: Exception) { "That phone action couldn't be opened. Check the required permission and try again." }
    }

    private fun findContactNumber(name: String): String? {
        if (name.isBlank()) return null
        return try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$name%"),
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null }
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
    }
}
