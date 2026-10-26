package com.resqmesh.mesh.vectors

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.AckKind
import com.resqmesh.mesh.packet.CancelBody
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.MalformedPacketException
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketType
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.packet.ResponderCodes
import com.resqmesh.mesh.packet.SosBody
import com.resqmesh.mesh.packet.Verdict
import com.resqmesh.mesh.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VectorFile(
    val description: String,
    val formatVersion: Int,
    /** Check every vector as if the local clock reads this time (epoch ms). */
    val verifyAtMillis: Long,
    /** The pinned server key that RESPONDER acks must carry. */
    val serverPublicKey: String,
    val keys: List<VectorKey>,
    val vectors: List<Vector>,
)

@Serializable
data class VectorKey(val name: String, val privateKeyHex: String, val publicKey: String, val nodeId: String)

@Serializable
data class Vector(
    val name: String,
    val description: String,
    val envelopeHex: String,
    /** ACCEPT, REJECT or MALFORMED. */
    val expect: String,
    /** A RejectReason or MalformedReason name; absent for ACCEPT. */
    val reason: String? = null,
    /** The signed body as text, for readers; the bytes inside envelopeHex are what count. */
    val bodyJson: String? = null,
)

/** Fixed keys, times and IDs, so every run produces byte-identical packets. */
object TestVectors {
    const val CREATED_AT = 1_793_000_000_000L
    const val VERIFY_AT = CREATED_AT + 60_000L
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    val sender: NodeKeys = NodeKeys.fromPrivateKey(ByteArray(32) { (0x01 + it).toByte() })
    val gateway: NodeKeys = NodeKeys.fromPrivateKey(ByteArray(32) { (0x41 + it).toByte() })
    val server: NodeKeys = NodeKeys.fromPrivateKey(ByteArray(32) { (0x81 + it).toByte() })

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun id(first: Int): String = Base64Url.encode(ByteArray(16) { (first + it).toByte() })

