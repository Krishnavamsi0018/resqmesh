package com.resqmesh.mesh.link

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId

/** What the radio layer (Nearby Connections on Android) must do next. */
sealed interface LinkCommand {
    val endpointId: String

    data class Request(override val endpointId: String) : LinkCommand

    data class Accept(override val endpointId: String) : LinkCommand

    data class Reject(override val endpointId: String) : LinkCommand

    data class Disconnect(override val endpointId: String) : LinkCommand
}

enum class LinkState { SEEN, CONNECTING, LINKED }

data class PeerView(val endpointId: String, val name: EndpointName, val state: LinkState)

/** All optional: what the app logs and shows. */
interface LinkEvents {
    fun onLinked(node: NodeId) {}

    fun onUnlinked(node: NodeId) {}

    fun onPeersChanged() {}

    fun onNote(text: String) {}
}

/**
 * The link state machine for every nearby phone, kept free of Android so it can be tested: the
 * app feeds in Nearby's callbacks and carries out the [LinkCommand]s it gets back.
 *
 * A connection counts as in progress from the moment either side starts it until its result
 * arrives, so this phone never asks again while one is pending. "Already connecting" from Nearby
 * means wait, not fail. Before this, the three-phone test showed pairs colliding for 35 s or never
 * linking. A connection that never reports back is given up after [connectTimeoutMs].
 *
 * Call [tick] at [nextWakeAt] for retries, fallbacks and timeouts. Not thread-safe; main thread only.
 */
