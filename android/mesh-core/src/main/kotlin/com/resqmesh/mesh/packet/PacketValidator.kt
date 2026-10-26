package com.resqmesh.mesh.packet

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.Protocol
import com.resqmesh.mesh.crypto.Ed25519
import java.nio.charset.CharacterCodingException

sealed interface Verdict {
    data class Accepted(val body: PacketBody) : Verdict

    data class Rejected(val reason: RejectReason, val detail: String? = null) : Verdict
}

enum class RejectReason {
    BAD_BODY,
    TYPE_MISMATCH,
    BAD_ID,
    BAD_KEY,
    TTL_EXHAUSTED,
    TTL_TOO_HIGH,
    BAD_LIFETIME,
    EXPIRED,
    BAD_FIELDS,
    UNTRUSTED_RESPONDER,
    BAD_SIGNATURE,
}

/**
 * Decides whether a decoded packet may be stored and relayed: steps 3 and 4 of the relay
 * algorithm (TRD 2.5). Cheap checks run first; the Ed25519 check runs last.
 *
 * Checks that need state (the seen-ID cache, per-sender rate limits, whether a cancel's key matches
 * the SOS it names) belong to the router, which arrives with multi-hop relaying in weeks 4–5.
 */
class PacketValidator(
    private val serverPublicKey: ByteArray,
    private val clock: Clock = Clock.SYSTEM,
) {
    init {
        require(serverPublicKey.size == Ed25519.PUBLIC_KEY_SIZE) { "Server public key must be ${Ed25519.PUBLIC_KEY_SIZE} bytes" }
    }

    fun validate(packet: Packet): Verdict {
        val body = try {
            BodyJson.decode(packet.body)
        } catch (e: IllegalArgumentException) {
            return Verdict.Rejected(RejectReason.BAD_BODY, e.message)
        } catch (e: CharacterCodingException) {
            return Verdict.Rejected(RejectReason.BAD_BODY, "Body is not UTF-8")
        }

        if (body.type != packet.type) {
            return Verdict.Rejected(RejectReason.TYPE_MISMATCH, "Header says ${packet.type}, body says ${body.type}")
        }
        if (!isPacketId(body.id)) return Verdict.Rejected(RejectReason.BAD_ID)
        val publicKey = Base64Url.decodeOrNull(body.pk)
        if (publicKey == null || publicKey.size != Ed25519.PUBLIC_KEY_SIZE) return Verdict.Rejected(RejectReason.BAD_KEY)

        if (packet.ttl < 1) return Verdict.Rejected(RejectReason.TTL_EXHAUSTED)
        if (packet.ttl > Protocol.MAX_TTL) return Verdict.Rejected(RejectReason.TTL_TOO_HIGH, "ttl ${packet.ttl}")

        val lifetime = body.exp - body.ts
        if (lifetime <= 0 || lifetime > Protocol.MAX_LIFETIME_MS) {
            return Verdict.Rejected(RejectReason.BAD_LIFETIME, "exp - ts = $lifetime ms")
        }
        // No check on ts being in the future: a sender with a fast clock still needs help.
        if (clock.nowMillis() > body.exp + Protocol.CLOCK_SKEW_MS) return Verdict.Rejected(RejectReason.EXPIRED)

        fieldProblem(body)?.let { return Verdict.Rejected(RejectReason.BAD_FIELDS, it) }

        if (body is AckBody && body.kind == AckKind.RESPONDER && !publicKey.contentEquals(serverPublicKey)) {
            return Verdict.Rejected(RejectReason.UNTRUSTED_RESPONDER)
        }
        if (!Ed25519.verify(publicKey, packet.body, packet.signature)) return Verdict.Rejected(RejectReason.BAD_SIGNATURE)

        return Verdict.Accepted(body)
    }

    private fun fieldProblem(body: PacketBody): String? = when (body) {
        is SosBody -> sosProblem(body)
        is CancelBody -> if (!isPacketId(body.ref)) "ref is not a packet ID" else null
        is AckBody -> ackProblem(body)
    }

    private fun sosProblem(body: SosBody): String? = when {
        (body.lat == null) != (body.lon == null) -> "lat and lon must both be present or both absent"
        body.lat != null && body.lat !in -90.0..90.0 -> "lat out of range"
        body.lon != null && body.lon !in -180.0..180.0 -> "lon out of range"
        body.acc != null && body.acc < 0 -> "acc is negative"
        body.fixAge != null && body.fixAge < 0 -> "fixAge is negative"
        body.bat != null && body.bat !in 0..100 -> "bat out of range"
        body.ppl !in 1..Protocol.MAX_PEOPLE -> "ppl out of range"
        body.note != null && body.note.length > Protocol.MAX_NOTE_LENGTH -> "note too long"
        body.lang.length !in 2..8 -> "lang must be 2-8 characters"
        else -> null
    }

    private fun ackProblem(body: AckBody): String? = when {
        !isPacketId(body.ref) -> "ref is not a packet ID"
        body.kind == AckKind.GATEWAY && (body.up == null || body.sms == null) -> "gateway ack needs up and sms"
        body.kind == AckKind.RESPONDER && body.code.isNullOrBlank() -> "responder ack needs a code"
        body.msg != null && body.msg.length > Protocol.MAX_ACK_MESSAGE_LENGTH -> "msg too long"
        body.by != null && body.by.length > Protocol.MAX_UNIT_NAME_LENGTH -> "by too long"
        else -> null
    }

    private fun isPacketId(text: String): Boolean = Base64Url.decodeOrNull(text)?.size == Protocol.ID_SIZE
}
