package com.resqmesh.app.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.link.EndpointName
import com.resqmesh.mesh.link.LinkCommand
import com.resqmesh.mesh.link.LinkEvents
import com.resqmesh.mesh.link.LinkManager
import com.resqmesh.mesh.link.LinkPolicy
import com.resqmesh.mesh.link.LinkState
import com.resqmesh.mesh.relay.Links

/**
 * Phone-to-phone links over Google Nearby Connections (TRD 2.6), advertising and discovering at
 * the same time with P2P_CLUSTER. Every linking decision lives in [LinkManager] (mesh-core, unit
 * tested); this class only feeds it Nearby's callbacks and carries out its commands.
 * Nearby delivers every callback on the main thread, and all state here is touched only there.
 */
class NearbyTransport(
    context: Context,
    keys: NodeKeys,
    private val node: MeshNode,
    private val state: MeshState,
    private val clock: Clock = Clock.SYSTEM,
) : Links {
    private val client = Nearby.getConnectionsClient(context)
    private val handler = Handler(Looper.getMainLooper())
    private val localShort = keys.nodeId.short
    private val localName = EndpointName(keys.nodeId).encode()
    private var running = false
    private var advertiseFailures = 0
    private var discoverFailures = 0
    private var lastRadioRestartAt = Long.MIN_VALUE / 2

    /** Payloads sent and not yet reported done, by payload ID, so a failed transfer can name its link. */
    private val outgoing = object : LinkedHashMap<Long, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>) = size > MAX_OUTGOING
    }

    private val manager = LinkManager(
        local = keys.nodeId,
        clock = clock,
        policy = LinkPolicy(keys.nodeId, clock),
        commands = ::execute,
        events = object : LinkEvents {
            override fun onLinked(node: NodeId) {
                state.log(LogKind.LINK, "Linked to ${node.short}")
                // Swap summaries so each side fetches what it missed (store-carry-forward).
                this@NearbyTransport.node.onLinked(node)
            }

            override fun onUnlinked(node: NodeId) {
                state.log(LogKind.LINK, "Link to ${node.short} dropped")
            }

            override fun onPeersChanged() = publish()

            override fun onNote(text: String) {
                state.log(LogKind.LINK, text)
            }

            override fun onStuck(node: NodeId) = restartRadio(node)
        },
        statusName = ::statusName,
    )

    fun start() {
        if (running) return
        running = true
        advertiseFailures = 0
        discoverFailures = 0
        manager.clear()
        node.links = this
        state.setRunning(true)
        state.log(LogKind.LINK, "Advertising and discovering as $localShort")
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        manager.clear()
        outgoing.clear()
        node.links = null
        publish()
        state.setRunning(false)
        state.log(LogKind.LINK, "Stopped")
    }

    /** Chain test mode (F-61): null links to anyone, otherwise only these phones. */
    fun setAllowOnly(allowed: Set<NodeId>?) {
        manager.setAllowOnly(allowed)
        wake()
    }

    override fun linked(): Set<NodeId> = manager.linkedNodes()

    override fun send(to: NodeId, bytes: ByteArray) {
        val endpointId = manager.endpointFor(to) ?: return
        val payload = Payload.fromBytes(bytes)
        outgoing[payload.id] = endpointId
        client.sendPayload(endpointId, payload).addOnFailureListener {
            outgoing.remove(payload.id)
            if (!running) return@addOnFailureListener
            manager.onSendFailed(endpointId, codeOf(it))
            wake()
        }
    }

    private fun execute(command: LinkCommand) {
        val endpointId = command.endpointId
        when (command) {
            is LinkCommand.Request -> client.requestConnection(localName, endpointId, lifecycle).addOnFailureListener {
                if (!running) return@addOnFailureListener
                manager.onRequestFailed(endpointId, codeOf(it))
                wake()
            }
            is LinkCommand.Accept -> client.acceptConnection(endpointId, payloads).addOnFailureListener {
                if (!running) return@addOnFailureListener
                manager.onAcceptFailed(endpointId, codeOf(it))
                wake()
            }
            is LinkCommand.Reject -> client.rejectConnection(endpointId)
            is LinkCommand.Disconnect -> client.disconnectFromEndpoint(endpointId)
        }
    }

    /**
     * Nearby stopped answering link requests to [node]. In the 0.3.3 test only Stop and Start got
     * such a pair linked, so restart advertising and discovery, keeping the links that work.
     * At most once a minute, so a phone that has really gone stays a few timeouts, not a restart loop.
     */
    private fun restartRadio(node: NodeId) {
        val now = clock.nowMillis()
        if (now - lastRadioRestartAt < RADIO_RESTART_GAP_MS) return
        lastRadioRestartAt = now
        state.log(LogKind.LINK, "No answer from ${node.short} 3 times, restarting discovery")
        client.stopAdvertising()
        client.stopDiscovery()
        manager.onDiscoveryRestarted()
        startAdvertising()
        startDiscovery()
    }

    /** Runs the manager's next due retry, fallback or timeout, then re-arms for the one after. */
    private fun wake() {
        handler.removeCallbacksAndMessages(TOKEN_TICK)
        if (!running) return
        val at = manager.nextWakeAt() ?: return
        val delay = (at - clock.nowMillis()).coerceAtLeast(0)
        HandlerCompat.postDelayed(handler, {
            if (running) {
                manager.tick()
                wake()
            }
        }, TOKEN_TICK, delay)
    }

    private fun startAdvertising() {
        if (!running) return
        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener {
                if (advertiseFailures > 0) state.log(LogKind.LINK, "Advertising started")
                advertiseFailures = 0
            }
            .addOnFailureListener { error ->
                if (!running || codeOf(error) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) return@addOnFailureListener
                advertiseFailures++
                retryLater("Advertising", error, advertiseFailures, TOKEN_ADVERTISE, ::startAdvertising)
            }
    }

    private fun startDiscovery() {
        if (!running) return
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener {
                if (discoverFailures > 0) state.log(LogKind.LINK, "Discovery started")
                discoverFailures = 0
            }
            .addOnFailureListener { error ->
                if (!running || codeOf(error) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return@addOnFailureListener
                discoverFailures++
                retryLater("Discovery", error, discoverFailures, TOKEN_DISCOVER, ::startDiscovery)
            }
    }

    /** A failed start is retried after 2, 4, 8 … 60 s instead of leaving this phone deaf or invisible. */
    private fun retryLater(what: String, error: Exception, failures: Int, token: Any, again: () -> Unit) {
        val delay = (2_000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(60_000L)
        val hint = if (isPermissionProblem(codeOf(error))) " · open the app and check permissions and Location" else ""
        state.log(LogKind.ERROR, "$what failed (${describe(error)}), retrying in ${delay / 1000} s$hint")
        handler.removeCallbacksAndMessages(token)
        HandlerCompat.postDelayed(handler, { again() }, token, delay)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!running) return
            val name = EndpointName.decode(info.endpointName) ?: return
            manager.onFound(endpointId, name)
            wake()
        }

        override fun onEndpointLost(endpointId: String) {
            if (!running) return
            manager.onLost(endpointId)
            wake()
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!running) return
            val name = EndpointName.decode(info.endpointName)
            if (name == null) {
                client.rejectConnection(endpointId)
                return
            }
            manager.onInitiated(endpointId, name, info.isIncomingConnection)
            wake()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!running) return
            manager.onResult(endpointId, result.status.isSuccess, result.status.statusCode)
            wake()
        }

        override fun onDisconnected(endpointId: String) {
            if (!running) return
            manager.onDisconnected(endpointId)
            wake()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (!running || payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val from = manager.nodeFor(endpointId) ?: return
            node.onBytes(from, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            // Only our own sends are tracked; updates for received payloads find nothing here.
            val sentTo = outgoing.remove(update.payloadId) ?: return
            if (!running || update.status != PayloadTransferUpdate.Status.FAILURE) return
            manager.onSendFailed(sentTo)
            wake()
        }
    }

    private fun publish() {
        state.setPeers(
            manager.peers().map {
                Peer(it.endpointId, it.name, linked = it.state == LinkState.LINKED, connecting = it.state == LinkState.CONNECTING)
            },
        )
    }

    private fun codeOf(error: Exception): Int = (error as? ApiException)?.statusCode ?: CommonStatusCodes.ERROR

    private fun describe(error: Exception): String =
        (error as? ApiException)?.statusCode?.let(::statusName) ?: (error.message ?: error.javaClass.simpleName)

    private fun statusName(code: Int) = "${ConnectionsStatusCodes.getStatusCodeString(code)} $code"

    /** Nearby's MISSING_SETTING_LOCATION_MUST_BE_ON (8025) and MISSING_PERMISSION_* codes (8029–8039). */
    private fun isPermissionProblem(code: Int) = code == 8025 || code in 8029..8039

    private companion object {
        const val SERVICE_ID = "com.resqmesh.mesh"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        const val TOKEN_ADVERTISE = "advertise"
        const val TOKEN_DISCOVER = "discover"
        const val TOKEN_TICK = "tick"
        const val MAX_OUTGOING = 1_000
        const val RADIO_RESTART_GAP_MS = 60_000L
    }
}
