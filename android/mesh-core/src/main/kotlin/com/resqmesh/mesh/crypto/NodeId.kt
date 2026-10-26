package com.resqmesh.mesh.crypto

import com.resqmesh.mesh.toHex
import java.security.MessageDigest

/**
 * Stable ID of a phone: the first 8 bytes of SHA-256(public key), as 16 uppercase hex digits.
 * [short] is what people see, e.g. "7F3A-91C2" (F-03).
 */
@JvmInline
value class NodeId(val hex: String) {
    val short: String get() = "${hex.substring(0, 4)}-${hex.substring(4, 8)}"

    override fun toString(): String = short

    companion object {
        fun of(publicKey: ByteArray): NodeId {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
            return NodeId(digest.copyOf(8).toHex())
        }
    }
}
