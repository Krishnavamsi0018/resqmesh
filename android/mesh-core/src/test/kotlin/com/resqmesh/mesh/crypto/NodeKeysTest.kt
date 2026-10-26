package com.resqmesh.mesh.crypto

import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NodeKeysTest {
    @Test
    fun `the same private key always gives the same public key and node ID`() {
        val a = Fixtures.keys(1)
        val b = NodeKeys.fromPrivateKey(a.exportPrivateKey())

        assertContentEquals(a.publicKey, b.publicKey)
        assertEquals(a.nodeId, b.nodeId)
        assertEquals(a.publicKeyB64, b.publicKeyB64)
    }

    @Test
    fun `node IDs look like 7F3A-91C2`() {
        val id = NodeKeys.generate().nodeId

        assertTrue(Regex("^[0-9A-F]{16}$").matches(id.hex), id.hex)
        assertTrue(Regex("^[0-9A-F]{4}-[0-9A-F]{4}$").matches(id.short), id.short)
        assertEquals(id.hex.substring(0, 8), id.short.replace("-", ""))
    }

    @Test
    fun `different keys give different node IDs`() {
        assertNotEquals(NodeKeys.generate().nodeId, NodeKeys.generate().nodeId)
    }

    @Test
    fun `toString never shows the private key`() {
        val keys = Fixtures.keys(1)
        assertFalse(keys.toString().contains(keys.exportPrivateKey().toHex()))
    }

    @Test
    fun `exported private key is a copy`() {
        val keys = Fixtures.keys(1)
        keys.exportPrivateKey().fill(0)
        assertTrue(Ed25519.verify(keys.publicKey, byteArrayOf(1), keys.sign(byteArrayOf(1))))
    }

    @Test
    fun `wrong-size private key is refused`() {
        assertFailsWith<IllegalArgumentException> { NodeKeys.fromPrivateKey(ByteArray(31)) }
    }
}
