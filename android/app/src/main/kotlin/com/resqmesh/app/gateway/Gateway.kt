package com.resqmesh.app.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.resqmesh.app.mesh.LogKind
import com.resqmesh.app.mesh.MeshNode
import com.resqmesh.app.mesh.MeshState
import com.resqmesh.app.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes this phone a gateway whenever it has working internet (TRD 2.7): it uploads the SOS and
 * cancels it holds, floods a gateway ack for each one the server confirms, and pulls responder
 * replies every 30 s to flood them back into the mesh. Runs while relaying is on. A phone in
 * airplane mode simply never becomes a gateway. Main thread, except the HTTP calls.
 */
class Gateway(
    context: Context,
    private val node: MeshNode,
    private val state: MeshState,
    private val settings: AppSettings,
    private val gatewayId: String,
    private val scope: CoroutineScope,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var lastAckPullAt = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // VALIDATED: Android checked that this network really reaches the internet.
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            scope.launch { setOnline(online) }
        }

        override fun onLost(network: Network) {
            scope.launch { setOnline(false) }
        }
    }

    fun start() {
        if (loop != null) return
        node.onUploadQueued = { wake.trySend(Unit) }
        connectivity.registerDefaultNetworkCallback(callback)
        loop = scope.launch {
            while (true) {
                cycle()
                withTimeoutOrNull(CYCLE_MS) { wake.receive() }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        node.onUploadQueued = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        state.updateGateway { it.copy(online = false) }
    }

    /** The server URL changed in settings: try it straight away. */
    fun poke() {
        wake.trySend(Unit)
    }

    private fun setOnline(online: Boolean) {
        val was = state.gateway.value.online
        state.updateGateway { it.copy(online = online) }
        if (online && !was) {
            state.log(LogKind.LINK, "Internet available: acting as a gateway")
            wake.trySend(Unit)
        } else if (!online && was) {
            state.log(LogKind.LINK, "Internet lost: no longer a gateway")
        }
    }

    private suspend fun cycle() {
        val url = settings.serverUrl.value
        state.updateGateway { it.copy(serverSet = ServerApi.isValidUrl(url)) }
        if (!state.gateway.value.active) return
        val api = ServerApi(url)
        try {
            // Upload everything waiting, a batch at a time.
            while (true) {
                val batch = node.uploadBatch()
                if (batch.isEmpty()) break
                val reply = withContext(Dispatchers.IO) { api.upload(gatewayId, batch.map { it.envelope }) }
                node.onUploaded(batch, reply.statuses)
                reply.acks.forEach(node::fromServer)
                contact()
                if (reply.statuses.all { it == null }) break // the server answered nothing useful; try later
            }
            val now = System.currentTimeMillis()
            val watched = node.watchedSos()
            if (watched.isNotEmpty() && now - lastAckPullAt >= ACK_PULL_MS) {
                lastAckPullAt = now
                withContext(Dispatchers.IO) { api.acks(watched) }.forEach(node::fromServer)
                contact()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            if (state.gateway.value.lastError != reason) state.log(LogKind.ERROR, "Server unreachable ($reason), retrying")
            state.updateGateway { it.copy(lastError = reason) }
        }
    }

    private fun contact() {
        state.updateGateway { it.copy(lastContactAt = System.currentTimeMillis(), lastError = null) }
    }

    private companion object {
        const val CYCLE_MS = 15_000L
        const val ACK_PULL_MS = 30_000L
    }
}
