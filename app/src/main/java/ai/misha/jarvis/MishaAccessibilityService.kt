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
        // Store only the foreground package and timestamp. Screen text is read on demand
        // after an explicit user request and is never persisted to preferences.
        if (packageName.isNotBlank()) {
            getSharedPreferences("misha_screen_context", MODE_PRIVATE).edit()
                .putString("package", packageName)
                .putLong("updated_at", System.currentTimeMillis())
                .apply()
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, output: StringBuilder, depth: Int) {
        if (depth > 20 || output.length >= 3000 || !node.isVisibleToUser) return
        val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
        if (label.isNotBlank()) output.append(label).append(" | ")
        for (index in 0 until node.childCount) {
            if (output.length >= 3000) break
            val child = node.getChild(index) ?: continue
            try { collectVisibleText(child, output, depth + 1) } catch (_: Exception) { }
            finally { child.recycle() }
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
            val normalized = command.lowercase()
            return when (normalized) {
                "back" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                "notifications" -> service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quick settings" -> service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "enter", "search", "submit" -> service.pressEnterOnFocusedField()
                else -> when {
                    normalized.startsWith("tap ") -> {
                        val target = command.substringAfter(' ').trim()
                        target.isNotBlank() && service.clickVisibleText(target)
                    }
                    normalized.startsWith("type ") -> service.typeIntoFocusedField(command.substringAfter(' ').trim())
                    else -> false
                }
            }
        }

        fun currentScreen(context: android.content.Context): String {
            val service = instance?.get()
            if (service == null) return "Enable Misha Assistant Controls in Android Accessibility settings first."
            return service.readVisibleScreen()
        }
    }

    private fun readVisibleScreen(): String {
        val root = rootInActiveWindow ?: return "The current app does not expose screen content to accessibility."
        val visible = StringBuilder()
        try { collectVisibleText(root, visible, 0) } catch (_: Exception) { }
        finally { root.recycle() }
        val packageName = getSharedPreferences("misha_screen_context", MODE_PRIVATE).getString("package", "unknown")
        return "Current app: $packageName\nVisible text: ${visible.toString().trim().take(3000).ifBlank { "(no readable text exposed)" }}"
    }

    private fun typeIntoFocusedField(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        var focused: AccessibilityNodeInfo? = null
        return try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused?.isEditable == true && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { false }
        finally { focused?.recycle(); root.recycle() }
    }

    private fun pressEnterOnFocusedField(): Boolean {
        val root = rootInActiveWindow ?: return false
        var focused: AccessibilityNodeInfo? = null
        return try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER) == true
        } catch (_: Exception) { false }
        finally { focused?.recycle(); root.recycle() }
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
