package com.resqmesh.mesh.gateway

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.AckKind
import com.resqmesh.mesh.packet.CancelBody
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.PacketType
import com.resqmesh.mesh.packet.SosBody
import com.resqmesh.mesh.relay.PacketKey

/** One packet waiting for upload, as the bytes this phone received or made. */
class UploadItem(val key: PacketKey, val id: String, val type: PacketType, val expiresAt: Long, val envelope: ByteArray)

/** The server's verdict on one uploaded packet. */
enum class UploadStatus { STORED, DUPLICATE, REJECTED }

/**
 * What a gateway phone uploads (F-40, TRD 2.7), kept free of Android so it can be tested.
 *
 * Every SOS and cancel this phone sees is queued, as is any gateway ack saying an SOS was texted
 * (week 8). Uploads go in batches of [maxBatch]. When the server confirms an SOS, this phone owes
 * the mesh a gateway ack ("rescue team informed", F-43) unless some gateway already sent one.
 * Confirmed SOS are then watched for responder replies (F-44) until they expire.
 * Not thread-safe; the app calls it from the main thread.
 */
class GatewayQueue(
    private val clock: Clock,
    private val maxBatch: Int = 50,
    private val maxPending: Int = 1000,
    private val maxWatched: Int = 100,
) {
    private val pending = LinkedHashMap<PacketKey, UploadItem>()
    private val done = HashSet<PacketKey>()
    /** SOS some gateway has acknowledged: ours, or an ack seen in the mesh. */
    private val acknowledged = HashSet<String>()
    /** Confirmed SOS by ID, with their expiry. */
    private val watched = LinkedHashMap<String, Long>()

    val pendingCount: Int get() = pending.size

    /** A packet this phone made or accepted. Only what the server needs is queued, each once. */
    fun offer(packet: Packet, body: PacketBody) {
        if (body is AckBody && body.kind == AckKind.GATEWAY) acknowledged += body.ref
        val wanted = when (body) {
            is SosBody, is CancelBody -> true
            is AckBody -> body.kind == AckKind.GATEWAY && body.sms == true
        }
        val key = PacketKey.of(body) ?: return
        if (!wanted || key in done || key in pending) return
        pending[key] = UploadItem(key, body.id, body.type, body.exp, PacketCodec.encode(packet))
        // SOS and cancels first if the queue overflows: drop the oldest ack, else the oldest item.
        while (pending.size > maxPending) {
            val victim = pending.values.firstOrNull { it.type == PacketType.ACK } ?: pending.values.first()
            pending.remove(victim.key)
        }
    }

    /** The next upload: SOS and cancels before acks (F-23), oldest first. */
    fun batch(): List<UploadItem> = pending.values.sortedBy { it.type.priority }.take(maxBatch)

    /**
     * The server answered [items] with [statuses] (null: no answer for that one, try again).
     * Returns the SOS IDs this phone should now acknowledge as a gateway, each once.
     */
    fun onUploaded(items: List<UploadItem>, statuses: List<UploadStatus?>): List<String> {
        val toAcknowledge = mutableListOf<String>()
        items.zip(statuses).forEach { (item, status) ->
            if (status == null) return@forEach
            // Rejected packets will never be accepted, so don't retry them either.
            pending.remove(item.key)
            done += item.key
            if (status != UploadStatus.REJECTED && item.type == PacketType.SOS) {
                watch(item.id, item.expiresAt)
                if (acknowledged.add(item.id)) toAcknowledge += item.id
            }
        }
        return toAcknowledge
    }

    /** SOS whose responder replies this phone should ask the server for, newest first. */
    fun watchedSos(): List<String> {
        val now = clock.nowMillis()
        watched.entries.removeAll { it.value < now }
        return watched.keys.reversed().take(maxWatched)
    }

    private fun watch(id: String, expiresAt: Long) {
        watched.remove(id)
        watched[id] = expiresAt
        while (watched.size > maxWatched * 2) watched.remove(watched.keys.first())
    }
}
