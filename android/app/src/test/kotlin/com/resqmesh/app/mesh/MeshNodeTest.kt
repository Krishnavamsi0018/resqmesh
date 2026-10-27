package com.resqmesh.app.mesh

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two phones wired back to back: everything in week 2 except the radio. */
class MeshNodeTest {
    private val clock = Clock { 1_793_000_000_000L }
    private val server = NodeKeys.fromPrivateKey(ByteArray(32) { (0x81 + it).toByte() })

    private fun phone(seed: Int, state: MeshState) =
        MeshNode(NodeKeys.fromPrivateKey(ByteArray(32) { (seed + it).toByte() }), PacketValidator(server.publicKey, clock), state, clock)

    @Test
    fun `a test SOS sent by one phone is accepted by the other`() {
        val aState = MeshState()
        val bState = MeshState()
        val a = phone(1, aState)
        val b = phone(2, bState)
        a.outbound = Outbound { bytes -> b.onBytes("A", bytes); 1 }

        a.sendTestSos(Category.FLOOD)

        assertEquals(1, aState.counters.value.sent)
        assertEquals(1, bState.counters.value.accepted)
        val entry = bState.log.value.first()
        assertEquals(LogKind.ACCEPTED, entry.kind)
        assertTrue(entry.text.startsWith("SOS FLOOD"), entry.text)
        assertTrue(entry.text.contains("hops 0"), entry.text)
    }

    @Test
    fun `the same packet twice counts once plus a duplicate`() {
        val state = MeshState()
        val receiver = phone(2, state)
        var sent: ByteArray? = null
        phone(1, MeshState()).apply { outbound = Outbound { sent = it; 1 } }.sendTestSos(Category.MEDICAL)

        receiver.onBytes("A", sent!!)
        receiver.onBytes("A", sent!!)

        assertEquals(1, state.counters.value.accepted)
        assertEquals(1, state.counters.value.duplicates)
    }

    @Test
    fun `a phone ignores its own SOS when it comes back`() {
        val state = MeshState()
        val a = phone(1, state)
        var sent: ByteArray? = null
        a.outbound = Outbound { sent = it; 1 }
        a.sendTestSos(Category.FIRE)

        a.onBytes("B", sent!!)

        assertEquals(0, state.counters.value.accepted)
        assertEquals(1, state.counters.value.duplicates)
    }

    @Test
    fun `tampered and garbage bytes are rejected`() {
        val state = MeshState()
        val receiver = phone(2, state)
        var sent: ByteArray? = null
        phone(1, MeshState()).apply { outbound = Outbound { sent = it; 1 } }.sendTestSos(Category.FLOOD)
        val tampered = sent!!.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        receiver.onBytes("A", tampered)
        receiver.onBytes("A", byteArrayOf(1, 2, 3))

        assertEquals(0, state.counters.value.accepted)
        assertEquals(2, state.counters.value.rejected)
        assertTrue(state.log.value.any { it.text.contains("BAD_SIGNATURE") })
        assertTrue(state.log.value.any { it.text.contains("Malformed (TOO_SHORT)") })
    }

    @Test
    fun `sending with no linked phones still logs the attempt`() {
        val state = MeshState()
        phone(1, state).sendTestSos(Category.WATER)

        assertEquals(1, state.counters.value.sent)
        assertTrue(state.log.value.first().text.endsWith("to 0 phone(s)"))
    }
}
