package com.resqmesh.mesh.link

import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.crypto.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkPolicyTest {
    private val low = NodeId("1000000000000000")
    private val high = NodeId("F000000000000000")
    private val clock = FixedClock(1_000_000)

    @Test
    fun `the lower node ID asks at once`() {
        val policy = LinkPolicy(low, clock)
        assertEquals(LinkDecision.Request, policy.decide(high, firstSeenAtMillis = clock.now, currentLinks = 0))
    }

    @Test
    fun `the higher node ID waits, then asks if the other side never did`() {
        val policy = LinkPolicy(high, clock, fallbackAfterMs = 10_000)
        val seenAt = clock.now

        assertEquals(LinkDecision.WaitUntil(seenAt + 10_000), policy.decide(low, seenAt, 0))
        clock.now = seenAt + 10_000
        assertEquals(LinkDecision.Request, policy.decide(low, seenAt, 0))
    }

    @Test
    fun `never more than the link cap`() {
        val policy = LinkPolicy(low, clock, maxLinks = 4)

        assertEquals(LinkDecision.Skip(SkipReason.LINKS_FULL), policy.decide(high, clock.now, currentLinks = 4))
        assertTrue(policy.canAccept(3))
        assertFalse(policy.canAccept(4))
    }

    @Test
    fun `never links to itself`() {
        assertEquals(LinkDecision.Skip(SkipReason.SELF), LinkPolicy(low, clock).decide(low, clock.now, 0))
    }

    @Test
    fun `failures back off 2, 4, 8 seconds up to 60`() {
        val policy = LinkPolicy(low, clock)
        val waits = mutableListOf<Long>()
        repeat(7) {
            policy.onFailedOrDropped(high)
            val decision = policy.decide(high, clock.now, 0) as LinkDecision.WaitUntil
            waits += decision.atMillis - clock.now
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun `asks again once the backoff has passed`() {
        val policy = LinkPolicy(low, clock)
        policy.onFailedOrDropped(high)

        clock.now += 2_000
        assertEquals(LinkDecision.Request, policy.decide(high, clock.now, 0))
    }

    @Test
    fun `a good link resets the backoff`() {
        val policy = LinkPolicy(low, clock)
        repeat(3) { policy.onFailedOrDropped(high) }
        policy.onConnected(high)

        assertEquals(LinkDecision.Request, policy.decide(high, clock.now, 0))
        policy.onFailedOrDropped(high)
        assertEquals(LinkDecision.WaitUntil(clock.now + 2_000), policy.decide(high, clock.now, 0))
    }
}
