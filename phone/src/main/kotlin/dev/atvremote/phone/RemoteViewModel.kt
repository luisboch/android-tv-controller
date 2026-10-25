package dev.atvremote.phone

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.UUID

sealed interface UiState {
    data class Unpaired(val error: String? = null) : UiState
    data object Searching : UiState
    data class ChooseTv(val tvs: List<FoundTv>) : UiState
    data object Connecting : UiState
    data class EnterPin(val tvName: String, val error: String? = null, val busy: Boolean = false) : UiState
    data class Paired(val tvName: String) : UiState
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PhoneStore(app)
    private val sender = CommandSender(store)
    private var session: PairSession? = null
    private var pairingTv: FoundTv? = null

    var state by mutableStateOf(store.tv?.let { UiState.Paired(it.name) } ?: UiState.Unpaired())
        private set

    init {
        if (store.tv != null) refreshHost()
    }

    fun startPairing() {
        state = UiState.Searching
        viewModelScope.launch {
            val tvs = io { TvDiscovery.discover() }
            when (tvs.size) {
                0 -> state = UiState.Unpaired("Nenhuma TV encontrada na rede")
                1 -> connect(tvs[0])
                else -> state = UiState.ChooseTv(tvs)
            }
        }
    }

    fun connectToIp(ip: String) {
        viewModelScope.launch {
            val address = runCatching { io { InetAddress.getByName(ip.trim()) } }.getOrNull()
            if (address == null) state = UiState.Unpaired("IP inválido") else connect(FoundTv(address, ip.trim()))
        }
    }

    fun connect(tv: FoundTv) {
        state = UiState.Connecting
        viewModelScope.launch {
            try {
                session = io { PairSession(tv.address, UUID.randomUUID(), phoneName()) }
                pairingTv = tv
                state = UiState.EnterPin(tv.name)
            } catch (e: Exception) {
                state = UiState.Unpaired("Não foi possível conectar em ${tv.name}")
            }
        }
    }

    fun submitPin(pin: String) {
        val s = session ?: return
        val tv = pairingTv ?: return
        state = UiState.EnterPin(tv.name, busy = true)
        viewModelScope.launch {
            try {
                when (val result = io { s.tryPin(pin) }) {
                    is PairSession.Result.Paired -> {
                        store.tv = PairedTv(s.deviceId, tv.address.hostAddress!!, tv.name, result.key)
                        state = UiState.Paired(tv.name)
                        closeSession()
                    }
                    PairSession.Result.WrongPin -> state = UiState.EnterPin(tv.name, "PIN incorreto")
                    PairSession.Result.Rejected -> fail("PIN incorreto. Tente parear de novo.")
                }
            } catch (e: Exception) {
                fail("Pareamento interrompido")
            }
        }
    }

    fun cancelPairing() = fail(null)

    fun unpair() {
        store.tv = null
        state = UiState.Unpaired()
    }

    fun press(keyCode: Int) = sender.send(keyCode)

    fun sendText(text: String) = sender.sendText(text)

    /** The TV's IP may have changed (DHCP); look for it again by name. */
    private fun refreshHost() = viewModelScope.launch {
        val tv = store.tv ?: return@launch
        val found = io { TvDiscovery.discover() }
        val match = found.firstOrNull { it.name == tv.name } ?: found.singleOrNull() ?: return@launch
        store.updateHost(match.address.hostAddress!!)
    }

    private fun fail(error: String?) {
        closeSession()
        state = UiState.Unpaired(error)
    }

    private fun closeSession() {
        val s = session
        session = null
        pairingTv = null
        if (s != null) viewModelScope.launch { io { s.close() } }
    }

    private fun phoneName(): String =
        Settings.Global.getString(getApplication<Application>().contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override fun onCleared() {
        session?.close()
        sender.close()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
