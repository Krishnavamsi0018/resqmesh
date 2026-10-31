package com.resqmesh.mesh.gateway

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.AckKind
import com.resqmesh.mesh.packet.SosBody
import kotlin.random.Random

/** One SOS this phone may text, with the hop count it arrived with (shown in the SMS). */
class SmsJob(val sos: SosBody, val hops: Int) {
    val id: String get() = sos.id
    internal var readyAt = 0L
    internal var attempts = 0
    internal var sending = false
}

/**
 * Decides which SOS this phone texts to the rescue team, and when (F-41, TRD 2.7), kept free of
 * Android so it can be tested.
 *
 * Any phone with cellular service may text an SOS it holds, so the mesh tries not to text the same
 * SOS twice: each phone waits a random [minWaitMs]–[maxWaitMs] first and skips the SOS if a gateway
 * ack saying it was texted arrives meanwhile. The wait restarts whenever service comes back, so
 * phones that regain signal together don't all send at once. Limits: one SMS per SOS per phone,
 * [maxPerHour] per phone, none for test SOS or cancelled ones. Not thread-safe.
 */
class SmsEscalator(
    private val clock: Clock,
    private val random: Random = Random.Default,
    private val minWaitMs: Long = 5_000,
    private val maxWaitMs: Long = 20_000,
    private val maxPerHour: Int = 10,
    private val retryMs: Long = 60_000,
    private val maxAttempts: Int = 3,
) {
    private val pending = LinkedHashMap<String, SmsJob>()
    /** SOS that need no SMS from this phone: texted by anyone, cancelled, or given up on. */
    private val settled = HashSet<String>()
    private val sentAt = ArrayDeque<Long>()

    val pendingCount: Int get() = pending.size

    /** SMS this phone sent in the last hour. */
    val sentLastHour: Int get() = sentAt.also { prune() }.size

    /** An SOS this phone made or accepted. */
    fun offer(sos: SosBody, hops: Int) {
        if (sos.test || sos.id in settled || sos.id in pending || sos.exp <= clock.nowMillis()) return
        pending[sos.id] = SmsJob(sos, hops).also { it.readyAt = clock.nowMillis() + waitMs() }
    }

    /** A gateway ack: if some phone already texted this SOS, don't text it again. */
    fun onAck(ack: AckBody) {
        if (ack.kind == AckKind.GATEWAY && ack.sms == true) settle(ack.ref)
    }

    /** The sender cancelled: nobody needs a text any more. */
    fun onCancel(sosId: String) = settle(sosId)

    /** Cellular service came or went. When it comes back, every waiting SOS waits again. */
    fun onServiceChanged(available: Boolean) {
        if (!available) return
        val now = clock.nowMillis()
        pending.values.filter { !it.sending }.forEach { it.readyAt = now + waitMs() }
    }

    /** SOS to text now, within the hourly limit. Each is marked as sending until [onResult]. */
    fun due(): List<SmsJob> {
        val now = clock.nowMillis()
        pending.values.filter { it.sos.exp <= now && !it.sending }.forEach { settle(it.id) }
        var budget = maxPerHour - sentLastHour - pending.values.count { it.sending }
        val ready = mutableListOf<SmsJob>()
        for (job in pending.values) {
            if (budget <= 0) break
            if (job.sending || job.readyAt > now) continue
            job.sending = true
            ready += job
            budget--
        }
        return ready
    }

    /**
     * The phone reported whether the SMS for [sosId] went out. Returns true when it did, so the
     * caller floods a gateway ack with sms=true and other phones stop waiting to text it.
     */
    fun onResult(sosId: String, sent: Boolean): Boolean {
        val job = pending[sosId]?.takeIf { it.sending } ?: return false
        job.sending = false
        // Another phone's ack may have arrived while this SMS was going out; then the mesh already knows.
        val textedElsewhere = sosId in settled
        if (sent) {
            sentAt.addLast(clock.nowMillis())
            settle(sosId)
            return !textedElsewhere
        }
        job.attempts++
        if (textedElsewhere || job.attempts >= maxAttempts) settle(sosId) else job.readyAt = clock.nowMillis() + retryMs
        return false
    }

    /** No more SMS for this SOS from this phone; one already going out still reports its result. */
    private fun settle(sosId: String) {
        settled += sosId
        if (pending[sosId]?.sending != true) pending.remove(sosId)
    }

    private fun prune() {
        val hourAgo = clock.nowMillis() - HOUR_MS
        while (sentAt.isNotEmpty() && sentAt.first() <= hourAgo) sentAt.removeFirst()
    }

    private fun waitMs(): Long = random.nextLong(minWaitMs, maxWaitMs + 1)

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
