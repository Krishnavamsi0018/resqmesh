package com.resqmesh.mesh.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkManagerTest {
    private val radio = FakeRadio()
    private val a = radio.phone("1000000000000000")
    private val b = radio.phone("F000000000000000")

    @Test
    fun `two phones in range end up with one link`() {
        radio.inRange(a, b)
        radio.run()

        assertEquals(listOf(b.id), a.linkedTo)
        assertEquals(listOf(a.id), b.linkedTo)
        assertEquals(1, radio.connectionCount())
        assertTrue(a.notes.isEmpty() && b.notes.isEmpty(), "no failures: ${a.notes + b.notes}")
    }

    @Test
    fun `both phones asking at once still gives one link and no retries`() {
        // b saw a long before a saw b, so b's fallback fires while a's request is still in the air.
        b.manager.onFound(a.endpointId, a.name)
        radio.advance(29_000)
        a.manager.onFound(b.endpointId, b.name)
        radio.advance(1_000)
        radio.run()

        assertEquals(listOf(b.id), a.linkedTo)
        assertEquals(listOf(a.id), b.linkedTo)
        assertEquals(1, radio.connectionCount())
        assertEquals(listOf("Already connecting to ${a.id.short}, waiting"), b.notes)
        assertNull(a.manager.nextWakeAt())
        assertNull(b.manager.nextWakeAt())
    }

    @Test
    fun `a phone does not ask again while a connection the other phone started is pending`() {
        // b has seen a, but a hasn't seen b yet; b falls back and asks.
        b.manager.onFound(a.endpointId, a.name)
        radio.advance(30_000)
        radio.step()

        // a now discovers b while b's connection is still being set up: the old code asked again (8003 loop).
        a.manager.onFound(b.endpointId, b.name)
        radio.run()

        assertEquals(1, radio.connectionCount())
        assertEquals(listOf(b.id), a.linkedTo)
        assertTrue(a.notes.isEmpty() && b.notes.isEmpty(), "no failures: ${a.notes + b.notes}")
    }

    @Test
    fun `a dropped link comes back after the backoff`() {
        radio.inRange(a, b)
        radio.run()

        radio.breakLink(a, b)
        assertEquals(listOf(b.id), a.unlinkedFrom)
        assertEquals(0, radio.connectionCount())

        radio.advance(2_000)
        radio.run()
        assertEquals(1, radio.connectionCount())
        assertEquals(listOf(b.id, b.id), a.linkedTo)
    }

    @Test
    fun `a connection that never reports back times out and is retried`() {
        radio.inRange(a, b)
        radio.dropQueued()
        radio.advance(14_999)
        assertTrue(a.notes.isEmpty(), "too early to give up: ${a.notes}")

        radio.advance(1)
        assertTrue(a.notes.any { it.startsWith("Link attempt with ${b.id.short} timed out") }, a.notes.toString())
        radio.run()
        radio.advance(2_000)
        radio.run()
        assertEquals(1, radio.connectionCount())
        // The lower ID's retry got there first: b never had to fall back and ask itself.
        assertEquals(0, b.requests)
    }

    @Test
    fun `after a drop the lower ID retries first, so the phones don't collide`() {
        radio.inRange(a, b)
        radio.run()
        radio.breakLink(a, b)

        radio.advance(2_000)
        radio.run()

        assertEquals(1, radio.connectionCount())
        assertTrue(b.notes.none { it.startsWith("Already connecting") }, b.notes.toString())
    }

    @Test
    fun `chain mode refuses unticked phones until they are ticked`() {
        a.manager.setAllowOnly(emptySet())
        radio.inRange(a, b)
        radio.run()
        radio.advance(30_000)
        radio.run()

        assertEquals(0, radio.connectionCount())
        assertTrue(b.notes.any { it.contains("failed") }, b.notes.toString())

        a.manager.setAllowOnly(setOf(b.id))
        radio.run()
        assertEquals(1, radio.connectionCount())
    }

    @Test
    fun `unticking a linked phone drops the link`() {
        radio.inRange(a, b)
        radio.run()

        a.manager.setAllowOnly(emptySet())
        radio.run()

        assertEquals(0, radio.connectionCount())
        assertEquals(listOf(b.id), a.unlinkedFrom)
        assertEquals(listOf(a.id), b.unlinkedFrom)
    }

    @Test
    fun `no more than four links, and a fifth phone is turned away`() {
        val others = List(5) { radio.phone("E00000000000000$it") }
        others.forEach { radio.inRange(a, it) }
        radio.run()
        assertEquals(4, a.linkedTo.size)

        // The fifth phone falls back and asks itself; a turns it away.
        radio.advance(30_000)
        radio.run()
        assertEquals(4, a.linkedTo.size)
        assertEquals(4, radio.connectionCount())
    }

    @Test
    fun `a phone seen again under a new endpoint ID is not linked twice`() {
        radio.inRange(a, b)
        radio.run()

        a.manager.onFound("ep-new-id-for-b", b.name)

        assertEquals(1, a.manager.peers().count { it.state == LinkState.LINKED })
        assertEquals(LinkState.SEEN, a.manager.peers().single { it.endpointId == "ep-new-id-for-b" }.state)
    }

    @Test
    fun `a late failure result does not undo a link`() {
        radio.inRange(a, b)
        radio.run()

        a.manager.onResult(b.endpointId, false, 13)

        assertEquals(setOf(b.id), a.manager.linkedNodes())
    }

    @Test
    fun `a slow Bluetooth request is not met by the other phone asking too`() {
        radio.inRange(a, b)
        // In the four-phone test a request on a busy phone took 13 s to come back; a's is still in the air.
        radio.advance(13_000)
        radio.run()

        assertEquals(0, b.requests)
        assertEquals(1, radio.connectionCount())
        assertTrue(b.notes.isEmpty(), b.notes.toString())
    }

    @Test
    fun `switching chain mode off on both phones leaves the asking to the lower ID`() {
        a.manager.setAllowOnly(emptySet())
        b.manager.setAllowOnly(emptySet())
        radio.inRange(a, b)
        radio.advance(60_000)
        radio.run()

        a.manager.setAllowOnly(null)
        b.manager.setAllowOnly(null)
        radio.run()

        assertEquals(1, a.requests)
        assertEquals(0, b.requests)
        assertEquals(1, radio.connectionCount())
    }

    @Test
    fun `a request cancelled by chain mode is not reported as a failure`() {
        radio.inRange(a, b)
        // a's request is still in the air when chain mode turns on with nothing ticked.
        a.manager.setAllowOnly(emptySet())
        a.manager.onRequestFailed(b.endpointId, 8007)
        radio.dropQueued()

        assertTrue(a.notes.isEmpty(), a.notes.toString())
        // No backoff either: ticking b asks again at once.
        a.manager.setAllowOnly(setOf(b.id))
        assertEquals(2, a.requests)
    }

    @Test
    fun `a link that stops delivering is hung up and rebuilt`() {
        radio.inRange(a, b)
        radio.run()

        a.manager.onSendFailed(b.endpointId)
        radio.run()
        assertEquals(0, radio.connectionCount())
        assertEquals(listOf(b.id), a.unlinkedFrom)
        assertEquals(listOf(a.id), b.unlinkedFrom)
        assertEquals(listOf("Sending to ${b.id.short} failed, reconnecting"), a.notes)

        radio.advance(2_000)
        radio.run()
        assertEquals(1, radio.connectionCount())
        assertEquals(listOf(b.id, b.id), a.linkedTo)
    }

    @Test
    fun `three silent timeouts in a row with one phone report it as stuck`() {
        radio.inRange(a, b)
        // Like B and C in the 0.3.3 test: every request vanishes without an answer.
        repeat(3) {
            radio.dropQueued()
            radio.advance(15_000)
            radio.dropQueued()
            a.manager.nextWakeAt()?.let { radio.advance(it - radio.clock.now) }
        }

        assertEquals(listOf(b.id), a.stuckWith)
    }

    @Test
    fun `restarting discovery keeps links and forgets phones that are only seen`() {
        val c = radio.phone("E000000000000000")
        radio.inRange(a, b)
        radio.run()
        a.manager.setAllowOnly(setOf(b.id))
        a.manager.onFound(c.endpointId, c.name)

        a.manager.onDiscoveryRestarted()

        assertEquals(listOf(b.endpointId), a.manager.peers().map { it.endpointId })
        assertEquals(setOf(b.id), a.manager.linkedNodes())
    }

    @Test
    fun `an answer from Nearby, even a failure, resets the stuck count`() {
        radio.inRange(a, b)
        repeat(2) {
            radio.dropQueued()
            radio.advance(15_000)
            a.manager.nextWakeAt()?.let { radio.advance(it - radio.clock.now) }
        }
        // The third attempt gets an answer: the radio is working, only this request failed.
        radio.dropQueued()
        a.manager.onRequestFailed(b.endpointId, 8012)
        a.manager.nextWakeAt()?.let { radio.advance(it - radio.clock.now) }
        radio.dropQueued()
        radio.advance(15_000)

        assertTrue(a.stuckWith.isEmpty())
    }

    @Test
    fun `the higher ID's retry note shows its real wait`() {
        b.manager.onFound(a.endpointId, a.name)
        radio.advance(30_000)
        radio.dropQueued()
        b.manager.onRequestFailed(a.endpointId, 8012)

        assertEquals(listOf("Link request to ${a.id.short} failed (status 8012) · retrying in 30 s"), b.notes)
    }
}
