package dev.atvremote.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.atvremote.common.Pairing

/**
 * Shows the pairing PIN over whatever is on screen. It lives in its own task, so closing it
 * returns to the app the user was in.
 */
class PinActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pin: TextView
    private lateinit var phone: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        val pad = (32 * resources.displayMetrics.density).toInt()
        pin = TextView(this).apply {
            textSize = 72f
            letterSpacing = 0.3f
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        phone = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad * 2, pad, pad * 2, pad)
            addView(TextView(context).apply {
                text = "Código de pareamento"
                textSize = 22f
                gravity = Gravity.CENTER
            })
            addView(pin)
            addView(phone)
        })
        show(intent)
        current = this
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        pin.text = intent.getStringExtra(EXTRA_PIN).orEmpty()
        phone.text = "Digite no celular \"${intent.getStringExtra(EXTRA_PHONE).orEmpty()}\""
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::finish, Pairing.TIMEOUT_MS.toLong())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PIN = "pin"
        const val EXTRA_PHONE = "phone"

        @Volatile private var current: PinActivity? = null

        fun close() {
            current?.let { it.runOnUiThread(it::finish) }
        }
    }
}
