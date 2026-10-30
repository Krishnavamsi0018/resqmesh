package com.resqmesh.mesh.relay

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketValidator

/**
 * An in-memory radio for tests: phones, links between them, and a queue of bytes in flight,
 * delivered in order. The week-5 simulator grows from this.
 */
class FakeMesh(val clock: FixedClock = FixedClock(Fixtures.NOW)) {
    val server = Fixtures.keys(0x81)
    private val phones = LinkedHashMap<NodeId, Phone>()
    private val inFlight = ArrayDeque<Triple<NodeId, NodeId, ByteArray>>()

    /** Packets (not control frames) actually handed to a receiver. */
    var packetTransmissions = 0
        private set

    data class Delivery(val body: PacketBody, val packet: Packet, val from: NodeId)

    inner class Phone(seed: Int, config: RouterConfig) : Links, RouterEvents {
        val keys = Fixtures.keys(seed)
        val id: NodeId = keys.nodeId
        val factory = PacketFactory(keys, clock)
        val neighbours = LinkedHashSet<NodeId>()
        val delivered = mutableListOf<Delivery>()
        val duplicates = mutableListOf<PacketBody>()
        val dropped = mutableListOf<Pair<DropKind, String>>()
        val router = Router(PacketValidator(server.publicKey, clock), this, this, clock, config)

        override fun linked(): Set<NodeId> = neighbours.toSet()

        override fun send(to: NodeId, bytes: ByteArray) {
            inFlight.addLast(Triple(id, to, bytes))
        }

        override fun onDelivered(packet: Packet, body: PacketBody, from: NodeId) {
            delivered += Delivery(body, packet, from)
        }

        override fun onDuplicate(body: PacketBody, from: NodeId) {
            duplicates += body
        }

        override fun onDropped(kind: DropKind, detail: String, from: NodeId) {
            dropped += kind to detail
        }
    }

    fun phone(seed: Int, config: RouterConfig = RouterConfig()): Phone = Phone(seed, config).also { phones[it.id] = it }

    fun link(a: Phone, b: Phone) {
        a.neighbours += b.id
        b.neighbours += a.id
        a.router.onLinked(b.id)
        b.router.onLinked(a.id)
    }

    fun chain(vararg line: Phone) {
        line.toList().zipWithNext { a, b -> link(a, b) }
        deliverAll()
    }

    /** Delivers everything in flight, and everything that triggers, over the links as they are now. */
    fun deliverAll() {
        while (inFlight.isNotEmpty()) {
            val (from, to, bytes) = inFlight.removeFirst()
            val receiver = phones.getValue(to)
            if (from !in receiver.neighbours) continue
            if (!ControlFrame.isControl(bytes)) packetTransmissions++
            receiver.router.onReceive(from, bytes)
        }
    }
}
