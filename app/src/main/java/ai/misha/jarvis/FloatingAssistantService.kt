package ai.misha.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * User-enabled floating entry point. It intentionally does not listen to the microphone:
 * wake-word listening requires a separately permission-gated, device-validated audio pipeline.
 */
class FloatingAssistantService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var startX = 0
    private var startY = 0
    private var downX = 0f
    private var downY = 0f

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
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
            .setContentText("Tap the M bubble to open Misha. Tap Stop to dismiss the floating assistant.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopPendingIntent())
            .build()
    }

    private fun stopPendingIntent(): android.app.PendingIntent {
        val intent = Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP)
        return android.app.PendingIntent.getService(
            this, 74, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    private fun attachBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "M"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(157, 123, 255), Color.rgb(67, 42, 145))
            ).apply { shape = GradientDrawable.OVAL; setStroke(2, Color.rgb(220, 195, 255)) }
            contentDescription = "Open Misha assistant"
            elevation = 12f
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            dp(60), dp(60), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(180) }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) < dp(8) && abs(event.rawY - downY) < dp(8)) {
                        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        if (launch != null) startActivity(launch)
                    }
                    true
                }
                else -> true
            }
        }
        try { windowManager?.addView(view, params); bubble = view; layoutParams = params }
        catch (_: Exception) { stopSelf() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null
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
