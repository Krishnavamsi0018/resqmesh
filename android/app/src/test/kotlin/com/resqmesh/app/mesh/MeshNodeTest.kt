package com.resqmesh.app.mesh

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.relay.Links
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phones wired back to back: everything except the radio. */
class MeshNodeTest {
    private val clock = Clock { 1_793_000_000_000L }
    private val server = NodeKeys.fromPrivateKey(ByteArray(32) { (0x81 + it).toByte() })

    private inner class Phone(seed: Int) : Links {
        val state = MeshState()
        val node = MeshNode(NodeKeys.fromPrivateKey(ByteArray(32) { (seed + it).toByte() }), PacketValidator(server.publicKey, clock), state, clock)
        val neighbours = LinkedHashMap<NodeId, MeshNode>()

        init {
            node.links = this
        }

        override fun linked(): Set<NodeId> = neighbours.keys

        override fun send(to: NodeId, bytes: ByteArray) {
            neighbours.getValue(to).onBytes(node.id, bytes)
        }
    }

    private fun link(a: Phone, b: Phone) {
        a.neighbours[b.node.id] = b.node
        b.neighbours[a.node.id] = a.node
    }

    /** Captures what a phone sends instead of delivering it. */
    private fun capturing(phone: Phone, to: NodeId): () -> ByteArray {
        var sent: ByteArray? = null
        phone.node.links = object : Links {
            override fun linked() = setOf(to)

            override fun send(to: NodeId, bytes: ByteArray) {
                sent = bytes
            }
        }
        return { assertNotNull(sent) }
    }

    @Test
    fun `a test SOS sent by one phone is accepted by the other`() {
        val (a, b) = Phone(1) to Phone(2)
        link(a, b)

        a.node.sendTestSos(Category.FLOOD)

        assertEquals(1, a.state.counters.value.sent)
        assertEquals(1, b.state.counters.value.accepted)
        val entry = b.state.log.value.first()
        assertEquals(LogKind.ACCEPTED, entry.kind)
        assertTrue(entry.text.startsWith("SOS FLOOD"), entry.text)
        assertTrue(entry.text.contains("hops 0"), entry.text)
    }

    @Test
    fun `the middle phone forwards to the far phone`() {
        val (a, b, c) = listOf(Phone(1), Phone(2), Phone(3))
        link(a, b)
        link(b, c)

        a.node.sendTestSos(Category.MEDICAL)

        assertEquals(1, b.state.counters.value.forwarded)
        assertTrue(b.state.log.value.any { it.kind == LogKind.FORWARDED && it.text.contains(c.node.id.short) })
        val atC = c.state.log.value.single { it.kind == LogKind.ACCEPTED }.text
        assertTrue(atC.contains("from ${a.node.id.short} via ${b.node.id.short} · hops 1"), atC)
        assertEquals(0, a.state.counters.value.accepted)
    }

    @Test
    fun `the same packet twice counts once plus a duplicate`() {
        val (a, b) = Phone(1) to Phone(2)
        val sent = capturing(a, b.node.id)
        a.node.sendTestSos(Category.MEDICAL)

        b.node.onBytes(a.node.id, sent())
        b.node.onBytes(a.node.id, sent())

        assertEquals(1, b.state.counters.value.accepted)
        assertEquals(1, b.state.counters.value.duplicates)
    }

    @Test
    fun `a phone ignores its own SOS when it comes back`() {
        val (a, b) = Phone(1) to Phone(2)
        val sent = capturing(a, b.node.id)
        a.node.sendTestSos(Category.FIRE)

        a.node.onBytes(b.node.id, sent())

        assertEquals(0, a.state.counters.value.accepted)
        assertEquals(1, a.state.counters.value.duplicates)
    }

    @Test
    fun `tampered and garbage bytes are dropped`() {
        val (a, b) = Phone(1) to Phone(2)
        val sent = capturing(a, b.node.id)
        a.node.sendTestSos(Category.FLOOD)
        val tampered = sent().copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        b.node.onBytes(a.node.id, tampered)
        b.node.onBytes(a.node.id, byteArrayOf(1, 2, 3))

        assertEquals(0, b.state.counters.value.accepted)
        assertEquals(2, b.state.counters.value.rejected)
        assertTrue(b.state.log.value.any { it.text.contains("INVALID: BAD_SIGNATURE") })
        assertTrue(b.state.log.value.any { it.text.contains("MALFORMED: TOO_SHORT") })
    }

    @Test
    fun `an SOS sent with nobody around is held for later`() {
        val a = Phone(1)

        a.node.sendTestSos(Category.WATER)

        assertTrue(a.state.log.value.first().text.endsWith("to 0 phone(s)"))
        assertEquals(1, a.state.held.value)
    }
}
