package dev.atvremote.phone

import dev.atvremote.common.Command
import dev.atvremote.common.Crypto
import dev.atvremote.common.Discovery
import dev.atvremote.common.Ids
import dev.atvremote.common.Pairing
import dev.atvremote.common.Ports
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

data class FoundTv(val address: InetAddress, val name: String)

object TvDiscovery {
    /** Broadcasts a discovery request on every interface and collects the TVs that answer. Blocking. */
    fun discover(timeoutMs: Long = 1500): List<FoundTv> = DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 250
        val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
        repeat(2) {
            targets.forEach { target ->
                runCatching { socket.send(DatagramPacket(Discovery.REQUEST, Discovery.REQUEST.size, target, Ports.DISCOVERY)) }
            }
        }
        val found = linkedMapOf<InetAddress, FoundTv>()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            }
            Discovery.parseResponse(buf, p.length)?.let { found[p.address] = FoundTv(p.address, it) }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.interfaceAddresses.mapNotNull { it.broadcast } }
}

/** One pairing attempt with a TV. Blocking; the TV shows the PIN as soon as this is constructed. */
class PairSession(tv: InetAddress, val deviceId: UUID, phoneName: String) : Closeable {
    sealed interface Result {
        class Paired(val key: ByteArray) : Result
        data object WrongPin : Result
        data object Rejected : Result
    }

    private val socket = Socket()
    private val keys = Crypto.newKeyPair()
    private val phonePub = keys.public.encoded
    private val inp: DataInputStream
    private val out: DataOutputStream
    private val tvPub: ByteArray

    init {
        try {
            socket.connect(InetSocketAddress(tv, Ports.PAIRING), 5_000)
            socket.soTimeout = Pairing.TIMEOUT_MS
            inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            Pairing.writeRequest(out, Pairing.Request(deviceId, phonePub, phoneName))
            tvPub = Pairing.readKey(inp)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    fun tryPin(pin: String): Result {
        val key = Crypto.deriveKey(keys.private, Crypto.decodePublicKey(tvPub), pin, Ids.toBytes(deviceId))
        out.write(Pairing.confirmMac(key, phonePub, tvPub))
        out.flush()
        return when (inp.readUnsignedByte()) {
            Pairing.OK -> {
                val mac = ByteArray(Pairing.MAC_SIZE).also(inp::readFully)
                if (!MessageDigest.isEqual(mac, Pairing.acceptMac(key, phonePub, tvPub))) throw IOException("TV não confirmou o pareamento")
                Result.Paired(key)
            }
            Pairing.WRONG_PIN -> Result.WrongPin
            else -> Result.Rejected
        }
    }

    override fun close() = socket.close()
}

/** Sends encrypted key presses to the paired TV, in order, off the main thread. */
class CommandSender(private val store: PhoneStore) : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val socket by lazy { DatagramSocket() }

    fun send(keyCode: Int, action: Int = Command.PRESS) = send { tv -> Command.key(tv.deviceId, tv.key, store.nextCounter(), keyCode, action) }

    fun sendText(text: String) = send { tv -> Command.text(tv.deviceId, tv.key, store.nextCounter(), text) }

    private fun send(encode: (PairedTv) -> ByteArray) = executor.execute {
        val tv = store.tv ?: return@execute
        runCatching {
            val packet = encode(tv)
            val datagram = DatagramPacket(packet, packet.size, InetAddress.getByName(tv.host), Ports.COMMAND)
            // Wi-Fi drops a few percent of UDP packets; the TV ignores the copy since it has the same counter.
            socket.send(datagram)
            Thread.sleep(15)
            socket.send(datagram)
        }
    }

    override fun close() {
        executor.execute { socket.close() }
        executor.shutdown()
    }
}
