package com.resqmesh.mesh.gateway

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketType
import com.resqmesh.mesh.packet.ResponderCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayQueueTest {
    private val clock = FixedClock(Fixtures.NOW)
    private val queue = GatewayQueue(clock, maxBatch = 3, maxPending = 4)
    private val sender = PacketFactory(Fixtures.keys(1), clock)
    private val otherGateway = PacketFactory(Fixtures.keys(2), clock)
    private val server = PacketFactory(Fixtures.keys(3), clock)

    private fun offer(packet: Packet) = packet.also { queue.offer(it, BodyJson.decode(it.body)) }

    private fun idOf(packet: Packet) = BodyJson.decode(packet.body).id

    private fun sos() = offer(sender.sos(Category.FLOOD, location = null))

    @Test
    fun `SOS and cancels are queued once each, other acks are not`() {
        val flood = sos()
        offer(flood)
        offer(sender.cancel(idOf(flood), CancelReason.SAFE))
        offer(server.responderAck(idOf(flood), ResponderCodes.STAY_PUT))
        offer(otherGateway.gatewayAck(idOf(flood), uploaded = true, sms = false))

        assertEquals(listOf(PacketType.SOS, PacketType.CANCEL), queue.batch().map { it.type })
    }

    @Test
    fun `a gateway ack saying the SOS was texted is uploaded, so the server knows`() {
        val flood = sos()
        offer(otherGateway.gatewayAck(idOf(flood), uploaded = false, sms = true))

        assertEquals(listOf(PacketType.SOS, PacketType.ACK), queue.batch().map { it.type })
    }

    @Test
    fun `a confirmed SOS is acknowledged once and then watched for replies`() {
        val flood = sos()
        val batch = queue.batch()

        assertEquals(listOf(idOf(flood)), queue.onUploaded(batch, listOf(UploadStatus.STORED)))
        assertEquals(0, queue.pendingCount)
        assertEquals(listOf(idOf(flood)), queue.watchedSos())

        // Seen again later (another phone relays it back): nothing new to upload or acknowledge.
        offer(flood)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `no second gateway ack when another gateway already sent one`() {
        val flood = sos()
        offer(otherGateway.gatewayAck(idOf(flood), uploaded = true, sms = false))

        val batch = queue.batch()
        assertTrue(queue.onUploaded(batch, batch.map { UploadStatus.DUPLICATE }).isEmpty())
        assertEquals(listOf(idOf(flood)), queue.watchedSos(), "still watched for replies")
    }

    @Test
    fun `unanswered items stay queued, rejected ones are dropped for good`() {
        val a = sos()
        val b = sos()
        queue.onUploaded(queue.batch(), listOf(UploadStatus.REJECTED, null))

        assertEquals(listOf(idOf(b)), queue.batch().map { it.id }, "the unanswered one is tried again")
        offer(a)
        assertEquals(listOf(idOf(b)), queue.batch().map { it.id }, "a rejected packet is never retried")
    }

    @Test
    fun `batches respect the size limit and put SOS before acks`() {
        val first = sos()
        offer(otherGateway.gatewayAck(idOf(first), uploaded = false, sms = true))
        repeat(2) { sos() }

        val batch = queue.batch()
        assertEquals(3, batch.size)
        assertTrue(batch.all { it.type == PacketType.SOS })
    }

    @Test
    fun `when full, acks are dropped before any SOS`() {
        val first = sos()
        offer(otherGateway.gatewayAck(idOf(first), uploaded = false, sms = true))
        repeat(3) { sos() }

        assertEquals(4, queue.pendingCount)
        assertTrue(queue.batch().none { it.type == PacketType.ACK })
    }

    @Test
    fun `expired SOS stop being watched`() {
        val flood = sos()
        queue.onUploaded(queue.batch(), listOf(UploadStatus.STORED))

        clock.now = BodyJson.decode(flood.body).exp + 1
        assertTrue(queue.watchedSos().isEmpty())
    }
}
