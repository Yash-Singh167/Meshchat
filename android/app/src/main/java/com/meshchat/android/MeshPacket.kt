package com.meshchat.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * MeshChat v1 packet envelope.
 *
 * This mirrors the established bitchat wire layout:
 *   version(1), type(1), ttl(1), timestamp(8), flags(1), payloadLength(2)
 *   senderId(8), optional recipientId(8), payload, optional Ed25519 signature(64)
 *
 * Multi-byte fields are network/big-endian order. TTL is deliberately part of
 * the routing header and is not covered by signatures so relays can decrement it.
 */
data class MeshPacket(
    val version: Int = 1,
    val type: Int,
    val ttl: Int,
    val timestamp: Long,
    val senderId: ByteArray,
    val recipientId: ByteArray? = null,
    val payload: ByteArray,
    val signature: ByteArray? = null
) {
    fun encode(): ByteArray {
        require(version == 1)
        require(senderId.size == ID_SIZE)
        require(ttl in 0..255)
        require(payload.size <= 0xFFFF)
        require(recipientId == null || recipientId.size == ID_SIZE)
        require(signature == null || signature.size == SIGNATURE_SIZE)

        var flags = 0
        if (recipientId != null) flags = flags or FLAG_RECIPIENT
        if (signature != null) flags = flags or FLAG_SIGNATURE

        val size = HEADER_SIZE + ID_SIZE +
            (if (recipientId != null) ID_SIZE else 0) +
            payload.size +
            (if (signature != null) SIGNATURE_SIZE else 0)

        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(version.toByte())
            put(type.toByte())
            put(ttl.toByte())
            putLong(timestamp)
            put(flags.toByte())
            putShort(payload.size.toShort())
            put(senderId)
            recipientId?.let(::put)
            put(payload)
            signature?.let(::put)
        }.array()
    }

    /**
     * Signature input. TTL is excluded so a relay may decrement TTL without
     * invalidating the origin signature.
     */
    fun bytesForSigning(): ByteArray {
        val raw = copy(signature = null, ttl = SIGNING_TTL).encode()
        val target = optimalPaddingSize(raw.size)
        val pad = target - raw.size
        if (pad <= 0 || pad > 255) return raw
        return raw + ByteArray(pad) { pad.toByte() }
    }

    fun relay(): MeshPacket? {
        if (ttl <= 1) return null
        return copy(ttl = ttl - 1)
    }

    fun isBroadcast(): Boolean =
        recipientId == null || recipientId.contentEquals(BROADCAST)

    fun isFor(recipient: ByteArray): Boolean =
        recipientId == null || recipientId.contentEquals(BROADCAST) ||
            recipientId.contentEquals(recipient)

    companion object {
        const val VERSION = 1
        const val TYPE_ANNOUNCE = 0x01
        const val TYPE_MESSAGE = 0x02
        const val TYPE_LEAVE = 0x03
        const val TYPE_NOISE_HANDSHAKE = 0x10
        const val TYPE_NOISE_ENCRYPTED = 0x11
        const val TYPE_FRAGMENT = 0x20
        const val TYPE_REQUEST_SYNC = 0x21
        const val TYPE_FILE_TRANSFER = 0x22

        const val DEFAULT_TTL = 7
        const val MAX_TTL = 7

        const val FLAG_RECIPIENT = 0x01
        const val FLAG_SIGNATURE = 0x02

        const val ID_SIZE = 8
        const val SIGNATURE_SIZE = 64
        const val HEADER_SIZE = 14
        const val SIGNING_TTL = 0

        private fun optimalPaddingSize(size: Int): Int {
            val total = size + 16
            return listOf(256, 512, 1024, 2048).firstOrNull { total <= it } ?: size
        }

        val BROADCAST = ByteArray(ID_SIZE) { 0xFF.toByte() }

        fun decode(data: ByteArray): MeshPacket? = runCatching {
            if (data.size < HEADER_SIZE + ID_SIZE) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = b.get().toInt() and 0xFF
            if (version != VERSION) return null

            val type = b.get().toInt() and 0xFF
            val ttl = b.get().toInt() and 0xFF
            val timestamp = b.getLong()
            val flags = b.get().toInt() and 0xFF
            val payloadLength = b.short.toInt() and 0xFFFF

            val hasRecipient = (flags and FLAG_RECIPIENT) != 0
            val hasSignature = (flags and FLAG_SIGNATURE) != 0

            val expected = HEADER_SIZE + ID_SIZE +
                (if (hasRecipient) ID_SIZE else 0) +
                payloadLength +
                (if (hasSignature) SIGNATURE_SIZE else 0)

            if (expected != data.size) return null

            val sender = ByteArray(ID_SIZE).also(b::get)
            val recipient = if (hasRecipient) ByteArray(ID_SIZE).also(b::get) else null
            val payload = ByteArray(payloadLength).also(b::get)
            val signature = if (hasSignature) ByteArray(SIGNATURE_SIZE).also(b::get) else null

            MeshPacket(version, type, ttl, timestamp, sender, recipient, payload, signature)
        }.getOrNull()

        fun dedupKey(packet: MeshPacket, wire: ByteArray): String =
            packet.senderId.toHex() + ":" + packet.timestamp + ":" +
                packet.type + ":" + MessageDigest.getInstance("SHA-256")
                    .digest(wire).toHex()
    }
}

data class MeshFragment(
    val id: Long,
    val index: Int,
    val total: Int,
    val originalType: Int,
    val data: ByteArray
) {
    fun encode(): ByteArray =
        ByteBuffer.allocate(13 + data.size).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(id)
            putShort(index.toShort())
            putShort(total.toShort())
            put(originalType.toByte())
            put(data)
        }.array()

    companion object {
        const val HEADER_SIZE = 13
        fun decode(payload: ByteArray): MeshFragment? = runCatching {
            if (payload.size < HEADER_SIZE) return null
            val b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val id = b.long
            val index = b.short.toInt() and 0xFFFF
            val total = b.short.toInt() and 0xFFFF
            val originalType = b.get().toInt() and 0xFF
            if (total !in 1..10_000 || index !in 0 until total) return null
            val body = ByteArray(b.remaining()).also(b::get)
            MeshFragment(id, index, total, originalType, body)
        }.getOrNull()
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
