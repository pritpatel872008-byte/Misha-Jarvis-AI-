package ai.misha.jarvis

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/**
 * Optional, user-enabled assistive control service. Commands are deliberately limited to
 * explicit global navigation and text-target clicks; it does not silently approve dialogs.
 */
class MishaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        val text = event.text?.joinToString(" ") { it.toString() }.orEmpty()
        if (packageName.isNotBlank() || text.isNotBlank()) {
            getSharedPreferences("misha_screen_context", MODE_PRIVATE).edit()
                .putString("package", packageName)
                .putString("text", text.take(3000))
                .putLong("updated_at", System.currentTimeMillis())
                .apply()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: WeakReference<MishaAccessibilityService>? = null

        fun isEnabled(): Boolean = instance?.get() != null

        fun perform(command: String): Boolean {
            val service = instance?.get() ?: return false
            return when (command.lowercase()) {
                "back" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                "notifications" -> service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quick settings" -> service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                else -> {
                    val target = command.removePrefix("tap ").trim()
                    if (target.isBlank()) false else service.clickVisibleText(target)
                }
            }
        }

        fun currentScreen(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("misha_screen_context", MODE_PRIVATE)
            val packageName = prefs.getString("package", "").orEmpty()
            val text = prefs.getString("text", "").orEmpty()
            val age = System.currentTimeMillis() - prefs.getLong("updated_at", 0L)
            if (age > 15_000L || (packageName.isBlank() && text.isBlank())) {
                return "Screen context is unavailable or stale. Enable Misha Assistant Controls in Android Accessibility settings and open the app screen again."
            }
            return "Current app: $packageName\nVisible text: ${text.ifBlank { "(no text exposed by this app)" }}"
        }
    }

    private fun clickVisibleText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByText(target)
            val node = nodes?.firstOrNull { it.isVisibleToUser && it.isClickable }
                ?: nodes?.firstOrNull { it.isVisibleToUser }
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } catch (_: Exception) { false }
        finally { root.recycle() }
    }
}
