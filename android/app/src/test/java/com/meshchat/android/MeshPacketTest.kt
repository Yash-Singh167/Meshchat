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
    fun signatureRoundTripAndSigningPreimageExcludeTtl() {
        val packet = MeshPacket(
            type = MeshPacket.TYPE_MESSAGE,
            ttl = 7,
            timestamp = 1L,
            senderId = ByteArray(8) { it.toByte() },
            recipientId = MeshPacket.BROADCAST,
            payload = "signed".toByteArray(),
            signature = ByteArray(64) { 7 }
        )
        val decoded = MeshPacket.decode(packet.encode())
        assertNotNull(decoded)
        assertArrayEquals(packet.signature, decoded!!.signature)
        val a = packet.copy(ttl = 7).bytesForSigning()
        val b = packet.copy(ttl = 2).bytesForSigning()
        assertArrayEquals(a, b)
    }

    @Test
    fun fragmentRoundTripPreservesOriginalType() {
        val fragment = MeshFragment(123L, 1, 3, MeshPacket.TYPE_MESSAGE, byteArrayOf(1, 2, 3))
        val decoded = MeshFragment.decode(fragment.encode())
        assertNotNull(decoded)
        assertEquals(fragment.id, decoded!!.id)
        assertEquals(fragment.index, decoded.index)
        assertEquals(fragment.total, decoded.total)
        assertEquals(fragment.originalType, decoded.originalType)
        assertArrayEquals(fragment.data, decoded.data)
    }

    @Test
    fun rejectsTruncatedPacket() {
        assertNull(MeshPacket.decode(ByteArray(22)))
    }
}
