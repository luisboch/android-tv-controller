package dev.atvremote.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

object Ports {
    /** UDP: the phone broadcasts [Discovery.REQUEST], the TV answers with its name. */
    const val DISCOVERY = 47100

    /** TCP: pairing handshake. */
    const val PAIRING = 47101

    /** UDP: encrypted key commands. */
    const val COMMAND = 47102
}

object Ids {
    const val SIZE = 16

    fun toBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(SIZE).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    fun fromBytes(b: ByteArray, offset: Int = 0): UUID {
        val buf = ByteBuffer.wrap(b, offset, SIZE)
        return UUID(buf.long, buf.long)
    }
}

object Discovery {
    val REQUEST = "ATVC?".toByteArray()
    private val RESPONSE = "ATVC!".toByteArray()

    fun isRequest(data: ByteArray, length: Int): Boolean =
        length == REQUEST.size && REQUEST.indices.all { data[it] == REQUEST[it] }

    fun response(tvName: String): ByteArray = RESPONSE + tvName.toByteArray().take(200).toByteArray()

    /** Returns the TV name, or null if this isn't a discovery response. */
    fun parseResponse(data: ByteArray, length: Int): String? {
        if (length < RESPONSE.size || RESPONSE.indices.any { data[it] != RESPONSE[it] }) return null
        return String(data, RESPONSE.size, length - RESPONSE.size)
    }
}

/**
 * Pairing over TCP:
 *  phone -> tv: MAGIC, device id, phone public key, phone name
 *  tv -> phone: tv public key (the TV now shows a 4-digit PIN)
 *  repeat up to [MAX_ATTEMPTS]:
 *    phone -> tv: confirmMac (computed with the key derived from the typed PIN)
 *    tv -> phone: OK + acceptMac | WRONG_PIN | REJECTED (no attempts left)
 */
object Pairing {
    const val MAGIC = 0x41545650 // "ATVP"
    const val MAX_ATTEMPTS = 3
    const val TIMEOUT_MS = 120_000
    const val MAC_SIZE = 32

    const val WRONG_PIN = 0
    const val OK = 1
    const val REJECTED = 2

    class Request(val deviceId: UUID, val publicKey: ByteArray, val name: String)

    fun writeRequest(out: DataOutputStream, r: Request) {
        out.writeInt(MAGIC)
        out.write(Ids.toBytes(r.deviceId))
        writeKey(out, r.publicKey)
        out.writeUTF(r.name.take(100))
        out.flush()
    }

    fun readRequest(inp: DataInputStream): Request {
        if (inp.readInt() != MAGIC) throw IOException("bad magic")
        val id = ByteArray(Ids.SIZE).also(inp::readFully)
        val key = readKey(inp)
        return Request(Ids.fromBytes(id), key, inp.readUTF())
    }

    fun writeKey(out: DataOutputStream, key: ByteArray) {
        out.writeShort(key.size)
        out.write(key)
        out.flush()
    }

    fun readKey(inp: DataInputStream): ByteArray {
        val len = inp.readUnsignedShort()
        if (len == 0 || len > 512) throw IOException("bad key length")
        return ByteArray(len).also(inp::readFully)
    }

    fun confirmMac(key: ByteArray, phonePub: ByteArray, tvPub: ByteArray): ByteArray =
        Crypto.hmac(key, "confirm".toByteArray(), phonePub, tvPub)

    fun acceptMac(key: ByteArray, phonePub: ByteArray, tvPub: ByteArray): ByteArray =
        Crypto.hmac(key, "accept".toByteArray(), phonePub, tvPub)
}

/**
 * Command datagram: device id (16, also the AAD) || nonce (12) || AES-GCM(counter u64, type u8, payload) || tag (16).
 *  - key:  payload = keycode u16, action u8 (56 bytes in total)
 *  - text: payload = UTF-8 text, up to [MAX_TEXT_BYTES]
 * The counter must always grow, so captured packets can't be replayed.
 */
object Command {
    const val PRESS = 0
    const val DOWN = 1
    const val UP = 2

    const val MAX_TEXT_BYTES = 1000

    private const val TYPE_KEY = 1
    private const val TYPE_TEXT = 2
    private const val HEADER_SIZE = 8 + 1
    private const val OVERHEAD = Ids.SIZE + Crypto.NONCE_SIZE + HEADER_SIZE + Crypto.TAG_SIZE
    const val MAX_PACKET_SIZE = OVERHEAD + MAX_TEXT_BYTES

    sealed class Decoded(val counter: Long) {
        class Key(counter: Long, val keyCode: Int, val action: Int) : Decoded(counter)
        class Text(counter: Long, val text: String) : Decoded(counter)
    }

    fun key(deviceId: UUID, key: ByteArray, counter: Long, keyCode: Int, action: Int = PRESS): ByteArray =
        encode(deviceId, key, counter, TYPE_KEY, ByteBuffer.allocate(3).putShort(keyCode.toShort()).put(action.toByte()).array())

    fun text(deviceId: UUID, key: ByteArray, counter: Long, text: String): ByteArray {
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_TEXT_BYTES) { "text too long" }
        return encode(deviceId, key, counter, TYPE_TEXT, bytes)
    }

    private fun encode(deviceId: UUID, key: ByteArray, counter: Long, type: Int, payload: ByteArray): ByteArray {
        val id = Ids.toBytes(deviceId)
        val plain = ByteBuffer.allocate(HEADER_SIZE + payload.size).putLong(counter).put(type.toByte()).put(payload).array()
        return id + Crypto.seal(key, id, plain)
    }

    fun deviceId(packet: ByteArray): UUID? = if (packet.size in OVERHEAD..MAX_PACKET_SIZE) Ids.fromBytes(packet) else null

    fun decode(key: ByteArray, packet: ByteArray): Decoded? {
        if (deviceId(packet) == null) return null
        val plain = Crypto.open(key, packet.copyOf(Ids.SIZE), packet, Ids.SIZE) ?: return null
        val buf = ByteBuffer.wrap(plain)
        val counter = buf.long
        return when (buf.get().toInt()) {
            TYPE_KEY -> if (buf.remaining() == 3) Decoded.Key(counter, buf.short.toInt() and 0xffff, buf.get().toInt()) else null
            TYPE_TEXT -> Decoded.Text(counter, String(plain, HEADER_SIZE, plain.size - HEADER_SIZE))
            else -> null
        }
    }
}
