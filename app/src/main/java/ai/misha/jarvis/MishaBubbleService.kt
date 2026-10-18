package ai.misha.jarvis

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * User-enabled floating launcher. Android requires explicit overlay permission.
 * The bubble launches the full assistant; it does not pretend to run a wake-word model.
 */
class MishaBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val label = TextView(this).apply {
            text = "M"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(157, 123, 255), Color.rgb(71, 44, 145))).apply {
                shape = GradientDrawable.OVAL
                setStroke(2, Color.rgb(220, 185, 255))
            }
            contentDescription = "Open Misha assistant"
            elevation = 12f
        }
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val layout = WindowManager.LayoutParams(
            dp(58), dp(58), windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(180)
        }
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        label.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    initialX = layout.x; initialY = layout.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (initialX + startX - event.rawX).toInt().coerceAtLeast(0)
                    layout.y = (initialY + event.rawY - startY).toInt().coerceAtLeast(0)
                    try { windowManager?.updateViewLayout(view, layout) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - startX) < dp(8) && kotlin.math.abs(event.rawY - startY) < dp(8)) {
                        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(launch)
                    }
                    true
                }
                else -> false
            }
        }
        try {
            windowManager?.addView(label, layout)
            bubble = label
            params = layout
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
