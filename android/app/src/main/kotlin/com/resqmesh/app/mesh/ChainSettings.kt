package com.resqmesh.app.mesh

import android.content.Context
import androidx.core.content.edit
import com.resqmesh.mesh.crypto.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chain test mode (F-61), kept across restarts: when on, this phone links only to the phones
 * ticked in the list, so phones on one table can be made into a line A–B–C–D.
 */
class ChainSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _ticked = MutableStateFlow(prefs.getStringSet(KEY_TICKED, emptySet()).orEmpty().map(::NodeId).toSet())
    val ticked: StateFlow<Set<NodeId>> = _ticked.asStateFlow()

    /** What the link policy should allow: null means anyone. */
    fun allowOnly(): Set<NodeId>? = if (_enabled.value) _ticked.value else null

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit { putBoolean(KEY_ENABLED, on) }
    }

    fun toggle(peer: NodeId) {
        val now = _ticked.value.let { if (peer in it) it - peer else it + peer }
        _ticked.value = now
        prefs.edit { putStringSet(KEY_TICKED, now.map { it.hex }.toSet()) }
    }

    private companion object {
        const val PREFS = "resqmesh_settings"
        const val KEY_ENABLED = "chain_mode"
        const val KEY_TICKED = "chain_ticked"
    }
}
