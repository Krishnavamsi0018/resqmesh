package com.resqmesh.mesh.relay

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.Protocol
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.CancelBody
import com.resqmesh.mesh.packet.MalformedPacketException
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.packet.SosBody
import com.resqmesh.mesh.packet.Verdict
import com.resqmesh.mesh.toHex
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

/** The phones this one is linked to right now, and how to reach each. */
interface Links {
    fun linked(): Set<NodeId>

    fun send(to: NodeId, bytes: ByteArray)
}

enum class DropKind { MALFORMED, INVALID, RATE_LIMITED, CANCEL_KEY_MISMATCH }

/** What the router did, for the app's log and the experiment counters. All optional. */
interface RouterEvents {
    fun onDelivered(packet: Packet, body: PacketBody, from: NodeId) {}

    fun onDuplicate(body: PacketBody, from: NodeId) {}

    fun onDropped(kind: DropKind, detail: String, from: NodeId) {}

    fun onForwarded(body: PacketBody, to: Collection<NodeId>) {}

    /** Answered a neighbour's request after linking: [sent] packets it was missing. */
    fun onSynced(peer: NodeId, sent: Int) {}

    /** [body] went to [to], live or as a catch-up. The sender's tracker counts these (F-14). */
    fun onHandedOver(body: PacketBody, to: NodeId) {}
}

data class RouterConfig(
    /** Remember a packet's key this long after it expires, so late copies still count as duplicates. */
    val seenRetentionMs: Long = 60 * 60 * 1000L,
    val maxHeld: Int = 1000,
    val maxSeen: Int = 20_000,
    val maxRejected: Int = 2_000,
    /** F-32: SOS relayed per sender key per window. */
    val sosPerSender: Int = 5,
    /**
     * Test SOS have their own, larger budget, so a field experiment (50 SOS at 10 s) gets through
     * while real SOS keep the tight limit. Test SOS never trigger SMS and show as TEST.
     */
    val testSosPerSender: Int = 120,
    val sosWindowMs: Long = 10 * 60 * 1000L,
    val pruneEveryMs: Long = 5_000,
    /**
     * Offer held packets to phones that link later (F-15). Off only for the plain-flooding baseline
     * in the experiments, where a packet reaches just the phones linked while it spreads.
     */
    val storeCarryForward: Boolean = true,
)

/**
 * Multi-hop relay (TRD 2.5, F-21 to F-24). Each valid packet is delivered here once, then
 * forwarded with one hop fewer to every linked phone that doesn't have it yet. Packets still
 * able to travel are held until they expire, and handed to phones that link later
 * (store-carry-forward). Not thread-safe: the app calls it from the main thread.
 */