    fun build(): VectorFile {
        val senderSeal = PacketFactory(sender)
        val sosId = id(0x10)
        val flood = senderSeal.seal(
            SosBody(
                id = sosId, pk = sender.publicKeyB64, ts = CREATED_AT, exp = CREATED_AT + 12 * HOUR,
                cat = Category.FLOOD, lat = 12.9716, lon = 77.59456, acc = 15, fixAge = 40, bat = 42, ppl = 2,
                note = "leg injury", lang = "kn",
            ),
        )
        val floodBytes = PacketCodec.encode(flood)

        val vectors = listOf(
            accept("sos-flood", "Flood SOS with location, straight from the sender", flood),
            accept("sos-relayed-3-hops", "The same SOS after 3 relays: ttl 5, hops 3, same signature", flood.forwarded().forwarded().forwarded()),
            accept(
                "sos-no-location", "General SOS sent before any GPS fix",
                senderSeal.seal(SosBody(id = id(0x20), pk = sender.publicKeyB64, ts = CREATED_AT, exp = CREATED_AT + 12 * HOUR, cat = Category.GENERAL)),
            ),
            accept(
                "sos-kannada-note", "Medical SOS whose note is Kannada script (multi-byte UTF-8)",
                senderSeal.seal(
                    SosBody(
                        id = id(0x28), pk = sender.publicKeyB64, ts = CREATED_AT, exp = CREATED_AT + 12 * HOUR,
                        cat = Category.MEDICAL, lat = 12.9716, lon = 77.59456, note = "ಕಾಲಿಗೆ ಗಾಯ", lang = "kn",
                    ),
                ),
            ),
            accept(
                "cancel-safe", "Sender cancels the flood SOS: now safe",
                senderSeal.seal(CancelBody(id = id(0x30), pk = sender.publicKeyB64, ts = CREATED_AT + 5 * MINUTE, exp = CREATED_AT + 12 * HOUR, ref = sosId, reason = CancelReason.SAFE)),
            ),
            accept(
                "ack-gateway", "A gateway phone uploaded and texted the flood SOS",
                PacketFactory(gateway).seal(
                    AckBody(id = id(0x40), pk = gateway.publicKeyB64, ts = CREATED_AT + 2 * MINUTE, exp = CREATED_AT + 12 * HOUR, ref = sosId, kind = AckKind.GATEWAY, up = true, sms = true),
                ),
            ),
            accept(
                "ack-responder", "Server-signed reply to the flood SOS",
                PacketFactory(server).seal(
                    AckBody(
                        id = id(0x50), pk = server.publicKeyB64, ts = CREATED_AT + 3 * MINUTE, exp = CREATED_AT + 12 * HOUR, ref = sosId,
                        kind = AckKind.RESPONDER, code = ResponderCodes.TEAM_DISPATCHED, msg = "Stay where you are", by = "District Control Room",
                    ),
                ),
            ),
            reject(
                "tampered-location", "sos-flood with the latitude edited by a relay", "BAD_SIGNATURE",
                Packet(flood.type, flood.ttl, flood.hops, flood.body.decodeToString().replace("12.9716", "12.9816").encodeToByteArray(), flood.signature),
            ),
            reject(
                "responder-ack-wrong-key", "A RESPONDER ack signed by a phone instead of the server", "UNTRUSTED_RESPONDER",
                senderSeal.seal(
                    AckBody(id = id(0x58), pk = sender.publicKeyB64, ts = CREATED_AT, exp = CREATED_AT + 12 * HOUR, ref = sosId, kind = AckKind.RESPONDER, code = ResponderCodes.STAY_PUT),
                ),
            ),
            reject("ttl-too-high", "sos-flood claiming 9 hops left", "TTL_TOO_HIGH", Packet(flood.type, 9, 0, flood.body, flood.signature)),
            reject(
                "expired", "An SOS that expired an hour before verifyAtMillis", "EXPIRED",
                senderSeal.seal(SosBody(id = id(0x60), pk = sender.publicKeyB64, ts = CREATED_AT - 13 * HOUR, exp = CREATED_AT - HOUR, cat = Category.FIRE)),
            ),
            reject("type-mismatch", "SOS body under a CANCEL header", "TYPE_MISMATCH", Packet(PacketType.CANCEL, flood.ttl, flood.hops, flood.body, flood.signature)),
            malformed("bad-magic", "sos-flood with the first byte changed", "BAD_MAGIC", floodBytes.copyOf().also { it[0] = 'X'.code.toByte() }),
            malformed("truncated", "sos-flood missing its last byte", "LENGTH_MISMATCH", floodBytes.copyOf(floodBytes.size - 1)),
        )

        return VectorFile(
            description = "ResQMesh v1 packet test vectors. Generated by mesh-core (./gradlew :mesh-core:writeTestVectors); do not edit by hand.",
            formatVersion = 1,
            verifyAtMillis = VERIFY_AT,
            serverPublicKey = server.publicKeyB64,
            keys = listOf(key("sender", sender), key("gateway", gateway), key("server", server)),
            vectors = vectors,
        )
    }

    fun render(): String = json.encodeToString(VectorFile.serializer(), build()) + "\n"

    /** What a receiving node concludes about raw bytes: (expect, reason). */
    fun verdictOf(envelope: ByteArray, validator: PacketValidator): Pair<String, String?> {
        val packet = try {
            PacketCodec.decode(envelope)
        } catch (e: MalformedPacketException) {
            return "MALFORMED" to e.reason.name
        }
        return when (val verdict = validator.validate(packet)) {
            is Verdict.Accepted -> "ACCEPT" to null
            is Verdict.Rejected -> "REJECT" to verdict.reason.name
        }
    }

    private fun key(name: String, keys: NodeKeys) =
        VectorKey(name, keys.exportPrivateKey().toHex(), keys.publicKeyB64, keys.nodeId.short)

    private fun accept(name: String, description: String, packet: Packet) =
        Vector(name, description, PacketCodec.encode(packet).toHex(), "ACCEPT", bodyJson = packet.body.decodeToString())

    private fun reject(name: String, description: String, reason: String, packet: Packet) =
        Vector(name, description, PacketCodec.encode(packet).toHex(), "REJECT", reason, packet.body.decodeToString())

    private fun malformed(name: String, description: String, reason: String, bytes: ByteArray) =
        Vector(name, description, bytes.toHex(), "MALFORMED", reason)
}
