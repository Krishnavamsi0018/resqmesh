package com.resqmesh.mesh.gateway

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.Fixtures
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.Location
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.SosBody
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsEscalatorTest {
    private val clock = FixedClock(Fixtures.NOW)
    private val sms = SmsEscalator(clock, Random(1), maxPerHour = 3)
    private val sender = PacketFactory(Fixtures.keys(1), clock)
    private val otherGateway = PacketFactory(Fixtures.keys(2), clock)

    private fun sos(test: Boolean = false, location: Location? = null) =
        BodyJson.decode(sender.sos(Category.FLOOD, location, people = 2, test = test).body) as SosBody

    private fun smsAck(id: String) = BodyJson.decode(otherGateway.gatewayAck(id, uploaded = false, sms = true).body) as AckBody

    private fun later(ms: Long) {
        clock.now += ms
    }

    @Test
    fun `an SOS is texted once, after a random 5 to 20 s wait`() {
        val flood = sos()
        sms.offer(flood, hops = 2)
        later(4_999)
        assertTrue(sms.due().isEmpty(), "never before 5 s")
        later(15_001)
        assertEquals(listOf(flood.id), sms.due().map { it.id })
        assertTrue(sms.due().isEmpty(), "not handed out twice while sending")

        assertTrue(sms.onResult(flood.id, sent = true), "the caller floods an SMS ack")
        sms.offer(flood, hops = 1)
        later(60_000)
        assertTrue(sms.due().isEmpty(), "one SMS per SOS per phone")
    }

    @Test
    fun `another phone's SMS ack means this phone stays quiet`() {
        val flood = sos()
        sms.offer(flood, hops = 1)
        sms.onAck(smsAck(flood.id))
        later(30_000)
        assertTrue(sms.due().isEmpty())
        assertEquals(0, sms.pendingCount)
    }

    @Test
    fun `an upload ack does not stop the SMS`() {
        val flood = sos()
        sms.offer(flood, hops = 1)
        sms.onAck(BodyJson.decode(otherGateway.gatewayAck(flood.id, uploaded = true, sms = false).body) as AckBody)
        later(20_000)
        assertEquals(1, sms.due().size)
    }

    @Test
    fun `test and cancelled SOS are never texted`() {
        sms.offer(sos(test = true), hops = 0)
        val flood = sos()
        sms.offer(flood, hops = 0)
        sms.onCancel(flood.id)
        later(30_000)
        assertTrue(sms.due().isEmpty())
    }

    @Test
    fun `no more than the hourly limit, the rest wait for the window`() {
        repeat(5) { sms.offer(sos(), hops = 0) }
        later(20_000)
        val first = sms.due()
        assertEquals(3, first.size)
        first.forEach { sms.onResult(it.id, sent = true) }
        later(10 * 60_000L)
        assertTrue(sms.due().isEmpty(), "budget spent")
        later(Fixtures.HOUR)
        assertEquals(2, sms.due().size)
    }

    @Test
    fun `a failed SMS is retried a minute later, then given up after three tries`() {
        val flood = sos()
        sms.offer(flood, hops = 0)
        later(20_000)
        repeat(2) {
            assertFalse(sms.onResult(sms.due().single().id, sent = false))
            later(59_000)
            assertTrue(sms.due().isEmpty())
            later(1_000)
        }
        assertFalse(sms.onResult(sms.due().single().id, sent = false))
        later(60_000)
        assertTrue(sms.due().isEmpty())
        assertEquals(0, sms.pendingCount)
    }

    @Test
    fun `when service comes back, every waiting SOS waits again`() {
        val flood = sos()
        sms.offer(flood, hops = 0)
        later(25_000) // ready, but there was no signal to send it
        sms.onServiceChanged(available = true)
        assertTrue(sms.due().isEmpty(), "phones that regain signal together don't all send at once")
        later(20_000)
        assertEquals(1, sms.due().size)
    }

    @Test
    fun `an ack that arrives while this phone is sending stops a second ack`() {
        val flood = sos()
        sms.offer(flood, hops = 0)
        later(20_000)
        sms.due()
        sms.onAck(smsAck(flood.id))
        assertFalse(sms.onResult(flood.id, sent = true), "the mesh already knows it was texted")
        assertEquals(1, sms.sentLastHour)
    }

    @Test
    fun `expired SOS are dropped`() {
        val flood = sos()
        sms.offer(flood, hops = 0)
        clock.now = flood.exp
        assertTrue(sms.due().isEmpty())
        assertEquals(0, sms.pendingCount)
    }

    @Test
    fun `the SMS fits one GSM-7 message and carries what responders need`() {
        val flood = sos(location = Location(-12.971601, -177.594562, accuracyM = 9999))
        val text = SmsText.format(flood, hops = 8, ZoneId.of("Asia/Kolkata"))
        assertTrue(text.length <= SmsText.MAX_LENGTH, "${text.length}: $text")
        assertTrue(text.all { it.code in 0x20..0x7e && it !in "[]{}\\^~|`" }, "plain GSM-7 basic characters: $text")
        assertTrue(text.startsWith("SOS FLOOD via ResQMesh. 2 ppl. -12.97160,-177.59456 +/-9999m maps.google.com/?q=-12.97160,-177.59456"), text)
        assertTrue(text.endsWith("ID ${flood.id.take(6)} hops 8"), text)
        assertTrue(" from ${Fixtures.keys(1).nodeId.short} " in text, text)

        val noFix = SmsText.format(sos(), hops = 0, ZoneId.of("Asia/Kolkata"))
        assertTrue("No location." in noFix, noFix)
    }
}
