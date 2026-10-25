package dev.atvremote.tv

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.atvremote.common.Command
import java.util.concurrent.Executors

/** Injects key events system-wide; works because the app runs as android.uid.system (INJECT_EVENTS). */
class KeyInjector(context: Context) {
    private val instrumentation = Instrumentation()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var downTime = 0L

    fun inject(keyCode: Int, action: Int) = executor.execute {
        try {
            if (adjustVolume(keyCode, action)) return@execute
            val now = SystemClock.uptimeMillis()
            when (action) {
                Command.PRESS -> {
                    send(now, now, KeyEvent.ACTION_DOWN, keyCode)
                    send(now, now, KeyEvent.ACTION_UP, keyCode)
                }
                Command.DOWN -> {
                    downTime = now
                    send(now, now, KeyEvent.ACTION_DOWN, keyCode)
                }
                Command.UP -> send(downTime, now, KeyEvent.ACTION_UP, keyCode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "inject $keyCode failed", e)
        }
    }

    /** Puts [text] on the clipboard and presses Ctrl+V, for fields accessibility can't reach (web pages). */
    fun paste(text: String) = executor.execute {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("ATV Remote", text))
            val now = SystemClock.uptimeMillis()
            send(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            send(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        } catch (e: Exception) {
            Log.w(TAG, "paste failed", e)
        }
    }

    /**
     * Volume keys go straight to the media stream, so the focused app or its media session
     * can't swallow them. Returns false for any other key.
     */
    private fun adjustVolume(keyCode: Int, action: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        if (action != Command.UP) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun send(downTime: Long, eventTime: Long, action: Int, keyCode: Int, metaState: Int = 0) {
        instrumentation.sendKeySync(
            KeyEvent(
                downTime, eventTime, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private companion object {
        const val TAG = "KeyInjector"
    }
}
