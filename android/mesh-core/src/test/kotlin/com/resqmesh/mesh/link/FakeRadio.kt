package com.resqmesh.mesh.link

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.crypto.NodeId

/**
 * Nearby Connections as far as linking goes: asynchronous, one pending connection per pair,
 * a second request for that pair answered with "already connecting" (8003), both sides must
 * accept, and a disconnect reaches the other side.
 */
class FakeRadio(val clock: FixedClock = FixedClock(1_000_000)) {
    private val phones = mutableListOf<Phone>()
    private val queue = ArrayDeque<Pair<Phone, LinkCommand>>()
    private val connections = mutableListOf<Connection>()

    private class Connection(val a: Phone, val b: Phone) {
        val accepted = HashSet<Phone>()
        var connected = false

        fun has(x: Phone, y: Phone) = (a == x && b == y) || (a == y && b == x)

        fun other(x: Phone) = if (x == a) b else a
    }

    inner class Phone(hex: String, configure: (LinkPolicy) -> Unit) : LinkEvents {
        val id = NodeId(hex)
        val name = EndpointName(id)
        val linkedTo = mutableListOf<NodeId>()
        val unlinkedFrom = mutableListOf<NodeId>()
        val notes = mutableListOf<String>()

        /** Link requests this phone has made. On real Bluetooth, two crossing requests both fail (8012). */
        var requests = 0
        val manager = LinkManager(
            local = id,
            clock = clock,
            policy = LinkPolicy(id, clock, jitter = 0.0).also(configure),
            commands = {
                if (it is LinkCommand.Request) requests++
                queue.addLast(this to it)
            },
            events = this,
        )

        /** The endpoint ID other phones know this one by. */
        val endpointId = "ep-${id.hex}"

        override fun onLinked(node: NodeId) {
            linkedTo += node
        }

        override fun onUnlinked(node: NodeId) {
            unlinkedFrom += node
        }

        override fun onNote(text: String) {
            notes += text
        }
    }

    fun phone(hex: String, configure: (LinkPolicy) -> Unit = {}): Phone = Phone(hex, configure).also { phones += it }

    /** Both phones discover each other. */
    fun inRange(a: Phone, b: Phone) {
        a.manager.onFound(b.endpointId, b.name)
        b.manager.onFound(a.endpointId, a.name)
    }

    /** Lets time pass and every phone run its due retries and timeouts. */
    fun advance(millis: Long) {
        clock.now += millis
        phones.forEach { it.manager.tick() }
    }

    /** Carries out every queued command, and everything those trigger. */
    fun run() {
        while (queue.isNotEmpty()) step()
    }

    /** The radio loses everything still in the air. */
    fun dropQueued() {
        queue.clear()
    }

    /** Carries out the next queued command only. */
    fun step() {
        val (from, command) = queue.removeFirst()
        val to = phones.first { it.endpointId == command.endpointId }
        val connection = connections.firstOrNull { it.has(from, to) }
        when (command) {
            is LinkCommand.Request ->
                if (connection != null) {
                    from.manager.onRequestFailed(to.endpointId, LinkManager.STATUS_ALREADY_CONNECTED_TO_ENDPOINT)
                } else {
                    connections += Connection(from, to)
                    from.manager.onInitiated(to.endpointId, to.name, incoming = false)
                    to.manager.onInitiated(from.endpointId, from.name, incoming = true)
                }
            is LinkCommand.Accept ->
                if (connection == null) {
                    from.manager.onAcceptFailed(to.endpointId, 8011)
                } else {
                    connection.accepted += from
                    if (connection.accepted.size == 2 && !connection.connected) {
                        connection.connected = true
                        connection.a.manager.onResult(connection.b.endpointId, true, 0)
                        connection.b.manager.onResult(connection.a.endpointId, true, 0)
                    }
                }
            is LinkCommand.Reject -> if (connection != null) {
                connections -= connection
                connection.a.manager.onResult(connection.b.endpointId, false, 8004)
                connection.b.manager.onResult(connection.a.endpointId, false, 8004)
            }
            is LinkCommand.Disconnect -> if (connection != null) {
                connections -= connection
                val other = connection.other(from)
                if (connection.connected) {
                    other.manager.onDisconnected(from.endpointId)
                } else {
                    other.manager.onResult(from.endpointId, false, 13)
                }
            }
        }
    }

    /** The radio link between two phones breaks on its own (out of range, Bluetooth hiccup). */
    fun breakLink(a: Phone, b: Phone) {
        val connection = connections.first { it.has(a, b) }
        connections -= connection
        a.manager.onDisconnected(b.endpointId)
        b.manager.onDisconnected(a.endpointId)
    }

    fun connectionCount() = connections.count { it.connected }
}
