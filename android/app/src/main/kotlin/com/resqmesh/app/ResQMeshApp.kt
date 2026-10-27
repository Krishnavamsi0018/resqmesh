package com.resqmesh.app

import android.app.Application
import android.content.Context
import com.resqmesh.app.keys.KeyVault
import com.resqmesh.app.mesh.MeshNode
import com.resqmesh.app.mesh.MeshState
import com.resqmesh.app.mesh.NearbyTransport
import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.PacketValidator

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
    val keys: NodeKeys = KeyVault(context).loadOrCreate()
    val state = MeshState()
    val node = MeshNode(
        keys = keys,
        validator = PacketValidator(requireNotNull(Base64Url.decodeOrNull(BuildConfig.SERVER_PUBLIC_KEY))),
        state = state,
    )
    val transport = NearbyTransport(context.applicationContext, keys, node, state)
}

val Context.graph: AppGraph get() = (applicationContext as ResQMeshApp).graph
