package com.resqmesh.mesh.link

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId

sealed interface LinkDecision {
    data object Request : LinkDecision

    /** Ask again at [atMillis]; nothing to do before then. */
    data class WaitUntil(val atMillis: Long) : LinkDecision

    data class Skip(val reason: SkipReason) : LinkDecision
}

enum class SkipReason { LINKS_FULL, SELF }

/**
 * Decides when this phone asks a discovered phone for a link (TRD 2.6).
 *
 * Both phones discover each other, so only one should ask: the one with the lower node ID. If the
 * other side never saw us (discovery is not always symmetric), the higher one asks after
 * [fallbackAfterMs]. A failed or dropped link is retried with backoff: 2, 4, 8 … 60 s.
 * Not thread-safe; the app calls it from the main thread, where Nearby delivers its callbacks.
 */
class LinkPolicy(
    private val local: NodeId,
    private val clock: Clock,
    val maxLinks: Int = 4,
    private val fallbackAfterMs: Long = 10_000,
    private val minBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 60_000,
) {
    private val failures = HashMap<NodeId, Int>()
    private val retryAt = HashMap<NodeId, Long>()

    fun decide(remote: NodeId, firstSeenAtMillis: Long, currentLinks: Int): LinkDecision {
        if (remote == local) return LinkDecision.Skip(SkipReason.SELF)
        if (currentLinks >= maxLinks) return LinkDecision.Skip(SkipReason.LINKS_FULL)

        val now = clock.nowMillis()
        val retry = retryAt[remote]
        if (retry != null && now < retry) return LinkDecision.WaitUntil(retry)

        if (local.hex < remote.hex) return LinkDecision.Request
        val fallbackAt = firstSeenAtMillis + fallbackAfterMs
        return if (now >= fallbackAt) LinkDecision.Request else LinkDecision.WaitUntil(fallbackAt)
    }

    /** Whether to accept a link the other phone asked for. */
    fun canAccept(currentLinks: Int): Boolean = currentLinks < maxLinks

    fun onConnected(remote: NodeId) {
        failures.remove(remote)
        retryAt.remove(remote)
    }

    /** A request failed or a live link dropped: wait longer before each new attempt. */
    fun onFailedOrDropped(remote: NodeId) {
        val count = (failures[remote] ?: 0) + 1
        failures[remote] = count
        val delay = (minBackoffMs shl (count - 1).coerceAtMost(30)).coerceAtMost(maxBackoffMs)
        retryAt[remote] = clock.nowMillis() + delay
    }
}
