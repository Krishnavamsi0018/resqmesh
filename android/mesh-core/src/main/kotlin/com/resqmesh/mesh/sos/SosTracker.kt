package com.resqmesh.mesh.sos

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.AckKind
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.SosBody

/** The four steps the sender sees (F-14, design 3.5 status stepper), plus closed. */
enum class SosStep { WAITING, RELAYED, NOTIFIED, ACKED, CLOSED }

enum class CloseReason { SAFE, MISTAKE, EXPIRED }

/** What a responder sent back (a RESPONDER ack). Phones show [code] in the sender's language. */
data class Reply(val code: String?, val message: String?, val unit: String?)

/**
 * The sender's view of their own SOS (TRD 2.8, `my_sos` in 2.9). Each step keeps the time it was
 * reached, epoch ms, so the status screen can show when things happened.
 */
data class MySos(
    val id: String,
    val category: Category,
    val people: Int,
    val sentAt: Long,
    val expiresAt: Long,
    /** Whether the SOS carries a location, and how accurate it is in metres. */
    val located: Boolean = false,
    val accuracyM: Int? = null,
    /** Neighbours this phone handed the SOS to; later relays are out of sight until a gateway reports. */
    val passedTo: Set<NodeId> = emptySet(),
    val relayedAt: Long? = null,
    val notifiedAt: Long? = null,
    val ackedAt: Long? = null,
    val reply: Reply? = null,
    val closedAt: Long? = null,
    val closeReason: CloseReason? = null,
) {
    val step: SosStep
        get() = when {
            closedAt != null -> SosStep.CLOSED
            ackedAt != null -> SosStep.ACKED
            notifiedAt != null -> SosStep.NOTIFIED
            relayedAt != null -> SosStep.RELAYED
            else -> SosStep.WAITING
        }

    val isOpen: Boolean get() = closedAt == null
}

/**
 * Follows this phone's latest SOS through the mesh. Each method returns whether anything changed,
 * so the app redraws only then. Steps only move forward: a late gateway ack never undoes a reply.
 * Not thread-safe; the app calls it from the main thread.
 */
class SosTracker(private val clock: Clock) {
    var current: MySos? = null
        private set

    fun started(body: SosBody) {
        current = MySos(
            id = body.id,
            category = body.cat,
            people = body.ppl,
            sentAt = body.ts,
            expiresAt = body.exp,
            located = body.lat != null && body.lon != null,
            accuracyM = body.acc,
        )
    }

    /** The Router passed [body] to a neighbour, live or while catching it up after linking. */
    fun onHandedOver(body: PacketBody, to: NodeId): Boolean {
        val sos = current?.takeIf { it.isOpen && it.id == body.id && to !in it.passedTo } ?: return false
        current = sos.copy(passedTo = sos.passedTo + to, relayedAt = sos.relayedAt ?: clock.nowMillis())
        return true
    }

    /** A valid ack reached this phone. Gateway acks mean "rescue team informed", responder acks "help is coming". */
    fun onAck(ack: AckBody): Boolean {
        val sos = current?.takeIf { it.isOpen && it.id == ack.ref } ?: return false
        val now = clock.nowMillis()
        current = when (ack.kind) {
            AckKind.GATEWAY -> if (sos.notifiedAt != null) return false else sos.copy(notifiedAt = now)
            AckKind.RESPONDER -> sos.copy(
                notifiedAt = sos.notifiedAt ?: now,
                ackedAt = now,
                reply = Reply(ack.code, ack.msg, ack.by),
            )
        }
        return true
    }

    /** The sender cancelled ("I'm safe now" or "sent by mistake"); the cancel packet is already on its way. */
    fun cancelled(reason: CancelReason): Boolean {
        val sos = current?.takeIf { it.isOpen } ?: return false
        val why = when (reason) {
            CancelReason.SAFE -> CloseReason.SAFE
            CancelReason.MISTAKE -> CloseReason.MISTAKE
        }
        current = sos.copy(closedAt = clock.nowMillis(), closeReason = why)
        return true
    }

    /** Closes the SOS once relays would drop it anyway (12 h after sending). */
    fun expireIfDue(): Boolean {
        val sos = current?.takeIf { it.isOpen && clock.nowMillis() > it.expiresAt } ?: return false
        current = sos.copy(closedAt = sos.expiresAt, closeReason = CloseReason.EXPIRED)
        return true
    }

    /** Forgets a closed SOS, so the home screen is ready for a new one. */
    fun dismiss() {
        if (current?.isOpen == false) current = null
    }
}
