package dev.atvremote.tv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds the text field that has input focus on the TV and inserts text sent from the phone.
 * Accessibility is used (instead of being the IME) so the TV keeps its own on-screen keyboard.
 */
class TextInputService : AccessibilityService() {
    enum class Result {
        /** Inserted through accessibility. */
        INSERTED,

        /** A field is being edited (the keyboard is up) but isn't reachable through accessibility, e.g. a web page. */
        NEEDS_PASTE,

        /** No text field has focus: the text is dropped. */
        NO_FIELD,
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun insertOnMainThread(text: String): Result {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: windows.asSequence().mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.firstOrNull { it.isEditable }
        if (node == null) {
            val keyboardUp = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            return if (keyboardUp) Result.NEEDS_PASTE else Result.NO_FIELD
        }

        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = node.textSelectionEnd.takeIf { it in start..current.length } ?: start
        val updated = current.substring(0, start) + text + current.substring(end)
        val set = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) },
        )
        if (!set) return Result.NEEDS_PASTE
        val cursor = start + text.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return Result.INSERTED
    }

    companion object {
        @Volatile private var instance: TextInputService? = null

        /** Called from a background thread; runs on the service's main thread and waits for it. */
        fun insert(text: String): Result {
            val service = instance ?: return Result.NO_FIELD
            var result = Result.NO_FIELD
            val done = CountDownLatch(1)
            service.mainExecutor.execute {
                result = runCatching { service.insertOnMainThread(text) }.getOrDefault(Result.NO_FIELD)
                done.countDown()
            }
            return if (done.await(2, TimeUnit.SECONDS)) result else Result.NO_FIELD
        }

        /** Turns this accessibility service on; allowed because the app runs as the system uid. */
        fun enable(context: Context) {
            val resolver = context.contentResolver
            val name = ComponentName(context, TextInputService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (name !in enabled.split(':')) {
                val updated = if (enabled.isEmpty()) name else "$enabled:$name"
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }
}
