package dev.atvremote.tv

import android.app.Instrumentation
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.atvremote.common.Command
import java.util.concurrent.Executors

/** Injects key events system-wide; works because the app runs as android.uid.system (INJECT_EVENTS). */
class KeyInjector {
    private val instrumentation = Instrumentation()
    private val executor = Executors.newSingleThreadExecutor()
    private var downTime = 0L

    fun inject(keyCode: Int, action: Int) = executor.execute {
        try {
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

    private fun send(downTime: Long, eventTime: Long, action: Int, keyCode: Int) {
        instrumentation.sendKeySync(
            KeyEvent(
                downTime, eventTime, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private companion object {
        const val TAG = "KeyInjector"
    }
}
