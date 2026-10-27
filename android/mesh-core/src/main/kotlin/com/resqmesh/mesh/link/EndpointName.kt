package com.resqmesh.mesh.link

import com.resqmesh.mesh.crypto.NodeId

/**
 * What a phone advertises as its Nearby endpoint name (TRD 2.6): a protocol tag, its node ID and
 * two capability flags, e.g. `RQ1:65B60673A1B2C3D4:GS`. Neighbours read it before connecting, so they
 * can pick who requests the link and spot phones that can escalate.
 */
data class EndpointName(
    val nodeId: NodeId,
    /** This phone has validated internet and uploads to the backend. */
    val gateway: Boolean = false,
    /** This phone has cellular service and can send the SMS escalation. */
    val smsCapable: Boolean = false,
) {
    fun encode(): String {
        val flags = buildString {
            if (gateway) append('G')
            if (smsCapable) append('S')
        }.ifEmpty { "-" }
        return "$PREFIX${nodeId.hex}:$flags"
    }

    companion object {
        const val PREFIX = "RQ1:"
        private val NODE_HEX = Regex("^[0-9A-F]{16}$")
        private val FLAGS = Regex("^(-|G?S?)$")

        /** Returns null for anything that isn't a ResQMesh v1 name, so foreign apps are ignored. */
        fun decode(text: String): EndpointName? {
            if (!text.startsWith(PREFIX)) return null
            val parts = text.removePrefix(PREFIX).split(':')
            if (parts.size != 2) return null
            val (hex, flags) = parts
            if (!NODE_HEX.matches(hex) || flags.isEmpty() || !FLAGS.matches(flags)) return null
            return EndpointName(NodeId(hex), gateway = 'G' in flags, smsCapable = 'S' in flags)
        }
    }
}
