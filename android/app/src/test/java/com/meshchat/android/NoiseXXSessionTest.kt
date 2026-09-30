package com.meshchat.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class NoiseXXSessionTest {
    @Test
    fun xxHandshakeProducesSharedTransportKeys() {
        val aKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val bKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val a = NoiseXXSession(aKey, true)
        val b = NoiseXXSession(bKey, false)

        val m1 = a.writeMessage()
        b.readMessage(m1)

        val m2 = b.writeMessage()
        a.readMessage(m2)

        val m3 = a.writeMessage()
        b.readMessage(m3)

        assertTrue(a.isComplete)
        assertTrue(b.isComplete)

        val ciphertext = a.encrypt("private hello".toByteArray())
        val plaintext = b.decrypt(ciphertext)
        assertArrayEquals("private hello".toByteArray(), plaintext)
    }
}
