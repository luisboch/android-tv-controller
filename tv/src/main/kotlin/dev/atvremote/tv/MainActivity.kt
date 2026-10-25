package dev.atvremote.tv

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Status screen: makes sure the service runs and lets the user forget paired phones. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteService.start(this)
        val store = (application as App).store

        val pad = (48 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 20f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "ATV Remote"
                textSize = 32f
            })
            addView(status)
            addView(Button(context).apply {
                text = "Remover pareamentos"
                setOnClickListener {
                    store.clear()
                    refresh()
                }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val phones = (application as App).store.all()
        status.text = if (phones.isEmpty()) {
            "Serviço ativo. Nenhum celular pareado."
        } else {
            "Serviço ativo. Celulares pareados:\n" + phones.joinToString("\n") { "• ${it.name}" }
        }
    }
}
