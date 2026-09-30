package com.meshchat.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MeshPacket(
    val version: Int = 1,
    val type: Int,
    val ttl: Int,
    val timestamp: Long,
    val senderId: ByteArray,
    val recipientId: ByteArray?,
    val payload: ByteArray,
    val signature: ByteArray? = null
) {
    fun encode(): ByteArray {
        require(version == 1 && senderId.size == 8 && ttl in 0..255 && payload.size <= 0xFFFF)
        var flags = 0
        if (recipientId != null) { require(recipientId.size == 8); flags = flags or 0x01 }
        if (signature != null) { require(signature.size == 64); flags = flags or 0x02 }
        val size = 22 + (if (recipientId != null) 8 else 0) +
            payload.size + (if (signature != null) 64 else 0)
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(version.toByte()); put(type.toByte()); put(ttl.toByte()); putLong(timestamp)
            put(flags.toByte()); putShort(payload.size.toShort()); put(senderId)
            recipientId?.let(::put); put(payload); signature?.let(::put)
        }.array()
    }

    fun relay(): MeshPacket? = if (ttl <= 1) null else copy(ttl = ttl - 1)

    companion object {
        const val TYPE_ANNOUNCE = 0x01
        const val TYPE_MESSAGE = 0x02
        const val TYPE_LEAVE = 0x03
        const val DEFAULT_TTL = 7
        val BROADCAST = ByteArray(8) { 0xFF.toByte() }

        fun decode(data: ByteArray): MeshPacket? = runCatching {
            if (data.size < 22) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = b.get().toInt() and 0xFF
            if (version != 1) return null
            val type = b.get().toInt() and 0xFF
            val ttl = b.get().toInt() and 0xFF
            val timestamp = b.getLong()
            val flags = b.get().toInt() and 0xFF
            val payloadLength = b.short.toInt() and 0xFFFF
            val hasRecipient = (flags and 0x01) != 0
            val hasSignature = (flags and 0x02) != 0
            val required = 22 + (if (hasRecipient) 8 else 0) + payloadLength +
                (if (hasSignature) 64 else 0)
            if (required > data.size) return null
            val sender = ByteArray(8).also(b::get)
            val recipient = if (hasRecipient) ByteArray(8).also(b::get) else null
            val payload = ByteArray(payloadLength).also(b::get)
            val signature = if (hasSignature) ByteArray(64).also(b::get) else null
            MeshPacket(version, type, ttl, timestamp, sender, recipient, payload, signature)
        }.getOrNull()
    }
}
