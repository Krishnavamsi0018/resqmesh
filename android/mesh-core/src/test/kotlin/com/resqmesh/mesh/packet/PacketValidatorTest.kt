package com.resqmesh.mesh.packet

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.Fixtures.HOUR
import com.resqmesh.mesh.Fixtures.NOW
import com.resqmesh.mesh.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PacketValidatorTest {
    private val sender = Fixtures.keys(1)
    private val server = Fixtures.keys(0x81)
    private val clock = FixedClock(NOW)
    private val factory = PacketFactory(sender, clock)
    private val serverFactory = PacketFactory(server, clock)
    private val validator = PacketValidator(server.publicKey, clock)

    private val flood = factory.sos(Category.FLOOD, Location(12.9716, 77.59456, 15, 40), batteryPercent = 42, people = 2)
    private val floodId = (BodyJson.decode(flood.body) as SosBody).id

    private fun sosBody(
        ts: Long = NOW,
        exp: Long = NOW + HOUR,
        lat: Double? = null,
        lon: Double? = null,
        ppl: Int = 1,
        bat: Int? = null,
        note: String? = null,
    ) = SosBody(
        id = "AAECAwQFBgcICQoLDA0ODw",
        pk = sender.publicKeyB64,
        ts = ts,
        exp = exp,
        cat = Category.GENERAL,
        lat = lat,
        lon = lon,
        ppl = ppl,
        bat = bat,
        note = note,
    )

    private fun assertAccepted(packet: Packet) {
        val verdict = validator.validate(packet)
        assertIs<Verdict.Accepted>(verdict, verdict.toString())
    }

    private fun assertRejected(reason: RejectReason, packet: Packet) {
        val verdict = validator.validate(packet)
        assertIs<Verdict.Rejected>(verdict, "expected $reason")
        assertEquals(reason, verdict.reason, verdict.detail)
    }

    @Test
    fun `accepts every kind of well-formed packet`() {
        assertAccepted(flood)
        assertAccepted(factory.cancel(floodId, CancelReason.SAFE))
        assertAccepted(Fixtures.keys(0x41).let { PacketFactory(it, clock) }.gatewayAck(floodId, uploaded = true, sms = true))
        assertAccepted(serverFactory.responderAck(floodId, ResponderCodes.TEAM_DISPATCHED, "Stay where you are", "District Control Room"))
    }

    @Test
    fun `accepts a packet that has already been relayed`() {
        assertAccepted(flood.forwarded().forwarded())
    }

    @Test
    fun `a relay that edits the body breaks the signature`() {
        val edited = flood.body.decodeToString().replace("12.9716", "12.9816").encodeToByteArray()
        assertRejected(RejectReason.BAD_SIGNATURE, Packet(flood.type, flood.ttl, flood.hops, edited, flood.signature))
    }

    @Test
    fun `a signature from a different key is rejected`() {
        val forged = Fixtures.keys(9).sign(flood.body)
        assertRejected(RejectReason.BAD_SIGNATURE, Packet(flood.type, flood.ttl, flood.hops, flood.body, forged))
    }

    @Test
    fun `only the server key may send a responder ack`() {
        assertRejected(RejectReason.UNTRUSTED_RESPONDER, factory.responderAck(floodId, ResponderCodes.STAY_PUT))
    }

    @Test
    fun `header type must match the body`() {
        assertRejected(RejectReason.TYPE_MISMATCH, Packet(PacketType.CANCEL, flood.ttl, flood.hops, flood.body, flood.signature))
    }

    @Test
    fun `ttl must be between 1 and 8`() {
        assertRejected(RejectReason.TTL_EXHAUSTED, Packet(flood.type, 0, 8, flood.body, flood.signature))
        assertRejected(RejectReason.TTL_TOO_HIGH, Packet(flood.type, Protocol.MAX_TTL + 1, 0, flood.body, flood.signature))
    }

    @Test
    fun `expiry allows 10 minutes of clock skew`() {
        val exp = (BodyJson.decode(flood.body) as SosBody).exp

        clock.now = exp + Protocol.CLOCK_SKEW_MS
        assertAccepted(flood)
        clock.now = exp + Protocol.CLOCK_SKEW_MS + 1
        assertRejected(RejectReason.EXPIRED, flood)
    }

    @Test
    fun `a sender clock that runs fast is still accepted`() {
        assertAccepted(factory.seal(sosBody(ts = NOW + 2 * HOUR, exp = NOW + 3 * HOUR)))
    }

    @Test
    fun `lifetime must be positive and at most 24 h`() {
        assertRejected(RejectReason.BAD_LIFETIME, factory.seal(sosBody(exp = NOW + 25 * HOUR)))
        assertRejected(RejectReason.BAD_LIFETIME, factory.seal(sosBody(exp = NOW)))
    }

    @Test
    fun `SOS fields must be in range`() {
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(sosBody(lat = 91.0, lon = 77.0)))
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(sosBody(lat = 12.0, lon = 181.0)))
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(sosBody(lat = 12.0)))
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(sosBody(ppl = 0)))
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(sosBody(bat = 101)))
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(sosBody(note = "x".repeat(Protocol.MAX_NOTE_LENGTH + 1))))
    }

    @Test
    fun `acks must carry the fields their kind needs`() {
        val base = AckBody(id = "AAECAwQFBgcICQoLDA0ODw", pk = server.publicKeyB64, ts = NOW, exp = NOW + HOUR, ref = floodId, kind = AckKind.GATEWAY)
        assertRejected(RejectReason.BAD_FIELDS, serverFactory.seal(base.copy(up = true)))
        assertRejected(RejectReason.BAD_FIELDS, serverFactory.seal(base.copy(kind = AckKind.RESPONDER, code = " ")))
        assertRejected(RejectReason.BAD_FIELDS, serverFactory.seal(base.copy(kind = AckKind.RESPONDER, code = "X", ref = "short")))
    }

    @Test
    fun `IDs and keys must have the right length`() {
        assertRejected(RejectReason.BAD_ID, factory.seal(sosBody().copy(id = "abc")))
        assertRejected(RejectReason.BAD_KEY, factory.seal(sosBody().copy(pk = "abc")))
        assertRejected(RejectReason.BAD_FIELDS, factory.seal(CancelBody("AAECAwQFBgcICQoLDA0ODw", sender.publicKeyB64, NOW, NOW + HOUR, ref = "abc", reason = CancelReason.SAFE)))
    }

    @Test
    fun `bodies that are not JSON or not UTF-8 are rejected`() {
        val sig = ByteArray(Protocol.SIGNATURE_SIZE)
        assertRejected(RejectReason.BAD_BODY, Packet(PacketType.SOS, 8, 0, "not json".encodeToByteArray(), sig))
        assertRejected(RejectReason.BAD_BODY, Packet(PacketType.SOS, 8, 0, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), sig))
    }
}
