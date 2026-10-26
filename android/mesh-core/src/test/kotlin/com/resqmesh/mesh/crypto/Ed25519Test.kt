package com.resqmesh.mesh.crypto

import com.resqmesh.mesh.hexToBytes
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {
    // RFC 8032 section 7.1, TEST 1 (empty message). Node's crypto produces the same bytes.
    private val rfcPrivate = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
    private val rfcPublic = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToBytes()
    private val rfcSignature = (
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        ).hexToBytes()

    @Test
    fun `matches RFC 8032 test vector 1`() {
        assertContentEquals(rfcPublic, Ed25519.publicKeyOf(rfcPrivate))
        assertContentEquals(rfcSignature, Ed25519.sign(rfcPrivate, ByteArray(0)))
        assertTrue(Ed25519.verify(rfcPublic, ByteArray(0), rfcSignature))
    }

    @Test
    fun `a fresh key signs and verifies`() {
        val privateKey = Ed25519.generatePrivateKey(SecureRandom())
        val message = "SOS FLOOD".encodeToByteArray()
        val signature = Ed25519.sign(privateKey, message)

        assertEquals(Ed25519.SIGNATURE_SIZE, signature.size)
        assertTrue(Ed25519.verify(Ed25519.publicKeyOf(privateKey), message, signature))
    }

    @Test
    fun `a changed message fails`() {
        val signature = Ed25519.sign(rfcPrivate, "lat 12.97160".encodeToByteArray())
        assertFalse(Ed25519.verify(rfcPublic, "lat 12.97161".encodeToByteArray(), signature))
    }

    @Test
    fun `another key's public key fails`() {
        val message = "hello".encodeToByteArray()
        val signature = Ed25519.sign(rfcPrivate, message)
        val otherPublic = Ed25519.publicKeyOf(Ed25519.generatePrivateKey(SecureRandom()))
        assertFalse(Ed25519.verify(otherPublic, message, signature))
    }

    @Test
    fun `malformed keys and signatures fail without throwing`() {
        val message = "hello".encodeToByteArray()
        val signature = Ed25519.sign(rfcPrivate, message)

        assertFalse(Ed25519.verify(rfcPublic.copyOf(31), message, signature))
        assertFalse(Ed25519.verify(rfcPublic, message, signature.copyOf(63)))
        assertFalse(Ed25519.verify(ByteArray(32) { 0xFF.toByte() }, message, signature))
        assertFalse(Ed25519.verify(rfcPublic, message, ByteArray(64)))
    }
}
