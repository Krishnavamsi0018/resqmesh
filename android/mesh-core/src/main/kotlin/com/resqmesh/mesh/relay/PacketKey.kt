package com.resqmesh.mesh.relay

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.Protocol
import com.resqmesh.mesh.crypto.Ed25519
import com.resqmesh.mesh.hexToBytes
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.toHex
import java.security.MessageDigest

/**
 * Identity of a packet for duplicate suppression: the first 16 bytes of SHA-256(sender key ‖ packet ID).
 *
 * Keying on the ID alone would let anyone squat on a real SOS: copy its ID into their own signed
 * packet, get it in first, and every phone would then drop the genuine SOS as a "duplicate".
 */
@JvmInline
value class PacketKey(val hex: String) {
    fun toBytes(): ByteArray = hex.hexToBytes()

    override fun toString(): String = hex.take(8)

    companion object {
        const val SIZE = 16

        fun of(publicKey: ByteArray, id: ByteArray): PacketKey {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(publicKey)
            digest.update(id)
            return PacketKey(digest.digest().copyOf(SIZE).toHex())
        }

        /** Null when the body's key or ID is malformed (the validator rejects those). */
        fun of(body: PacketBody): PacketKey? {
            val publicKey = Base64Url.decodeOrNull(body.pk)?.takeIf { it.size == Ed25519.PUBLIC_KEY_SIZE } ?: return null
            val id = Base64Url.decodeOrNull(body.id)?.takeIf { it.size == Protocol.ID_SIZE } ?: return null
            return of(publicKey, id)
        }

        fun fromBytes(bytes: ByteArray): PacketKey {
            require(bytes.size == SIZE) { "Packet key must be $SIZE bytes" }
            return PacketKey(bytes.toHex())
        }
    }
}
