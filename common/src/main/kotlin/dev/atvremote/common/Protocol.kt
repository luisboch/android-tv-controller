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
 * Command datagram (55 bytes): device id (16, also the AAD) || nonce (12) || AES-GCM(counter u64, keycode u16, action u8) || tag (16).
 * The counter must always grow, so captured packets can't be replayed.
 */
object Command {
    const val PRESS = 0
    const val DOWN = 1
    const val UP = 2

    private const val PLAIN_SIZE = 8 + 2 + 1
    const val PACKET_SIZE = Ids.SIZE + Crypto.NONCE_SIZE + PLAIN_SIZE + Crypto.TAG_SIZE

    class Decoded(val counter: Long, val keyCode: Int, val action: Int)

    fun encode(deviceId: UUID, key: ByteArray, counter: Long, keyCode: Int, action: Int = PRESS): ByteArray {
        val id = Ids.toBytes(deviceId)
        val plain = ByteBuffer.allocate(PLAIN_SIZE).putLong(counter).putShort(keyCode.toShort()).put(action.toByte()).array()
        return id + Crypto.seal(key, id, plain)
    }

    fun deviceId(packet: ByteArray): UUID? = if (packet.size == PACKET_SIZE) Ids.fromBytes(packet) else null

    fun decode(key: ByteArray, packet: ByteArray): Decoded? {
        if (packet.size != PACKET_SIZE) return null
        val plain = Crypto.open(key, packet.copyOf(Ids.SIZE), packet, Ids.SIZE) ?: return null
        val buf = ByteBuffer.wrap(plain)
        return Decoded(buf.long, buf.short.toInt() and 0xffff, buf.get().toInt())
    }
}
