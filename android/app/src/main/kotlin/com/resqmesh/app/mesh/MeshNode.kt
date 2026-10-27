package com.resqmesh.app.mesh

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.CancelBody
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.MalformedPacketException
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.packet.SosBody
import com.resqmesh.mesh.packet.Verdict
import java.util.Locale

/** Sends raw packet bytes to every linked phone; returns how many it went to. */
fun interface Outbound {
    fun broadcast(bytes: ByteArray): Int
}

/**
 * This phone's end of the mesh for week 2: sends test SOS packets and checks what arrives.
 * Relaying onward (TTL, seen-ID cache, summaries) replaces the simple [seen] set in weeks 4–5.
 * Called on the main thread only.
 */
class MeshNode(
    keys: NodeKeys,
    private val validator: PacketValidator,
    private val state: MeshState,
    private val clock: Clock = Clock.SYSTEM,
) {
    var outbound: Outbound? = null

    private val factory = PacketFactory(keys, clock)
    private val seen = LinkedHashSet<String>()

    fun sendTestSos(category: Category) {
        val packet = factory.sos(category, location = null, note = "week-2 link test", test = true)
        val body = BodyJson.decode(packet.body)
        remember(body.id)
        val sentTo = outbound?.broadcast(PacketCodec.encode(packet)) ?: 0
        state.count { it.copy(sent = it.sent + 1) }
        state.log(LogKind.SENT, "SOS $category ${shortId(body.id)} to $sentTo phone(s)")
    }

    fun onBytes(from: String, bytes: ByteArray) {
        val packet = try {
            PacketCodec.decode(bytes)
        } catch (e: MalformedPacketException) {
            state.count { it.copy(rejected = it.rejected + 1) }
            state.log(LogKind.REJECTED, "Malformed (${e.reason}) from $from")
            return
        }
        when (val verdict = validator.validate(packet)) {
            is Verdict.Rejected -> {
                state.count { it.copy(rejected = it.rejected + 1) }
                state.log(LogKind.REJECTED, "Rejected (${verdict.reason}) from $from")
            }
            is Verdict.Accepted -> {
                if (!remember(verdict.body.id)) {
                    state.count { it.copy(duplicates = it.duplicates + 1) }
                    state.log(LogKind.DUPLICATE, "Duplicate ${shortId(verdict.body.id)} from $from")
                } else {
                    state.count { it.copy(accepted = it.accepted + 1) }
                    state.log(LogKind.ACCEPTED, describe(verdict.body, packet, from))
                }
            }
        }
    }

    /** True the first time an ID is seen. */
    private fun remember(id: String): Boolean {
        val added = seen.add(id)
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return added
    }

    private fun describe(body: PacketBody, packet: Packet, from: String): String {
        val sender = Base64Url.decodeOrNull(body.pk)?.let { NodeId.of(it).short } ?: "?"
        val ageSeconds = (clock.nowMillis() - body.ts) / 1000.0
        val what = when (body) {
            is SosBody -> "SOS ${body.cat}"
            is CancelBody -> "CANCEL of ${shortId(body.ref)}"
            is AckBody -> "ACK ${body.kind} for ${shortId(body.ref)}"
        }
        val age = "%.1f".format(Locale.US, ageSeconds)
        return "$what ${shortId(body.id)} from $sender via $from · hops ${packet.hops} · age $age s"
    }

    private fun shortId(id: String) = id.take(4)

    private companion object {
        const val MAX_SEEN = 1000
    }
}
