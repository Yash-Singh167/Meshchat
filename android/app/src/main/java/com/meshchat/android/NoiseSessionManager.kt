package com.meshchat.android

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class NoiseSessionManager(context: Context) {
    private val identity = MeshIdentity(context)
    private data class Session(val noise: NoiseXXSession, val initiator: Boolean)
    private val sessions = ConcurrentHashMap<String, Session>()

    fun initiate(peerId: ByteArray): ByteArray {
        val key = peerId.toHex()
        val noise = NoiseXXSession(identity.noisePrivateKey(), true)
        sessions[key] = Session(noise, true)
        return noise.writeMessage()
    }

    fun handleHandshake(peerId: ByteArray, message: ByteArray): ByteArray? {
        val key = peerId.toHex()
        val session = sessions[key]
        if (session == null) {
            val noise = NoiseXXSession(identity.noisePrivateKey(), false)
            sessions[key] = Session(noise, false)
            noise.readMessage(message)
            return noise.writeMessage()
        }

        session.noise.readMessage(message)
        return if (!session.noise.isComplete) session.noise.writeMessage() else null
    }

    fun isEstablished(peerId: ByteArray): Boolean =
        sessions[peerId.toHex()]?.noise?.isComplete == true

    fun encrypt(peerId: ByteArray, plaintext: ByteArray): ByteArray? =
        sessions[peerId.toHex()]?.takeIf { it.noise.isComplete }?.noise?.encrypt(plaintext)

    fun decrypt(peerId: ByteArray, ciphertext: ByteArray): ByteArray? =
        runCatching {
            sessions[peerId.toHex()]?.takeIf { it.noise.isComplete }?.noise?.decrypt(ciphertext)
        }.getOrNull()

    fun remove(peerId: ByteArray) {
        sessions.remove(peerId.toHex())
    }
}
