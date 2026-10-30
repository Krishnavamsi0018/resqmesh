package com.resqmesh.app.mesh

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.CancelBody
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.Location
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.packet.SosBody
import com.resqmesh.mesh.relay.DropKind
import com.resqmesh.mesh.relay.Links
import com.resqmesh.mesh.relay.Router
import com.resqmesh.mesh.relay.RouterEvents
import com.resqmesh.mesh.sos.SosTracker
import java.util.Locale

/**
 * This phone's end of the mesh: sends the user's SOS (and test ones), runs every incoming byte
 * through the multi-hop [Router] (TTL, duplicate suppression, store-carry-forward), follows the
 * user's own SOS with a [SosTracker], and logs what happens. Called on the main thread only.
 */
class MeshNode(
    keys: NodeKeys,
    validator: PacketValidator,
    private val state: MeshState,
    private val clock: Clock = Clock.SYSTEM,
) : RouterEvents {
    val id: NodeId = keys.nodeId

    /** The live links; set by the transport while relaying runs. */
    var links: Links? = null

    private val factory = PacketFactory(keys, clock)
    private val router = Router(
        validator = validator,
        links = object : Links {
            override fun linked(): Set<NodeId> = links?.linked() ?: emptySet()

            override fun send(to: NodeId, bytes: ByteArray) {
                links?.send(to, bytes)
            }
        },
        events = this,
        clock = clock,
    )

    private val tracker = SosTracker(clock)

    /**
     * Sends the user's SOS (F-10 to F-13). It goes to every linked phone now, and the Router keeps
     * offering it to each phone that links later until it expires (F-15).
     */
    fun sendSos(category: Category, location: Location?, batteryPercent: Int?, people: Int, lang: String) {
        val packet = factory.sos(category, location, batteryPercent, people, lang = lang)
        // Track first, so the hand-overs inside originate() already count.
        tracker.started(BodyJson.decode(packet.body) as SosBody)
        val sentTo = router.originate(packet)
        state.count { it.copy(sent = it.sent + 1) }
        state.log(LogKind.SENT, "Your SOS $category ${shortId(tracker.current!!.id)} to $sentTo phone(s)")
        publishSos()
    }

    /** "I'm safe now" or "sent by mistake" (F-16): a signed cancel follows the SOS through the mesh. */
    fun cancelSos(reason: CancelReason) {
        val sos = tracker.current?.takeIf { it.isOpen } ?: return
        router.originate(factory.cancel(sos.id, reason))
        tracker.cancelled(reason)
        state.log(LogKind.SENT, "CANCEL ($reason) of your SOS ${shortId(sos.id)}")
        publishSos()
    }

    /** Closes an expired SOS; the status screen calls it as time passes. */
    fun refreshSos() {
        if (tracker.expireIfDue()) publishSos()
    }

    /** Clears a closed SOS from the screens. */
    fun dismissSos() {
        tracker.dismiss()
        publishSos()
    }

    private fun publishSos() {
        state.setMySos(tracker.current)
        state.setHeld(router.heldCount)
    }

    fun sendTestSos(category: Category) {
        val packet = factory.sos(category, location = null, note = "mesh test", test = true)
        val sentTo = router.originate(packet)
        state.count { it.copy(sent = it.sent + 1) }
        state.log(LogKind.SENT, "SOS $category ${shortId(BodyJson.decode(packet.body).id)} to $sentTo phone(s)")
        state.setHeld(router.heldCount)
    }

    fun onBytes(from: NodeId, bytes: ByteArray) {
        router.onReceive(from, bytes)
        state.setHeld(router.heldCount)
    }

    fun onLinked(peer: NodeId) {
        router.onLinked(peer)
    }

    override fun onDelivered(packet: Packet, body: PacketBody, from: NodeId) {
        state.count { it.copy(accepted = it.accepted + 1) }
        state.log(LogKind.ACCEPTED, describe(body, packet, from))
        if (body is AckBody && tracker.onAck(body)) publishSos()
    }

    override fun onHandedOver(body: PacketBody, to: NodeId) {
        if (tracker.onHandedOver(body, to)) publishSos()
    }

    override fun onDuplicate(body: PacketBody, from: NodeId) {
        state.count { it.copy(duplicates = it.duplicates + 1) }
        state.log(LogKind.DUPLICATE, "Duplicate ${shortId(body.id)} from ${from.short}")
    }

    override fun onDropped(kind: DropKind, detail: String, from: NodeId) {
        state.count { it.copy(rejected = it.rejected + 1) }
        state.log(LogKind.REJECTED, "Dropped ($kind: $detail) from ${from.short}")
    }

    override fun onForwarded(body: PacketBody, to: Collection<NodeId>) {
        state.count { it.copy(forwarded = it.forwarded + 1) }
        state.log(LogKind.FORWARDED, "Forwarded ${shortId(body.id)} to ${to.joinToString { it.short }}")
    }

    override fun onSynced(peer: NodeId, sent: Int) {
        if (sent > 0) state.log(LogKind.SYNCED, "Caught ${peer.short} up: sent $sent packet(s) it missed")
    }

    private fun describe(body: PacketBody, packet: Packet, from: NodeId): String {
        val sender = Base64Url.decodeOrNull(body.pk)?.let { NodeId.of(it).short } ?: "?"
        val age = "%.1f".format(Locale.US, (clock.nowMillis() - body.ts) / 1000.0)
        val what = when (body) {
            is SosBody -> "SOS ${body.cat}"
            is CancelBody -> "CANCEL of ${shortId(body.ref)}"
            is AckBody -> "ACK ${body.kind} for ${shortId(body.ref)}"
        }
        return "$what ${shortId(body.id)} from $sender via ${from.short} · hops ${packet.hops} · age $age s"
    }

    private fun shortId(id: String) = id.take(4)
}
