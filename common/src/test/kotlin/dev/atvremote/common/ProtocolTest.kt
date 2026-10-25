package dev.atvremote.common

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtocolTest {
    private val id = UUID.randomUUID()

    @Test
    fun bothSidesDeriveTheSameKeyOnlyWithTheSamePin() {
        val phone = Crypto.newKeyPair()
        val tv = Crypto.newKeyPair()
        val idBytes = Ids.toBytes(id)
        val tvKey = Crypto.deriveKey(tv.private, phone.public, "1234", idBytes)
        val phoneKey = Crypto.deriveKey(phone.private, Crypto.decodePublicKey(tv.public.encoded), "1234", idBytes)
        val wrongKey = Crypto.deriveKey(phone.private, tv.public, "1235", idBytes)

        assertContentEquals(tvKey, phoneKey)
        assertEquals(Crypto.KEY_SIZE, tvKey.size)
        assertFalse(tvKey.contentEquals(wrongKey))
    }

    @Test
    fun commandRoundTrip() {
        val key = ByteArray(Crypto.KEY_SIZE) { it.toByte() }
        val packet = Command.encode(id, key, 42, 23, Command.DOWN)

        assertEquals(Command.PACKET_SIZE, packet.size)
        assertEquals(id, Command.deviceId(packet))
        val cmd = assertNotNull(Command.decode(key, packet))
        assertEquals(42, cmd.counter)
        assertEquals(23, cmd.keyCode)
        assertEquals(Command.DOWN, cmd.action)
    }

    @Test
    fun tamperedOrForeignCommandIsRejected() {
        val key = ByteArray(Crypto.KEY_SIZE) { 1 }
        val packet = Command.encode(id, key, 1, 19)

        assertNull(Command.decode(ByteArray(Crypto.KEY_SIZE), packet))
        assertNull(Command.decode(key, packet.copyOf().also { it[30] = (it[30] + 1).toByte() }))
        // Swapping the device id breaks the AAD.
        val other = Ids.toBytes(UUID.randomUUID()) + packet.copyOfRange(Ids.SIZE, packet.size)
        assertNull(Command.decode(key, other))
    }

    @Test
    fun discovery() {
        val res = Discovery.response("Sala")
        assertEquals("Sala", Discovery.parseResponse(res, res.size))
        assertNull(Discovery.parseResponse(Discovery.REQUEST, Discovery.REQUEST.size))
    }
}
