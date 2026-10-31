package com.resqmesh.app

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import com.resqmesh.app.keys.KeyVault
import com.resqmesh.app.gateway.Gateway
import com.resqmesh.app.mesh.ChainSettings
import com.resqmesh.app.mesh.MeshService
import com.resqmesh.app.mesh.MeshNode
import com.resqmesh.app.mesh.MeshState
import com.resqmesh.app.mesh.NearbyTransport
import com.resqmesh.app.settings.AppLanguage
import com.resqmesh.app.settings.AppSettings
import com.resqmesh.app.sos.LocationSource
import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketValidator
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class ResQMeshApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** The app's long-lived objects, built once per process (manual DI keeps the project easy to follow). */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val scope = MainScope()

    val keys: NodeKeys = KeyVault(context).loadOrCreate()
    val state = MeshState()
    val node = MeshNode(
        keys = keys,
        validator = PacketValidator(requireNotNull(Base64Url.decodeOrNull(BuildConfig.SERVER_PUBLIC_KEY))),
        state = state,
    )
    val transport = NearbyTransport(context.applicationContext, keys, node, state)
    val chain = ChainSettings(context)
    val settings = AppSettings(context)
    private val location = LocationSource(appContext)
    val gateway = Gateway(appContext, node, state, settings, keys.nodeId.short, scope)

    fun setServerUrl(url: String) {
        settings.setServerUrl(url)
        gateway.poke()
    }
    private var relayAutoStarted = false

    /** Relaying is on by default (F-20): start it once per launch; the Network tab can still stop it. */
    fun autoStartRelay() {
        if (relayAutoStarted) return
        relayAutoStarted = true
        if (!state.running.value) MeshService.start(appContext)
    }

    /** The countdown opened: give GPS those seconds to find a fix. */
    fun prepareSos() = location.start()

    fun abandonSos() = location.stop()

    /**
     * Sends the SOS once a location is in hand (at most 10 s after [prepareSos]), then calls
     * [onSent]. Starts relaying if it was off, so the SOS leaves as soon as a phone is near.
     */
    fun sendSos(category: Category, people: Int, onSent: () -> Unit) {
        scope.launch {
            val fix = location.fix()
            if (!state.running.value) MeshService.start(appContext)
            node.sendSos(category, fix, batteryPercent(), people, (settings.language.value ?: AppLanguage.ENGLISH).code)
            onSent()
        }
    }

    private fun batteryPercent(): Int? =
        appContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    fun applyChainMode() {
        transport.setAllowOnly(chain.allowOnly())
    }

    fun setChainMode(on: Boolean) {
        chain.setEnabled(on)
        applyChainMode()
    }

    fun toggleChainPeer(peer: NodeId) {
        chain.toggle(peer)
        applyChainMode()
    }
}

val Context.graph: AppGraph get() = (applicationContext as ResQMeshApp).graph
