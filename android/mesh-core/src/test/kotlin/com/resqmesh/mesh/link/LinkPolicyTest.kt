package com.resqmesh.mesh.link

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.crypto.NodeId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkPolicyTest {
    private val low = NodeId("1000000000000000")
    private val high = NodeId("F000000000000000")
    private val clock = FixedClock(1_000_000)

    /** Exact timings: no jitter. */
    private fun policy(local: NodeId) = LinkPolicy(local, clock, jitter = 0.0)

    @Test
    fun `the lower node ID asks at once`() {
        assertEquals(LinkDecision.Request, policy(low).decide(high, firstSeenAtMillis = clock.now, currentLinks = 0))
    }

    @Test
    fun `the higher node ID waits, then asks if the other side never did`() {
        val policy = policy(high)
        val seenAt = clock.now

        assertEquals(LinkDecision.WaitUntil(seenAt + 30_000), policy.decide(low, seenAt, 0))
        clock.now = seenAt + 30_000
        assertEquals(LinkDecision.Request, policy.decide(low, seenAt, 0))
    }

    @Test
    fun `the higher node ID keeps waiting while the other side is trying`() {
        val policy = policy(high)
        val seenAt = clock.now

        clock.now = seenAt + 29_000
        policy.onInboundAttempt(low)
        clock.now = seenAt + 30_000

        assertEquals(LinkDecision.WaitUntil(seenAt + 59_000), policy.decide(low, seenAt, 0))
    }

    @Test
    fun `after a failure the higher node ID waits the whole fallback, not just the backoff`() {
        val policy = policy(high)
        policy.onFailedOrDropped(low)

        assertEquals(LinkDecision.WaitUntil(clock.now + 30_000), policy.decide(low, clock.now - 60_000, 0))
    }

    @Test
    fun `changing the chain rules gives the lower node ID the first turn again`() {
        val policy = policy(high)
        val seenAt = clock.now
        clock.now = seenAt + 60_000
        assertEquals(LinkDecision.Request, policy.decide(low, seenAt, 0))

        policy.allowOnly = null
        assertEquals(LinkDecision.WaitUntil(clock.now + 30_000), policy.decide(low, seenAt, 0))
    }

    @Test
    fun `never more than the link cap`() {
        val policy = policy(low)

        assertEquals(LinkDecision.Skip(SkipReason.LINKS_FULL), policy.decide(high, clock.now, currentLinks = 4))
        assertTrue(policy.canAccept(high, 3))
        assertFalse(policy.canAccept(high, 4))
    }

    @Test
    fun `chain mode links only to allowed phones, both ways`() {
        val other = NodeId("8000000000000000")
        val policy = policy(low)
        policy.allowOnly = setOf(high)

        assertEquals(LinkDecision.Request, policy.decide(high, clock.now, 0))
        assertEquals(LinkDecision.Skip(SkipReason.NOT_ALLOWED), policy.decide(other, clock.now, 0))
        assertTrue(policy.canAccept(high, 0))
        assertFalse(policy.canAccept(other, 0))

        policy.allowOnly = null
        assertTrue(policy.canAccept(other, 0))
    }

    @Test
    fun `never links to itself`() {
        assertEquals(LinkDecision.Skip(SkipReason.SELF), policy(low).decide(low, clock.now, 0))
    }

    @Test
    fun `failures back off 2, 4, 8 seconds up to 60`() {
        val policy = policy(low)
        val waits = mutableListOf<Long>()
        repeat(7) {
            policy.onFailedOrDropped(high)
            val decision = policy.decide(high, clock.now, 0) as LinkDecision.WaitUntil
            waits += decision.atMillis - clock.now
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun `jitter spreads each wait by up to a quarter either way`() {
        val policy = LinkPolicy(low, clock, random = Random(42))
        val waits = List(200) {
            policy.onConnected(high)
            policy.onFailedOrDropped(high)
            (policy.decide(high, clock.now, 0) as LinkDecision.WaitUntil).atMillis - clock.now
        }
        assertTrue(waits.all { it in 1_500L..2_500L }, waits.toString())
        assertTrue(waits.toSet().size > 50, "waits should differ from one another")
    }

    @Test
    fun `asks again once the backoff has passed`() {
        val policy = policy(low)
        policy.onFailedOrDropped(high)

        clock.now += 2_000
        assertEquals(LinkDecision.Request, policy.decide(high, clock.now, 0))
    }

    @Test
    fun `a good link resets the backoff`() {
        val policy = policy(low)
        repeat(3) { policy.onFailedOrDropped(high) }
        policy.onConnected(high)

        assertEquals(LinkDecision.Request, policy.decide(high, clock.now, 0))
        policy.onFailedOrDropped(high)
        assertEquals(LinkDecision.WaitUntil(clock.now + 2_000), policy.decide(high, clock.now, 0))
    }
}
