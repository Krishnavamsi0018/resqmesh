package com.resqmesh.app.mesh

import com.resqmesh.mesh.link.EndpointName
import com.resqmesh.mesh.sos.MySos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Peer(val endpointId: String, val name: EndpointName, val linked: Boolean, val connecting: Boolean = false)

enum class LogKind { SENT, ACCEPTED, FORWARDED, SYNCED, DUPLICATE, REJECTED, LINK, ERROR }

data class LogEntry(val atMillis: Long, val kind: LogKind, val text: String)

/** What the gateway is doing (F-40): whether this phone has internet and a server, and how uploads go. */
data class GatewayState(
    val online: Boolean = false,
    val serverSet: Boolean = false,
    val uploaded: Int = 0,
    val waiting: Int = 0,
    val lastContactAt: Long? = null,
    val lastError: String? = null,
) {
    /** Online with a server to talk to: this phone is acting as a gateway. */
    val active: Boolean get() = online && serverSet
}

/** What SMS escalation is doing (F-41): whether this phone can text the rescue team, and how it goes. */
data class SmsState(
    val permission: Boolean = false,
    val enabled: Boolean = true,
    /** The rescue team number is set and on the build's allow-list. */
    val numberAllowed: Boolean = false,
    /** The phone has cellular service (data not needed). */
    val service: Boolean = false,
    val sent: Int = 0,
    val waiting: Int = 0,
    val lastSentAt: Long? = null,
    val lastError: String? = null,
) {
    val ready: Boolean get() = permission && enabled && numberAllowed && service
}

data class Counters(
    val sent: Int = 0,
    val accepted: Int = 0,
    val forwarded: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
)

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

    private val _held = MutableStateFlow(0)
    /** Packets kept for phones that link later (store-carry-forward). */
    val held: StateFlow<Int> = _held.asStateFlow()

    private val _mySos = MutableStateFlow<MySos?>(null)
    /** This phone's latest SOS and how far it has got (F-14); null when there is none to show. */
    val mySos: StateFlow<MySos?> = _mySos.asStateFlow()

    private val _gateway = MutableStateFlow(GatewayState())
    val gateway: StateFlow<GatewayState> = _gateway.asStateFlow()

    fun updateGateway(change: (GatewayState) -> GatewayState) {
        _gateway.update(change)
    }

    private val _sms = MutableStateFlow(SmsState())
    val sms: StateFlow<SmsState> = _sms.asStateFlow()

    fun updateSms(change: (SmsState) -> SmsState) {
        _sms.update(change)
    }

    fun setMySos(sos: MySos?) {
        _mySos.value = sos
    }

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setHeld(count: Int) {
        _held.value = count
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
