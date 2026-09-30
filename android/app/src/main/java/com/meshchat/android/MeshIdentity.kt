package com.meshchat.android

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

class MeshIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("meshchat_identity", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private val noisePrivate: ByteArray get() = load("noise", 32)
    private val signingPrivate: ByteArray get() = load("signing", 32)

    val noisePublic: ByteArray get() =
        X25519PrivateKeyParameters(noisePrivate, 0).generatePublicKey().encoded

    val signingPublic: ByteArray get() =
        Ed25519PrivateKeyParameters(signingPrivate, 0).generatePublicKey().encoded

    val peerId: ByteArray get() =
        MessageDigest.getInstance("SHA-256").digest(noisePublic).copyOfRange(0, 8)

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(signingPrivate, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun load(name: String, size: Int): ByteArray {
        prefs.getString(name, null)?.let {
            Base64.decode(it, Base64.NO_WRAP).takeIf { b -> b.size == size }?.let { b -> return b }
        }
        return ByteArray(size).also {
            random.nextBytes(it)
            prefs.edit().putString(name, Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
    }
}
