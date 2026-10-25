package dev.atvremote.common

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH P-256 for pairing, HKDF-SHA256 to derive a 128-bit key bound to the PIN,
 * and AES-128-GCM for commands (12-byte nonce + 16-byte tag = 28 bytes of overhead).
 */
object Crypto {
    const val KEY_SIZE = 16
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    private val random = SecureRandom()

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun deriveKey(own: PrivateKey, peer: PublicKey, pin: String, deviceId: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }
        return hkdf(shared, pin.toByteArray(), "atvremote-v1".toByteArray() + deviceId, KEY_SIZE)
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /** Returns nonce || ciphertext || tag. */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
            updateAAD(aad)
        }
        return nonce + cipher.doFinal(plaintext)
    }

    /** Returns the plaintext, or null if the data was tampered with or sealed with another key. */
    fun open(key: ByteArray, aad: ByteArray, sealed: ByteArray, offset: Int = 0): ByteArray? {
        if (sealed.size - offset < NONCE_SIZE + TAG_SIZE) return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, sealed, offset, NONCE_SIZE))
                updateAAD(aad)
                doFinal(sealed, offset + NONCE_SIZE, sealed.size - offset - NONCE_SIZE)
            }
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    fun randomPin(): String = "%04d".format(random.nextInt(10_000))
}
