package com.meshchat.android

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Noise_XX_25519_ChaChaPoly_SHA256.
 *
 * Implements the three-message XX handshake used by MeshChat private sessions:
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 *
 * The implementation follows the Noise framework processing rules. Handshake
 * messages are transport-agnostic byte strings; BLE routing is handled by the
 * mesh layer.
 */
class NoiseXXSession(
    private val staticPrivate: ByteArray,
    private val initiator: Boolean,
    private val prologue: ByteArray = ByteArray(0)
) {
    companion object {
        const val PROTOCOL = "Noise_XX_25519_ChaChaPoly_SHA256"
        private const val HASH_LEN = 32
        private const val KEY_LEN = 32
        private const val TAG_LEN = 16

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = HMac(SHA256Digest())
            mac.init(KeyParameter(key))
            mac.update(data, 0, data.size)
            return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
        }

        private fun hkdf(ck: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
            val temp = hmac(ck, input)
            val out1 = hmac(temp, byteArrayOf(1))
            val out2 = hmac(temp, out1 + byteArrayOf(2))
            return out1 to out2
        }

        private fun nonce(n: Long): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0)
                putLong(n)
            }.array()
    }

    private var ck: ByteArray
    private var h: ByteArray
    private var key: ByteArray? = null
    private var nonceCounter = 0L
    private var state = if (initiator) 0 else 1

    private var localEphemeral: X25519PrivateKeyParameters? = null
    private var remoteEphemeral: X25519PublicKeyParameters? = null
    private var remoteStatic: X25519PublicKeyParameters? = null
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null

    val isComplete: Boolean get() = state == 3
    val handshakeHash: ByteArray get() = h.copyOf()

    init {
        require(staticPrivate.size == KEY_LEN)
        val protocol = PROTOCOL.toByteArray(Charsets.UTF_8)
        h = if (protocol.size <= HASH_LEN) protocol.copyOf(HASH_LEN) else sha256(protocol)
        ck = h.copyOf()
        mixHash(prologue)
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(state == if (initiator) 0 else 2) { "unexpected Noise write state $state" }

        return if (initiator && state == 0) {
            localEphemeral = X25519PrivateKeyParameters(SecureRandom())
            val e = localEphemeral!!.generatePublicKey().encoded
            mixHash(e)
            state = 2
            e + payload
        } else if (!initiator && state == 2) {
            val eKey = X25519PrivateKeyParameters(SecureRandom())
            localEphemeral = eKey
            val e = eKey.generatePublicKey().encoded
            mixHash(e)
            mixKey(dh(eKey, remoteEphemeral!!))
            val encryptedStatic = encryptAndHash(staticPublic())
            mixKey(dh(eKeyFromStatic(), remoteEphemeral!!))
            val encryptedPayload = encryptAndHash(payload)
            state = 2
            e + encryptedStatic + encryptedPayload
        } else {
            // Initiator message 3: encrypted static, then se, then payload.
            val encryptedStatic = encryptAndHash(staticPublic())
            mixKey(dh(eKeyFromStatic(), remoteEphemeral!!))
            val encryptedPayload = encryptAndHash(payload)
            split()
            encryptedStatic + encryptedPayload
        }
    }

    fun readMessage(message: ByteArray): ByteArray {
        return when {
            !initiator && state == 1 -> {
                require(message.size >= KEY_LEN)
                remoteEphemeral = X25519PublicKeyParameters(message, 0)
                mixHash(message.copyOfRange(0, KEY_LEN))
                state = 2
                ByteArray(0)
            }
            initiator && state == 2 -> {
                require(message.size >= KEY_LEN + KEY_LEN + TAG_LEN + TAG_LEN)
                remoteEphemeral = X25519PublicKeyParameters(message, 0)
                mixHash(message.copyOfRange(0, KEY_LEN))
                mixKey(dh(localEphemeral!!, remoteEphemeral!!))
                val staticEnd = KEY_LEN + KEY_LEN + TAG_LEN
                val remoteStaticBytes = decryptAndHash(message.copyOfRange(KEY_LEN, staticEnd))
                require(remoteStaticBytes.size == KEY_LEN)
                remoteStatic = X25519PublicKeyParameters(remoteStaticBytes, 0)
                mixKey(dh(localEphemeral!!, remoteStatic!!))
                val payload = decryptAndHash(message.copyOfRange(staticEnd, message.size))
                state = 0
                payload
            }
            !initiator && state == 2 -> {
                require(message.size >= KEY_LEN + TAG_LEN + TAG_LEN)
                val staticCipherEnd = KEY_LEN + TAG_LEN
                val remoteStaticBytes = decryptAndHash(message.copyOfRange(0, staticCipherEnd))
                remoteStatic = X25519PublicKeyParameters(remoteStaticBytes, 0)
                mixKey(dh(localEphemeral!!, remoteStatic!!))
                val payload = decryptAndHash(message.copyOfRange(staticCipherEnd, message.size))
                split()
                payload
            }
            else -> error("unexpected Noise read state $state")
        }
    }

    fun encrypt(payload: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        check(isComplete)
        return sendCipher!!.encrypt(payload, aad)
    }

    fun decrypt(ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        check(isComplete)
        return receiveCipher!!.decrypt(ciphertext, aad)
    }

    private fun staticPublic(): ByteArray =
        X25519PrivateKeyParameters(staticPrivate, 0).generatePublicKey().encoded

    private fun eKeyFromStatic(): X25519PrivateKeyParameters =
        X25519PrivateKeyParameters(staticPrivate, 0)

    private fun dh(privateKey: X25519PrivateKeyParameters, publicKey: X25519PublicKeyParameters): ByteArray =
        ByteArray(KEY_LEN).also { privateKey.generateSecret(publicKey, it, 0) }

    private fun mixKey(input: ByteArray) {
        val (newCk, newKey) = hkdf(ck, input)
        ck = newCk
        key = newKey
        nonceCounter = 0
    }

    private fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = if (key == null) plaintext else CipherState(key!!).encrypt(plaintext, h)
        mixHash(ciphertext)
        return ciphertext
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = if (key == null) ciphertext else CipherState(key!!).decrypt(ciphertext, h)
        mixHash(ciphertext)
        return plaintext
    }

    private fun split() {
        val (k1, k2) = hkdf(ck, ByteArray(0))
        if (initiator) {
            sendCipher = CipherState(k1)
            receiveCipher = CipherState(k2)
        } else {
            sendCipher = CipherState(k2)
            receiveCipher = CipherState(k1)
        }
        key = null
        state = 3
    }

    private class CipherState(private val key: ByteArray) {
        private var nonce = 0L

        fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = ChaCha20Poly1305()
            val out = ByteArray(plaintext.size + TAG_LEN)
            cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce(nonce++), aad))
            cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
            cipher.doFinal(out, plaintext.size)
            return out
        }

        fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            require(ciphertext.size >= TAG_LEN)
            val cipher = ChaCha20Poly1305()
            val out = ByteArray(ciphertext.size - TAG_LEN)
            cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce(nonce), aad))
            val n = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            cipher.doFinal(out, n)
            nonce++
            return out
        }
    }
}
