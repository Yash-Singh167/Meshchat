package com.meshchat.android

import org.junit.Assert.*
import org.junit.Test

class MeshPacketTest {
    @Test
    fun roundTrip_preservesV1Packet() {
        val packet = MeshPacket(
            type = MeshPacket.TYPE_MESSAGE,
            ttl = 7,
            timestamp = 123456789L,
            senderId = byteArrayOf(0,1,2,3,4,5,6,7),
            recipientId = MeshPacket.BROADCAST,
            payload = "hello mesh".toByteArray()
        )

        val decoded = MeshPacket.decode(packet.encode())
        assertNotNull(decoded)
        assertEquals(packet.version, decoded!!.version)
        assertEquals(packet.type, decoded.type)
        assertEquals(packet.ttl, decoded.ttl)
        assertEquals(packet.timestamp, decoded.timestamp)
        assertArrayEquals(packet.senderId, decoded.senderId)
        assertArrayEquals(packet.recipientId, decoded.recipientId)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    @Test
    fun relay_decrementsTtlAndStopsAtOne() {
        val packet = MeshPacket(
            type = MeshPacket.TYPE_MESSAGE,
            ttl = 2,
            timestamp = 1L,
            senderId = ByteArray(8),
            recipientId = MeshPacket.BROADCAST,
            payload = byteArrayOf(1)
        )

        assertEquals(1, packet.relay()!!.ttl)
        assertNull(packet.relay()!!.relay())
    }

    @Test
    fun rejectsTruncatedPacket() {
        assertNull(MeshPacket.decode(ByteArray(21)))
    }
}
