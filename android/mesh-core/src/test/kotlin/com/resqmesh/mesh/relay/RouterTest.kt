package com.resqmesh.mesh.relay

import com.resqmesh.mesh.Fixtures.HOUR
import com.resqmesh.mesh.Protocol
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.CancelBody
import com.resqmesh.mesh.packet.CancelReason
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.SosBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouterTest {
    private val mesh = FakeMesh()

    private fun sos(phone: FakeMesh.Phone, ttl: Int = Protocol.DEFAULT_TTL): Packet {
        val packet = phone.factory.sos(Category.FLOOD, location = null)
        // TTL lives in the unsigned header, so a test can set it without re-signing.
        return Packet(packet.type, ttl, 0, packet.body, packet.signature)
    }

    private fun idOf(packet: Packet) = BodyJson.decode(packet.body).id

    @Test
    fun `an SOS crosses a chain one hop at a time`() {
        val (a, b, c, d) = List(4) { mesh.phone(it + 1) }
        mesh.chain(a, b, c, d)

        assertEquals(1, a.router.originate(sos(a)))
        mesh.deliverAll()

        assertEquals(listOf(0 to a.id), b.delivered.map { it.packet.hops to it.from })
        assertEquals(listOf(1 to b.id), c.delivered.map { it.packet.hops to it.from })
        assertEquals(listOf(2 to c.id), d.delivered.map { it.packet.hops to it.from })
        assertTrue(a.delivered.isEmpty())
        // a→b, b→c, c→d: nothing is sent back the way it came.
        assertEquals(3, mesh.packetTransmissions)
        assertTrue(listOf(a, b, c, d).all { it.duplicates.isEmpty() })
    }

    @Test
    fun `the sender hears about each neighbour its SOS reaches, live or on catch-up`() {
        val (a, b, c) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b)
        val packet = sos(a)

        a.router.originate(packet)
        mesh.deliverAll()
        mesh.link(a, c)
        mesh.deliverAll()

        assertEquals(listOf(idOf(packet) to b.id, idOf(packet) to c.id), a.handedOver)
    }

    @Test
    fun `TTL limits how far a packet travels`() {
        val line = List(6) { mesh.phone(it + 1) }
        mesh.chain(*line.toTypedArray())

        line[0].router.originate(sos(line[0], ttl = 3))
        mesh.deliverAll()

        assertEquals(listOf(1, 1, 1, 0, 0), line.drop(1).map { it.delivered.size })
        assertEquals(2, line[3].delivered.single().packet.hops)
    }

    @Test
    fun `each phone delivers a packet once even when two paths reach it`() {
        val (a, b, c, d) = List(4) { mesh.phone(it + 1) }
        mesh.link(a, b)
        mesh.link(a, c)
        mesh.link(b, d)
        mesh.link(c, d)
        mesh.deliverAll()

        a.router.originate(sos(a))
        mesh.deliverAll()

        assertEquals(listOf(1, 1, 1), listOf(b, c, d).map { it.delivered.size })
        assertEquals(1, d.duplicates.size)
        // a→b, a→c, b→d, c→d, d→c, then the flood stops by itself.
        assertEquals(5, mesh.packetTransmissions)
    }

    @Test
    fun `a phone that links later still gets the SOS`() {
        val (a, b, c) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b)
        a.router.originate(sos(a))
        mesh.deliverAll()

        mesh.link(b, c)
        mesh.deliverAll()

        assertEquals(listOf(1 to b.id), c.delivered.map { it.packet.hops to it.from })
    }

    @Test
    fun `plain flooding, the experiments' baseline, never reaches a phone that links later`() {
        val flooding = RouterConfig(storeCarryForward = false)
        val (a, b, c) = List(3) { mesh.phone(it + 1, flooding) }
        mesh.chain(a, b)
        a.router.originate(sos(a))
        mesh.deliverAll()

        mesh.link(b, c)
        mesh.deliverAll()

        assertEquals(1, b.delivered.size, "phones linked while it spreads still get it")
        assertTrue(c.delivered.isEmpty())
    }

    @Test
    fun `a sender with no neighbours hands its SOS to the first phone that comes along`() {
        val (a, b) = List(2) { mesh.phone(it + 1) }
        assertEquals(0, a.router.originate(sos(a)))

        mesh.link(a, b)
        mesh.deliverAll()

        assertEquals(1, b.delivered.size)
    }

    @Test
    fun `expired packets are not handed on`() {
        val (a, b, c) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b)
        a.router.originate(sos(a))
        mesh.deliverAll()

        mesh.clock.now += 12 * HOUR + Protocol.CLOCK_SKEW_MS + 60_000
        mesh.link(b, c)
        mesh.deliverAll()

        assertTrue(c.delivered.isEmpty())
    }

    @Test
    fun `a forged copy arriving first does not block the real SOS`() {
        val (a, b, c) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b, c)
        val real = sos(a)
        val forged = Packet(real.type, real.ttl, real.hops, real.body.decodeToString().replace("FLOOD", "FIRE").encodeToByteArray(), real.signature)

        c.router.onReceive(b.id, PacketCodec.encode(forged))
        a.router.originate(real)
        mesh.deliverAll()

        assertEquals(DropKind.INVALID to "BAD_SIGNATURE", c.dropped.single())
        assertEquals(Category.FLOOD, (c.delivered.single().body as SosBody).cat)
    }

    @Test
    fun `someone else's packet reusing an SOS ID is a different packet`() {
        val (a, b, mallory) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b)
        mesh.link(mallory, b)
        mesh.deliverAll()
        val real = sos(a)
        val squatter = mallory.factory.seal(
            SosBody(id = idOf(real), pk = mallory.keys.publicKeyB64, ts = mesh.clock.now, exp = mesh.clock.now + HOUR, cat = Category.FIRE),
        )

        mallory.router.originate(squatter)
        mesh.deliverAll()
        a.router.originate(real)
        mesh.deliverAll()

        assertEquals(setOf(Category.FIRE, Category.FLOOD), b.delivered.map { (it.body as SosBody).cat }.toSet())
    }

    @Test
    fun `at most 5 SOS per sender per 10 minutes`() {
        val (a, b) = List(2) { mesh.phone(it + 1) }
        mesh.chain(a, b)

        repeat(6) { a.router.originate(sos(a)) }
        mesh.deliverAll()
        assertEquals(5, b.delivered.size)
        assertEquals(DropKind.RATE_LIMITED, b.dropped.single().first)

        mesh.clock.now += 10 * 60 * 1000L
        a.router.originate(sos(a))
        mesh.deliverAll()
        assertEquals(6, b.delivered.size)
    }

    @Test
    fun `only the SOS sender can cancel it`() {
        val (a, b, mallory) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b)
        mesh.link(mallory, b)
        mesh.deliverAll()
        val sosPacket = sos(a)
        a.router.originate(sosPacket)
        mesh.deliverAll()

        mallory.router.originate(mallory.factory.cancel(idOf(sosPacket), CancelReason.MISTAKE))
        mesh.deliverAll()
        assertEquals(DropKind.CANCEL_KEY_MISMATCH, b.dropped.single().first)

        a.router.originate(a.factory.cancel(idOf(sosPacket), CancelReason.SAFE))
        mesh.deliverAll()
        assertIs<CancelBody>(b.delivered.last().body)
    }

    @Test
    fun `a newly linked phone gets missing SOS before ACKs`() {
        val (a, b, c) = List(3) { mesh.phone(it + 1) }
        mesh.chain(a, b)
        val sosPacket = sos(a)
        a.router.originate(a.factory.gatewayAck(idOf(sosPacket), uploaded = true, sms = false))
        a.router.originate(sosPacket)
        mesh.deliverAll()

        mesh.link(b, c)
        mesh.deliverAll()

        assertIs<SosBody>(c.delivered[0].body)
        assertIs<AckBody>(c.delivered[1].body)
    }

    @Test
    fun `a broken control frame is ignored`() {
        val (a, b) = List(2) { mesh.phone(it + 1) }
        mesh.chain(a, b)

        b.router.onReceive(a.id, byteArrayOf(0x52, 0x43, 1, 1, 0, 9))

        assertEquals(DropKind.MALFORMED to "control frame", b.dropped.single())
    }
}
