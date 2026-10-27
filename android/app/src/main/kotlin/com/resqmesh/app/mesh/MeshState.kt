package com.resqmesh.app.mesh

import com.resqmesh.mesh.link.EndpointName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Peer(val endpointId: String, val name: EndpointName, val linked: Boolean)

enum class LogKind { SENT, ACCEPTED, DUPLICATE, REJECTED, LINK, ERROR }

data class LogEntry(val atMillis: Long, val kind: LogKind, val text: String)

data class Counters(val sent: Int = 0, val accepted: Int = 0, val duplicates: Int = 0, val rejected: Int = 0)

/** What the test screen and the notification show. Written by the transport and the node. */
class MeshState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    /** Newest first, capped at [MAX_LOG]. */
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _counters = MutableStateFlow(Counters())
    val counters: StateFlow<Counters> = _counters.asStateFlow()

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setPeers(peers: List<Peer>) {
        _peers.value = peers
    }

    fun log(kind: LogKind, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), kind, text)
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    fun count(change: (Counters) -> Counters) {
        _counters.update(change)
    }

    fun clearLog() {
        _log.value = emptyList()
        _counters.value = Counters()
    }

    private companion object {
        const val MAX_LOG = 200
    }
}
