package com.resqmesh.mesh.link

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import kotlin.math.roundToLong
import kotlin.random.Random

sealed interface LinkDecision {
    data object Request : LinkDecision

    /** Ask again at [atMillis]; nothing to do before then. */
    data class WaitUntil(val atMillis: Long) : LinkDecision

    data class Skip(val reason: SkipReason) : LinkDecision
}

enum class SkipReason { LINKS_FULL, SELF, NOT_ALLOWED }

/**
 * Decides when this phone asks a discovered phone for a link (TRD 2.6).
 *
 * Both phones discover each other, so only one should ask: the one with the lower node ID. The
 * higher one asks only if the other has been quiet for [fallbackAfterMs] (discovery is not always
 * symmetric); while the other side keeps trying, it waits. If both ask at once over Bluetooth, both
 * requests fail (8012), and in the four-phone test a request on a busy phone took 13 s to fail, so
 * the fallback is 30 s, and it restarts whenever the chain rules change. A failed or dropped link
 * is retried after 2, 4, 8 … 60 s. Every wait is spread by ±[jitter] so two phones stop retrying in
 * lockstep, which is what kept colliding in the first three-phone test.
 *
 * Chain test mode (F-61): when [allowOnly] is set, this phone links only to those phones, so
 * phones lying side by side on a table still form a line A–B–C–D.
 * Not thread-safe; the app calls it from the main thread, where Nearby delivers its callbacks.
 */
class LinkPolicy(
    private val local: NodeId,
    private val clock: Clock,
    val maxLinks: Int = 4,
    private val fallbackAfterMs: Long = 30_000,
    private val minBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 60_000,
    private val jitter: Double = 0.25,
    private val random: Random = Random.Default,
) {
    private val failures = HashMap<NodeId, Int>()
    private val retryAt = HashMap<NodeId, Long>()
    /** Last attempt by the other phone, or last failure or drop: the higher-ID side's fallback counts from here. */
    private val lastActivity = HashMap<NodeId, Long>()
    private val fallbackSpread = HashMap<NodeId, Long>()
    private var rulesChangedAt = Long.MIN_VALUE

    /**
     * Null: link to anyone. Otherwise only these phones (chain test mode). Changing it restarts the
     * higher-ID side's fallback wait: when every phone switches chain mode off at once, the lower-ID
     * phones ask straight away, and the higher ones must not ask at the same moment.
     */
    var allowOnly: Set<NodeId>? = null
        set(value) {
            field = value
            rulesChangedAt = clock.nowMillis()
        }

    fun isAllowed(remote: NodeId): Boolean = allowOnly?.contains(remote) ?: true

    fun decide(remote: NodeId, firstSeenAtMillis: Long, currentLinks: Int): LinkDecision {
        if (remote == local) return LinkDecision.Skip(SkipReason.SELF)
        if (!isAllowed(remote)) return LinkDecision.Skip(SkipReason.NOT_ALLOWED)
        if (currentLinks >= maxLinks) return LinkDecision.Skip(SkipReason.LINKS_FULL)

        var readyAt = retryAt[remote] ?: Long.MIN_VALUE
        if (local.hex > remote.hex) {
            val quietSince = maxOf(firstSeenAtMillis, lastActivity[remote] ?: Long.MIN_VALUE, rulesChangedAt)
            val spread = fallbackSpread.getOrPut(remote) { (fallbackAfterMs * jitter * random.nextDouble()).roundToLong() }
            readyAt = maxOf(readyAt, quietSince + fallbackAfterMs + spread)
        }
        return if (clock.nowMillis() >= readyAt) LinkDecision.Request else LinkDecision.WaitUntil(readyAt)
    }

    /** Whether to accept a link the other phone asked for. */
    fun canAccept(remote: NodeId, currentLinks: Int): Boolean = isAllowed(remote) && currentLinks < maxLinks

    /** The other phone just tried to link: it can see us, so the higher-ID side keeps waiting for it. */
    fun onInboundAttempt(remote: NodeId) {
        lastActivity[remote] = clock.nowMillis()
    }

    fun onConnected(remote: NodeId) {
        failures.remove(remote)
        retryAt.remove(remote)
        lastActivity.remove(remote)
    }

    /**
     * A request failed or a live link dropped: wait longer before each new attempt. The higher-ID
     * side also restarts its fallback wait, so after a drop the lower-ID side gets the first try.
     */
    fun onFailedOrDropped(remote: NodeId) {
        lastActivity[remote] = clock.nowMillis()
        val count = (failures[remote] ?: 0) + 1
        failures[remote] = count
        val base = (minBackoffMs shl (count - 1).coerceAtMost(30)).coerceAtMost(maxBackoffMs)
        val factor = 1 + jitter * (2 * random.nextDouble() - 1)
        retryAt[remote] = clock.nowMillis() + (base * factor).roundToLong()
    }
}
