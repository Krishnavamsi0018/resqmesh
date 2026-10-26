package com.resqmesh.mesh.packet

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.Fixtures.HOUR
import com.resqmesh.mesh.Fixtures.NOW
import com.resqmesh.mesh.Protocol
import com.resqmesh.mesh.crypto.Ed25519
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketFactoryTest {
    private val keys = Fixtures.keys(1)
    private val factory = PacketFactory(keys, FixedClock(NOW))

    private fun sosBody(packet: Packet) = BodyJson.decode(packet.body) as SosBody

    @Test
    fun `new SOS starts with full TTL, no hops and a 12 h lifetime`() {
        val packet = factory.sos(Category.FLOOD, Location(12.97160, 77.59456, accuracyM = 15, fixAgeS = 40), batteryPercent = 42, people = 2)
        val body = sosBody(packet)

        assertEquals(PacketType.SOS, packet.type)
        assertEquals(Protocol.DEFAULT_TTL, packet.ttl)
        assertEquals(0, packet.hops)
        assertEquals(NOW, body.ts)
        assertEquals(NOW + 12 * HOUR, body.exp)
        assertEquals(keys.publicKeyB64, body.pk)
        assertEquals(Protocol.ID_SIZE, Base64Url.decodeOrNull(body.id)?.size)
        assertEquals(Category.FLOOD, body.cat)
        assertEquals(15, body.acc)
        assertEquals(40, body.fixAge)
        assertEquals(42, body.bat)
        assertEquals(2, body.ppl)
    }

    @Test
    fun `signature covers the exact body bytes`() {
        val packet = factory.sos(Category.MEDICAL, location = null)
        assertTrue(Ed25519.verify(keys.publicKey, packet.body, packet.signature))
    }

    @Test
    fun `coordinates are rounded to 5 decimals`() {
        val body = sosBody(factory.sos(Category.FIRE, Location(12.9715987, 77.5945627)))
        assertEquals(12.9716, body.lat)
        assertEquals(77.59456, body.lon)
    }

    @Test
    fun `no fix means no coordinates`() {
        val body = sosBody(factory.sos(Category.TRAPPED, location = null))
        assertNull(body.lat)
        assertNull(body.lon)
    }

    @Test
    fun `note is trimmed, capped at 120 characters, and dropped when blank`() {
        assertEquals("leg injury", sosBody(factory.sos(Category.MEDICAL, null, note = "  leg injury  ")).note)
        assertEquals(Protocol.MAX_NOTE_LENGTH, sosBody(factory.sos(Category.MEDICAL, null, note = "x".repeat(500))).note?.length)
        assertNull(sosBody(factory.sos(Category.MEDICAL, null, note = "   ")).note)
    }

    @Test
    fun `every packet gets a fresh ID`() {
        val a = sosBody(factory.sos(Category.WATER, null))
        val b = sosBody(factory.sos(Category.WATER, null))
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `cancel and acks point at the SOS they answer`() {
        val sosId = sosBody(factory.sos(Category.FLOOD, null)).id

        val cancel = BodyJson.decode(factory.cancel(sosId, CancelReason.MISTAKE).body) as CancelBody
        assertEquals(sosId, cancel.ref)
        assertEquals(CancelReason.MISTAKE, cancel.reason)

        val gateway = BodyJson.decode(factory.gatewayAck(sosId, uploaded = true, sms = false).body) as AckBody
        assertEquals(sosId, gateway.ref)
        assertEquals(AckKind.GATEWAY, gateway.kind)
        assertEquals(true, gateway.up)
        assertEquals(false, gateway.sms)

        val responder = BodyJson.decode(factory.responderAck(sosId, ResponderCodes.TEAM_DISPATCHED, unit = "SDRF").body) as AckBody
        assertEquals(AckKind.RESPONDER, responder.kind)
        assertEquals(ResponderCodes.TEAM_DISPATCHED, responder.code)
        assertEquals("SDRF", responder.by)
    }

    @Test
    fun `a body over 900 bytes is refused`() {
        val huge = SosBody(id = "AAECAwQFBgcICQoLDA0ODw", pk = keys.publicKeyB64, ts = NOW, exp = NOW + HOUR, cat = Category.GENERAL, note = "x".repeat(1000))
        assertFailsWith<IllegalArgumentException> { factory.seal(huge) }
    }
}
