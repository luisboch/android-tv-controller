package dev.atvremote.phone

import android.content.Context
import android.util.Base64
import java.util.UUID

class PairedTv(val deviceId: UUID, val host: String, val name: String, val key: ByteArray)

class PhoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)

    var tv: PairedTv?
        get() {
            val id = prefs.getString("id", null) ?: return null
            return PairedTv(
                UUID.fromString(id),
                prefs.getString("host", null) ?: return null,
                prefs.getString("name", "").orEmpty(),
                Base64.decode(prefs.getString("key", null) ?: return null, Base64.NO_WRAP),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    clear()
                } else {
                    putString("id", value.deviceId.toString())
                    putString("host", value.host)
                    putString("name", value.name)
                    putString("key", Base64.encodeToString(value.key, Base64.NO_WRAP))
                    putLong("counter", 0)
                }
            }.apply()
        }

    fun updateHost(host: String) = prefs.edit().putString("host", host).apply()

    /** Strictly increasing across restarts; the TV drops any command whose counter isn't newer than the last one. */
    @Synchronized
    fun nextCounter(): Long {
        val next = maxOf(prefs.getLong("counter", 0) + 1, System.currentTimeMillis())
        prefs.edit().putLong("counter", next).apply()
        return next
    }
}
