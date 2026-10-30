package com.resqmesh.mesh.sos

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.AckKind
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.ResponderCodes
import com.resqmesh.mesh.packet.SosBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SosTrackerTest {
    private val clock = FixedClock(Fixtures.NOW)
    private val tracker = SosTracker(clock)
    private val me = PacketFactory(Fixtures.keys(1), clock)
    private val gateway = PacketFactory(Fixtures.keys(2), clock)
    private val server = PacketFactory(Fixtures.keys(3), clock)
    private val b = NodeId("B000000000000000")
    private val c = NodeId("C000000000000000")

    private val sos = BodyJson.decode(me.sos(Category.FLOOD, location = null, people = 3).body) as SosBody

    private fun ack(factory: PacketFactory, kind: AckKind, ref: String = sos.id): AckBody {
        val packet = when (kind) {
            AckKind.GATEWAY -> factory.gatewayAck(ref, uploaded = true, sms = false)
            AckKind.RESPONDER -> factory.responderAck(ref, ResponderCodes.TEAM_DISPATCHED, "Boat coming", "NDRF 4")
        }
        return BodyJson.decode(packet.body) as AckBody
    }

    @Test
    fun `a new SOS waits for phones`() {
        tracker.started(sos)

        val mine = tracker.current!!
        assertEquals(SosStep.WAITING, mine.step)
        assertEquals(Category.FLOOD, mine.category)
        assertEquals(3, mine.people)
    }

    @Test
    fun `each neighbour it reaches is counted once`() {
        tracker.started(sos)
        clock.now += 4_000

        assertTrue(tracker.onHandedOver(sos, b))
        assertFalse(tracker.onHandedOver(sos, b))
        assertTrue(tracker.onHandedOver(sos, c))

        val mine = tracker.current!!
        assertEquals(SosStep.RELAYED, mine.step)
        assertEquals(setOf(b, c), mine.passedTo)
        assertEquals(Fixtures.NOW + 4_000, mine.relayedAt)
    }

    @Test
    fun `other people's packets don't move my SOS`() {
        tracker.started(sos)
        val someoneElse = BodyJson.decode(gateway.sos(Category.FIRE, location = null).body)

        assertFalse(tracker.onHandedOver(someoneElse, b))
        assertFalse(tracker.onAck(ack(gateway, AckKind.GATEWAY, ref = someoneElse.id)))
        assertEquals(SosStep.WAITING, tracker.current!!.step)
    }

    @Test
    fun `a gateway ack means the rescue team is informed, a responder ack that help is coming`() {
        tracker.started(sos)
        tracker.onHandedOver(sos, b)

        assertTrue(tracker.onAck(ack(gateway, AckKind.GATEWAY)))
        assertEquals(SosStep.NOTIFIED, tracker.current!!.step)
        assertFalse(tracker.onAck(ack(gateway, AckKind.GATEWAY)), "a second gateway changes nothing")

        assertTrue(tracker.onAck(ack(server, AckKind.RESPONDER)))
        val mine = tracker.current!!
        assertEquals(SosStep.ACKED, mine.step)
        assertEquals(Reply(ResponderCodes.TEAM_DISPATCHED, "Boat coming", "NDRF 4"), mine.reply)
    }

    @Test
    fun `steps only move forward`() {
        tracker.started(sos)
        tracker.onAck(ack(server, AckKind.RESPONDER))

        tracker.onAck(ack(gateway, AckKind.GATEWAY))
        tracker.onHandedOver(sos, c)

        assertEquals(SosStep.ACKED, tracker.current!!.step)
        // A reply that skips the gateway step still marks the team as informed.
        assertEquals(Fixtures.NOW, tracker.current!!.notifiedAt)
    }

    @Test
    fun `I'm safe closes the SOS and nothing reopens it`() {
        tracker.started(sos)
        assertTrue(tracker.cancelled(CancelReason.SAFE))

        assertFalse(tracker.onAck(ack(server, AckKind.RESPONDER)))
        assertFalse(tracker.cancelled(CancelReason.MISTAKE))
        val mine = tracker.current!!
        assertEquals(SosStep.CLOSED, mine.step)
        assertEquals(CloseReason.SAFE, mine.closeReason)
    }

    @Test
    fun `an SOS closes itself when it expires, and can then be dismissed`() {
        tracker.started(sos)
        tracker.dismiss()
        assertTrue(tracker.current!!.isOpen, "an open SOS is never dismissed")

        clock.now = sos.exp
        assertFalse(tracker.expireIfDue())
        clock.now = sos.exp + 1
        assertTrue(tracker.expireIfDue())
        assertEquals(CloseReason.EXPIRED, tracker.current!!.closeReason)

        tracker.dismiss()
        assertNull(tracker.current)
    }
}