class LinkManager(
    private val local: NodeId,
    private val clock: Clock,
    val policy: LinkPolicy,
    private val commands: (LinkCommand) -> Unit,
    private val events: LinkEvents,
    private val statusName: (Int) -> String = { "status $it" },
    private val connectTimeoutMs: Long = 30_000,
) {
    private class Endpoint(val id: String, val name: EndpointName, val firstSeenAt: Long) {
        var discovered = false
        var state = LinkState.SEEN
        var connectingSince = 0L
        var wakeAt: Long? = null
        val node: NodeId get() = name.nodeId
    }

    private val endpoints = LinkedHashMap<String, Endpoint>()

    fun onFound(endpointId: String, name: EndpointName) {
        if (name.nodeId == local) return
        val endpoint = endpoints.getOrPut(endpointId) { Endpoint(endpointId, name, clock.nowMillis()) }
        endpoint.discovered = true
        evaluate(endpoint)
        events.onPeersChanged()
    }

    fun onLost(endpointId: String) {
        val endpoint = endpoints[endpointId] ?: return
        endpoint.discovered = false
        if (endpoint.state == LinkState.SEEN) endpoints.remove(endpointId)
        events.onPeersChanged()
    }

    /** Nearby's onConnectionInitiated: for our own requests ([incoming] false) and for theirs. */
    fun onInitiated(endpointId: String, name: EndpointName, incoming: Boolean) {
        val endpoint = endpoints.getOrPut(endpointId) { Endpoint(endpointId, name, clock.nowMillis()) }
        if (incoming) {
            policy.onInboundAttempt(name.nodeId)
            if (endpoint.state != LinkState.CONNECTING && !policy.canAccept(name.nodeId, busyCount())) {
                commands(LinkCommand.Reject(endpointId))
                return
            }
        }
        endpoint.state = LinkState.CONNECTING
        endpoint.connectingSince = clock.nowMillis()
        endpoint.wakeAt = null
        commands(LinkCommand.Accept(endpointId))
        events.onPeersChanged()
    }

    fun onRequestFailed(endpointId: String, code: Int) {
        val endpoint = endpoints[endpointId] ?: return
        // We already gave this attempt up (chain mode or a timeout): Nearby reports the cancelled
        // request as failed (8007 in the four-phone test), which is neither news nor a reason to back off.
        if (endpoint.state != LinkState.CONNECTING) return
        if (code == STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
            // A connection with this phone is already being set up, usually one it started. Wait for its result.
            events.onNote("Already connecting to ${endpoint.node.short}, waiting")
            return
        }
        failed(endpoint, "Link request to ${endpoint.node.short} failed (${statusName(code)})")
    }

    fun onAcceptFailed(endpointId: String, code: Int) {
        val endpoint = endpoints[endpointId] ?: return
        if (endpoint.state != LinkState.CONNECTING) return
        failed(endpoint, "Connection with ${endpoint.node.short} fell through (${statusName(code)})")
    }

    fun onResult(endpointId: String, success: Boolean, code: Int) {
        val endpoint = endpoints[endpointId] ?: return
        if (success) {
            endpoint.state = LinkState.LINKED
            endpoint.wakeAt = null
            policy.onConnected(endpoint.node)
            // One link per phone: drop an older endpoint for the same phone (it restarted relaying).
            for (other in endpoints.values.toList()) {
                if (other !== endpoint && other.node == endpoint.node && other.state != LinkState.SEEN) {
                    commands(LinkCommand.Disconnect(other.id))
                    other.state = LinkState.SEEN
                }
            }
            events.onLinked(endpoint.node)
        } else {
            // A result for an attempt we already gave up on or rejected changes nothing.
            if (endpoint.state != LinkState.CONNECTING) return
            failed(endpoint, "Link with ${endpoint.node.short} failed (${statusName(code)})")
        }
        events.onPeersChanged()
    }

    fun onDisconnected(endpointId: String) {
        val endpoint = endpoints[endpointId] ?: return
        val wasLinked = endpoint.state == LinkState.LINKED
        endpoint.state = LinkState.SEEN
        policy.onFailedOrDropped(endpoint.node)
        if (wasLinked && endpoint.node !in linkedNodes()) events.onUnlinked(endpoint.node)
        if (!endpoint.discovered) endpoints.remove(endpointId)
        endpoints.values.toList().forEach(::evaluate)
        events.onPeersChanged()
    }

    /**
     * Sending to this phone failed: the link has gone dead without Nearby noticing yet (in the
     * four-phone test one stayed silent for 22 s before it dropped). Hang up so it is rebuilt, and
     * the summaries swapped on relinking resend whatever was lost.
     */
    fun onSendFailed(endpointId: String, code: Int? = null) {
        val endpoint = endpoints[endpointId] ?: return
        if (endpoint.state != LinkState.LINKED) return
        val reason = code?.let { " (${statusName(it)})" } ?: ""
        hangUp(endpoint, "Sending to ${endpoint.node.short} failed$reason, reconnecting")
        policy.onFailedOrDropped(endpoint.node)
        endpoints.values.toList().forEach(::evaluate)
        events.onPeersChanged()
    }

    /** Chain test mode: null links to anyone. Drops links and attempts that no longer qualify. */
    fun setAllowOnly(allowed: Set<NodeId>?) {
        policy.allowOnly = allowed
        for (endpoint in endpoints.values.toList()) {
            if (endpoint.state != LinkState.SEEN && !policy.isAllowed(endpoint.node)) {
                hangUp(endpoint, "Unlinked ${endpoint.node.short} (not ticked in chain mode)")
            }
        }
        endpoints.values.toList().forEach(::evaluate)
        events.onPeersChanged()
    }

    /** Retries whose wait is over, fallbacks that are due, and connections that never reported back. */
    fun tick() {
        val now = clock.nowMillis()
        for (endpoint in endpoints.values.toList()) {
            if (endpoint.state == LinkState.CONNECTING && now - endpoint.connectingSince >= connectTimeoutMs) {
                commands(LinkCommand.Disconnect(endpoint.id))
                failed(endpoint, "Link attempt with ${endpoint.node.short} timed out")
            } else if (endpoint.state == LinkState.SEEN && (endpoint.wakeAt ?: Long.MAX_VALUE) <= now) {
                endpoint.wakeAt = null
                evaluate(endpoint)
            }
        }
        events.onPeersChanged()
    }

    fun nextWakeAt(): Long? = endpoints.values.mapNotNull {
        when (it.state) {
            LinkState.CONNECTING -> it.connectingSince + connectTimeoutMs
            LinkState.SEEN -> it.wakeAt
            LinkState.LINKED -> null
        }
    }.minOrNull()

    fun linkedNodes(): Set<NodeId> = endpoints.values.filter { it.state == LinkState.LINKED }.map { it.node }.toSet()

    fun endpointFor(node: NodeId): String? =
        endpoints.values.firstOrNull { it.state == LinkState.LINKED && it.node == node }?.id

    fun nodeFor(endpointId: String): NodeId? = endpoints[endpointId]?.node

    fun peers(): List<PeerView> = endpoints.values
        .map { PeerView(it.id, it.name, it.state) }
        .sortedWith(compareByDescending<PeerView> { it.state.ordinal }.thenBy { it.name.nodeId.hex })

    fun clear() {
        endpoints.clear()
    }

    /** Ends a link or attempt from this side. Nearby tells only the other side, so update our state here. */
    private fun hangUp(endpoint: Endpoint, linkedNote: String) {
        val wasLinked = endpoint.state == LinkState.LINKED
        commands(LinkCommand.Disconnect(endpoint.id))
        endpoint.state = LinkState.SEEN
        if (wasLinked) {
            events.onUnlinked(endpoint.node)
            events.onNote(linkedNote)
        }
    }

    private fun failed(endpoint: Endpoint, note: String) {
        endpoint.state = LinkState.SEEN
        policy.onFailedOrDropped(endpoint.node)
        if (endpoint.discovered) {
            evaluate(endpoint)
        } else {
            endpoints.remove(endpoint.id)
        }
        val wait = endpoint.wakeAt?.let { " · retrying in ${((it - clock.nowMillis()) + 999) / 1000} s" } ?: ""
        events.onNote(note + wait)
    }

    private fun evaluate(endpoint: Endpoint) {
        if (endpoint.state != LinkState.SEEN || !endpoint.discovered) return
        // Already linked or linking to this phone under another endpoint ID.
        if (endpoints.values.any { it !== endpoint && it.node == endpoint.node && it.state != LinkState.SEEN }) return

        when (val decision = policy.decide(endpoint.node, endpoint.firstSeenAt, busyCount())) {
            LinkDecision.Request -> {
                endpoint.state = LinkState.CONNECTING
                endpoint.connectingSince = clock.nowMillis()
                endpoint.wakeAt = null
                commands(LinkCommand.Request(endpoint.id))
            }
            is LinkDecision.WaitUntil -> endpoint.wakeAt = decision.atMillis
            is LinkDecision.Skip -> endpoint.wakeAt = null
        }
    }

    private fun busyCount() = endpoints.values.count { it.state != LinkState.SEEN }

    companion object {
        /** Nearby's ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT. */
        const val STATUS_ALREADY_CONNECTED_TO_ENDPOINT = 8003
    }
}
