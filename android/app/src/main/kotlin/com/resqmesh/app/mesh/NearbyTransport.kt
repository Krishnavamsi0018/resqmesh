package com.resqmesh.app.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.link.EndpointName
import com.resqmesh.mesh.link.LinkDecision
import com.resqmesh.mesh.link.LinkPolicy

/**
 * Phone-to-phone links over Google Nearby Connections (TRD 2.6): advertises and discovers at the
 * same time with P2P_CLUSTER, links automatically with no pairing prompt (trust comes from packet
 * signatures, not the link), and keeps at most [LinkPolicy.maxLinks] links.
 * Nearby delivers every callback on the main thread, and all state here is touched only there.
 */
class NearbyTransport(
    context: Context,
    keys: NodeKeys,
    private val node: MeshNode,
    private val state: MeshState,
    private val clock: Clock = Clock.SYSTEM,
) : Outbound {
    private val client = Nearby.getConnectionsClient(context)
    private val handler = Handler(Looper.getMainLooper())
    private val policy = LinkPolicy(keys.nodeId, clock)
    private val localShort = keys.nodeId.short
    private val localName = EndpointName(keys.nodeId).encode()
    private val endpoints = HashMap<String, Endpoint>()
    private var running = false

    private class Endpoint(val id: String, val name: EndpointName, val firstSeenAt: Long) {
        var discovered = false
        var requesting = false
        var linked = false
    }

    fun start() {
        if (running) return
        running = true
        node.outbound = this
        state.setRunning(true)
        state.log(LogKind.LINK, "Advertising and discovering as $localShort")

        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { fail("Advertising", it) }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { fail("Discovery", it) }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpoints.clear()
        node.outbound = null
        publish()
        state.setRunning(false)
        state.log(LogKind.LINK, "Stopped")
    }

    override fun broadcast(bytes: ByteArray): Int {
        val targets = endpoints.values.filter { it.linked }
        for (endpoint in targets) {
            client.sendPayload(endpoint.id, Payload.fromBytes(bytes))
                .addOnFailureListener { fail("Send to ${endpoint.name.nodeId.short}", it) }
        }
        return targets.size
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val name = EndpointName.decode(info.endpointName) ?: return
            val endpoint = endpoints.getOrPut(endpointId) { Endpoint(endpointId, name, clock.nowMillis()) }
            endpoint.discovered = true
            publish()
            considerLink(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            val endpoint = endpoints[endpointId] ?: return
            endpoint.discovered = false
            if (!endpoint.linked && !endpoint.requesting) forget(endpointId)
            publish()
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val name = EndpointName.decode(info.endpointName)
            if (name == null) {
                client.rejectConnection(endpointId)
                return
            }
            val endpoint = endpoints.getOrPut(endpointId) { Endpoint(endpointId, name, clock.nowMillis()) }
            if (!endpoint.requesting && !policy.canAccept(linkedCount())) {
                client.rejectConnection(endpointId)
                return
            }
            client.acceptConnection(endpointId, payloads).addOnFailureListener { fail("Accept ${name.nodeId.short}", it) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val endpoint = endpoints[endpointId] ?: return
            endpoint.requesting = false
            if (result.status.isSuccess) {
                endpoint.linked = true
                policy.onConnected(endpoint.name.nodeId)
                state.log(LogKind.LINK, "Linked to ${endpoint.name.nodeId.short}")
            } else {
                policy.onFailedOrDropped(endpoint.name.nodeId)
                state.log(LogKind.LINK, "Link to ${endpoint.name.nodeId.short} failed (status ${result.status.statusCode})")
                retryOrForget(endpointId)
            }
            publish()
        }

        override fun onDisconnected(endpointId: String) {
            val endpoint = endpoints[endpointId] ?: return
            endpoint.linked = false
            policy.onFailedOrDropped(endpoint.name.nodeId)
            state.log(LogKind.LINK, "Link to ${endpoint.name.nodeId.short} dropped")
            retryOrForget(endpointId)
            publish()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            node.onBytes(endpoints[endpointId]?.name?.nodeId?.short ?: endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun considerLink(endpointId: String) {
        if (!running) return
        val endpoint = endpoints[endpointId] ?: return
        if (endpoint.linked || endpoint.requesting || !endpoint.discovered) return

        when (val decision = policy.decide(endpoint.name.nodeId, endpoint.firstSeenAt, linkedCount())) {
            LinkDecision.Request -> {
                endpoint.requesting = true
                client.requestConnection(localName, endpointId, lifecycle).addOnFailureListener {
                    endpoint.requesting = false
                    policy.onFailedOrDropped(endpoint.name.nodeId)
                    fail("Link request to ${endpoint.name.nodeId.short}", it)
                    retryOrForget(endpointId)
                }
            }
            is LinkDecision.WaitUntil -> schedule(endpointId, decision.atMillis - clock.nowMillis())
            is LinkDecision.Skip -> Unit
        }
    }

    private fun retryOrForget(endpointId: String) {
        val endpoint = endpoints[endpointId] ?: return
        if (endpoint.discovered) considerLink(endpointId) else forget(endpointId)
    }

    /** One pending re-check per endpoint; a newer one replaces it. */
    private fun schedule(endpointId: String, delayMillis: Long) {
        handler.removeCallbacksAndMessages(endpointId)
        HandlerCompat.postDelayed(handler, { considerLink(endpointId) }, endpointId, delayMillis.coerceAtLeast(0))
    }

    private fun forget(endpointId: String) {
        handler.removeCallbacksAndMessages(endpointId)
        endpoints.remove(endpointId)
    }

    private fun linkedCount() = endpoints.values.count { it.linked }

    private fun publish() {
        state.setPeers(
            endpoints.values
                .map { Peer(it.id, it.name, it.linked) }
                .sortedWith(compareByDescending<Peer> { it.linked }.thenBy { it.name.nodeId.hex }),
        )
    }

    private fun fail(what: String, error: Exception) {
        val code = (error as? ApiException)?.statusCode?.let { "status $it" } ?: error.message
        state.log(LogKind.ERROR, "$what failed ($code)")
    }

    private companion object {
        const val SERVICE_ID = "com.resqmesh.mesh"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
