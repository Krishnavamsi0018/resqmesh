package com.resqmesh.mesh.packet

import com.resqmesh.mesh.Protocol.HEADER_SIZE
import com.resqmesh.mesh.Protocol.MAGIC_0
import com.resqmesh.mesh.Protocol.MAGIC_1
import com.resqmesh.mesh.Protocol.MAX_BODY_SIZE
import com.resqmesh.mesh.Protocol.MAX_PACKET_SIZE
import com.resqmesh.mesh.Protocol.SIGNATURE_SIZE
import com.resqmesh.mesh.Protocol.VERSION

/**
 * Binary envelope (TRD 2.4):
 *
 * ```
 * offset  size  field
 * 0       2     magic "RQ"
 * 2       1     version
 * 3       1     type
 * 4       1     ttl
 * 5       1     hops
 * 6       2     bodyLen (big-endian)
 * 8       n     body (UTF-8 JSON, signed)
 * 8+n     64    Ed25519 signature over body
 * ```
 */
object PacketCodec {
    fun encode(packet: Packet): ByteArray {
        val bodyLen = packet.body.size
        val out = ByteArray(HEADER_SIZE + bodyLen + SIGNATURE_SIZE)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = VERSION.toByte()
        out[3] = packet.type.code.toByte()
        out[4] = packet.ttl.toByte()
        out[5] = packet.hops.toByte()
        out[6] = (bodyLen ushr 8).toByte()
        out[7] = bodyLen.toByte()
        packet.body.copyInto(out, HEADER_SIZE)
        packet.signature.copyInto(out, HEADER_SIZE + bodyLen)
        return out
    }

    /** Parses the envelope only; the body's JSON and signature are checked by [PacketValidator]. */
    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < HEADER_SIZE + 1 + SIGNATURE_SIZE) throw MalformedPacketException(MalformedReason.TOO_SHORT)
        if (bytes.size > MAX_PACKET_SIZE) throw MalformedPacketException(MalformedReason.TOO_LONG)
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) throw MalformedPacketException(MalformedReason.BAD_MAGIC)
        if (bytes[2].toInt() and 0xFF != VERSION) throw MalformedPacketException(MalformedReason.UNSUPPORTED_VERSION)
        val type = PacketType.fromCode(bytes[3].toInt() and 0xFF)
            ?: throw MalformedPacketException(MalformedReason.UNKNOWN_TYPE)
        val bodyLen = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        if (bodyLen == 0 || bodyLen > MAX_BODY_SIZE) throw MalformedPacketException(MalformedReason.BAD_BODY_LENGTH)
        if (bytes.size != HEADER_SIZE + bodyLen + SIGNATURE_SIZE) throw MalformedPacketException(MalformedReason.LENGTH_MISMATCH)

        return Packet(
            type = type,
            ttl = bytes[4].toInt() and 0xFF,
            hops = bytes[5].toInt() and 0xFF,
            body = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + bodyLen),
            signature = bytes.copyOfRange(HEADER_SIZE + bodyLen, bytes.size),
        )
    }
}

enum class MalformedReason {
    TOO_SHORT,
    TOO_LONG,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    UNKNOWN_TYPE,
    BAD_BODY_LENGTH,
    LENGTH_MISMATCH,
}

class MalformedPacketException(val reason: MalformedReason) : Exception("Malformed packet: $reason")
