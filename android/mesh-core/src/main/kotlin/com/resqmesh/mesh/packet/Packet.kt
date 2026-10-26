package com.resqmesh.mesh.packet

import com.resqmesh.mesh.Protocol

/**
 * One packet as it travels between phones: a header that relays may change ([ttl], [hops]),
 * the body bytes the sender signed, and the Ed25519 signature over exactly those bytes.
 *
 * The body is kept as raw bytes, never re-serialised, so the signature always checks
 * against what the sender produced. Parse it with [BodyJson.decode].
 * Treat [body] and [signature] as read-only.
 */
class Packet(
    val type: PacketType,
    val ttl: Int,
    val hops: Int,
    val body: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(ttl in 0..255) { "ttl must fit in one byte: $ttl" }
        require(hops in 0..255) { "hops must fit in one byte: $hops" }
        require(body.size in 1..Protocol.MAX_BODY_SIZE) { "body must be 1..${Protocol.MAX_BODY_SIZE} bytes: ${body.size}" }
        require(signature.size == Protocol.SIGNATURE_SIZE) { "signature must be ${Protocol.SIGNATURE_SIZE} bytes: ${signature.size}" }
    }

    /** The copy a relay sends on: one hop fewer left, one more taken. Body and signature are unchanged. */
    fun forwarded(): Packet {
        check(ttl > 1) { "A packet with ttl $ttl must not be forwarded" }
        return Packet(type, ttl - 1, hops + 1, body, signature)
    }

    override fun equals(other: Any?): Boolean =
        other is Packet &&
            type == other.type &&
            ttl == other.ttl &&
            hops == other.hops &&
            body.contentEquals(other.body) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + ttl
        result = 31 * result + hops
        result = 31 * result + body.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    override fun toString(): String = "Packet(type=$type, ttl=$ttl, hops=$hops, body=${body.size} B)"
}