class Router(
    private val validator: PacketValidator,
    private val links: Links,
    private val events: RouterEvents,
    private val clock: Clock = Clock.SYSTEM,
    private val config: RouterConfig = RouterConfig(),
) {
    /** A packet this phone can still pass on, as it will send it (TTL already lowered). */
    private class Held(val body: PacketBody, val outgoing: Packet, val heldAt: Long) {
        val peersWithIt = HashSet<NodeId>()
    }

    private val held = LinkedHashMap<PacketKey, Held>()
    private val seen = LinkedHashMap<PacketKey, Long>()
    private val rejected = LinkedHashSet<String>()
    private val recentSos = HashMap<String, ArrayDeque<Long>>()
    private var lastPruneAt = Long.MIN_VALUE

    val heldCount: Int get() = held.size

    /** Sends a packet this phone made. Returns how many neighbours it went to now; later ones get it on linking. */
    fun originate(packet: Packet): Int {
        prune()
        val body = BodyJson.decode(packet.body)
        val key = requireNotNull(PacketKey.of(body)) { "Own packet has a malformed key or ID" }
        remember(key, body)
        val entry = Held(body, packet, clock.nowMillis())
        hold(key, entry)
        return sendToNeighbours(entry).size
    }

    fun onReceive(from: NodeId, bytes: ByteArray) {
        prune()
        if (ControlFrame.isControl(bytes)) {
            onControl(from, bytes)
            return
        }
        val packet = try {
            PacketCodec.decode(bytes)
        } catch (e: MalformedPacketException) {
            events.onDropped(DropKind.MALFORMED, e.reason.name, from)
            return
        }

        // Cheap duplicate check before the costly signature check (TRD 2.5 step 2).
        val parsed = parseOrNull(packet)
        val parsedKey = parsed?.let(PacketKey::of)
        if (parsed != null && parsedKey != null && parsedKey in seen) {
            held[parsedKey]?.peersWithIt?.add(from)
            events.onDuplicate(parsed, from)
            return
        }

        // A rejected packet is remembered by its exact bytes, not its ID, so a forged copy can't block the real one.
        val fingerprint = sha256Hex(bytes)
        if (fingerprint in rejected) {
            events.onDropped(DropKind.INVALID, "already rejected", from)
            return
        }
        val body = when (val verdict = validator.validate(packet)) {
            is Verdict.Rejected -> {
                rejected.add(fingerprint)
                trim(rejected, config.maxRejected)
                events.onDropped(DropKind.INVALID, verdict.reason.name, from)
                return
            }
            is Verdict.Accepted -> verdict.body
        }
        val key = requireNotNull(PacketKey.of(body))

        if (body is SosBody && !withinRateLimit(body)) {
            events.onDropped(DropKind.RATE_LIMITED, "sender ${body.pk.take(8)}", from)
            return
        }
        if (body is CancelBody && !cancelMatchesSos(body)) {
            events.onDropped(DropKind.CANCEL_KEY_MISMATCH, "cancel of ${body.ref.take(4)}", from)
            return
        }

        remember(key, body)
        events.onDelivered(packet, body, from)

        if (packet.ttl > 1) {
            val entry = Held(body, packet.forwarded(), clock.nowMillis())
            entry.peersWithIt += from
            hold(key, entry)
            val to = sendToNeighbours(entry)
            if (to.isNotEmpty()) events.onForwarded(body, to)
        }
    }

    /** A link came up: tell the neighbour what we hold so it can ask for what it lacks. */
    fun onLinked(peer: NodeId) {
        prune()
        if (!config.storeCarryForward) return
        val keys = held.entries
            .filter { peer !in it.value.peersWithIt }
            .sortedByDescending { it.value.heldAt }
            .take(ControlFrame.MAX_KEYS)
            .map { it.key }
        links.send(peer, ControlFrame.encode(ControlFrame.Summary(keys)))
    }

    private fun onControl(from: NodeId, bytes: ByteArray) {
        when (val frame = ControlFrame.decode(bytes)) {
            null -> events.onDropped(DropKind.MALFORMED, "control frame", from)
            is ControlFrame.Summary -> {
                frame.keys.forEach { held[it]?.peersWithIt?.add(from) }
                val missing = frame.keys.filter { it !in seen }
                if (missing.isNotEmpty()) links.send(from, ControlFrame.encode(ControlFrame.Request(missing)))
            }
            is ControlFrame.Request -> {
                // F-23: SOS and cancels first, then ACKs; newest first within each.
                val wanted = frame.keys
                    .mapNotNull { held[it] }
                    .sortedWith(compareBy<Held> { it.outgoing.type.priority }.thenByDescending { it.heldAt })
                for (entry in wanted) {
                    links.send(from, PacketCodec.encode(entry.outgoing))
                    entry.peersWithIt += from
                    events.onHandedOver(entry.body, from)
                }
                events.onSynced(from, wanted.size)
            }
        }
    }

    private fun sendToNeighbours(entry: Held): List<NodeId> {
        val targets = links.linked().filter { it !in entry.peersWithIt }
        if (targets.isEmpty()) return targets
        val bytes = PacketCodec.encode(entry.outgoing)
        for (peer in targets) {
            links.send(peer, bytes)
            entry.peersWithIt += peer
            events.onHandedOver(entry.body, peer)
        }
        return targets
    }

    private fun hold(key: PacketKey, entry: Held) {
        held[key] = entry
        while (held.size > config.maxHeld) held.remove(held.keys.first())
    }

    private fun remember(key: PacketKey, body: PacketBody) {
        seen[key] = body.exp + config.seenRetentionMs
        while (seen.size > config.maxSeen) seen.remove(seen.keys.first())
    }

    private fun withinRateLimit(sos: SosBody): Boolean {
        val now = clock.nowMillis()
        val times = recentSos.getOrPut(if (sos.test) "test:${sos.pk}" else sos.pk) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= config.sosWindowMs) times.removeFirst()
        if (times.size >= if (sos.test) config.testSosPerSender else config.sosPerSender) return false
        times.addLast(now)
        return true
    }

    /** A cancel must come from the key that sent the SOS. Unknown SOS (never seen here) can't be checked, so it passes. */
    private fun cancelMatchesSos(cancel: CancelBody): Boolean {
        val sameId = held.values.map { it.body }.filter { it is SosBody && it.id == cancel.ref }
        return sameId.isEmpty() || sameId.any { it.pk == cancel.pk }
    }

    private fun prune() {
        val now = clock.nowMillis()
        if (now - lastPruneAt < config.pruneEveryMs) return
        lastPruneAt = now
        held.values.removeAll { now > it.body.exp + Protocol.CLOCK_SKEW_MS }
        seen.values.removeAll { forgetAt -> now > forgetAt }
        recentSos.values.removeAll { times -> times.isEmpty() || now - times.last() >= config.sosWindowMs }
    }

    private fun parseOrNull(packet: Packet): PacketBody? =
        try {
            BodyJson.decode(packet.body)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: CharacterCodingException) {
            null
        }

    private fun trim(set: LinkedHashSet<String>, max: Int) {
        while (set.size > max) set.remove(set.first())
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
