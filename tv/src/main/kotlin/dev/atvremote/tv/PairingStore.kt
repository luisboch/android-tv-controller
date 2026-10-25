package dev.atvremote.tv

import android.content.Context
import android.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PairedPhone(val id: UUID, val name: String, val key: ByteArray)

/** Paired phones and the last command counter seen from each (for replay protection). */
class PairingStore(context: Context) {
    private val phones = context.getSharedPreferences("phones", Context.MODE_PRIVATE)
    private val counters = context.getSharedPreferences("counters", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<UUID, PairedPhone>()

    init {
        phones.all.forEach { (id, value) ->
            val (key, name) = (value as String).split('|', limit = 2)
            val uuid = UUID.fromString(id)
            cache[uuid] = PairedPhone(uuid, name, Base64.decode(key, Base64.NO_WRAP))
        }
    }

    fun all(): List<PairedPhone> = cache.values.sortedBy { it.name }

    fun get(id: UUID): PairedPhone? = cache[id]

    fun add(phone: PairedPhone) {
        cache[phone.id] = phone
        phones.edit().putString(phone.id.toString(), Base64.encodeToString(phone.key, Base64.NO_WRAP) + "|" + phone.name).apply()
        counters.edit().remove(phone.id.toString()).apply()
    }

    fun clear() {
        cache.clear()
        phones.edit().clear().apply()
        counters.edit().clear().apply()
    }

    /** Records [counter] and returns true only if it is newer than anything seen from this phone. */
    @Synchronized
    fun acceptCounter(id: UUID, counter: Long): Boolean {
        if (counter <= counters.getLong(id.toString(), 0)) return false
        counters.edit().putLong(id.toString(), counter).apply()
        return true
    }
}
