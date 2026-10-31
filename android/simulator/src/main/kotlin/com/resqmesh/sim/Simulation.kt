package com.resqmesh.sim

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.Packet
import com.resqmesh.mesh.packet.PacketBody
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.packet.SosBody
import com.resqmesh.mesh.relay.ControlFrame
import com.resqmesh.mesh.relay.Links
import com.resqmesh.mesh.relay.Router
import com.resqmesh.mesh.relay.RouterConfig
import com.resqmesh.mesh.relay.RouterEvents
import java.util.PriorityQueue
import kotlin.random.Random

/** The relay scheme a run uses: ours, or the plain-flooding baseline (no store-carry-forward). */
enum class Scheme(val label: String, val config: RouterConfig) {
    RESQMESH("ResQMesh", RouterConfig()),
    FLOODING("Plain flooding", RouterConfig(storeCarryForward = false)),
}

/**
 * One simulated disaster area (plan doc, evaluation): [nodes] phones walking in an [areaM] square
 * with random-waypoint movement, linking to phones within [rangeM] after [connectDelayS] (Nearby's
 * connection setup). [gatewayShare] of them have signal; an SOS is delivered when any of those holds
 * it. [sosCount] SOS leave from random other phones between [sosFromS] and [sosUntilS].
 */
data class Scenario(
    val nodes: Int,
    val rangeM: Double,
    val scheme: Scheme,
    val seed: Long,
    val areaM: Double = 1000.0,
    val durationS: Int = 1800,
    val sosCount: Int = 20,
    val sosFromS: Int = 60,
    val sosUntilS: Int = 600,
    val gatewayShare: Double = 0.05,
    val connectDelayS: Int = 5,
    val minSpeed: Double = 0.5,
    val maxSpeed: Double = 1.5,
    val maxPauseS: Int = 60,
    val hopLatencyMs: Long = 150,
    val hopJitterMs: Long = 100,
)

/** What happened to one SOS: when a gateway first held it (null: never), after how many hops, and how many phones it reached. */
data class SosOutcome(val sentAtS: Double, val latencyMs: Long?, val hops: Int?, val reached: Int)

data class RunResult(
    val scenario: Scenario,
    val sos: List<SosOutcome>,
    /** SOS packets sent over links, including copies that turned out to be duplicates. */
    val dataTransmissions: Long,
    /** Summary and request frames (store-carry-forward's cost). */
    val controlTransmissions: Long,
    val bytes: Long,
    /** Links per phone, averaged over the run. */
    val meanDegree: Double,
) {
    val delivered: Int get() = sos.count { it.latencyMs != null }
    val deliveryRatio: Double get() = delivered.toDouble() / sos.size
}

/** Positions of every phone, one step per simulated second. */
interface Mobility {
    fun positions(timeS: Int): List<Pair<Double, Double>>
}

/** Random waypoint (walk to a random point at a random speed, pause, repeat), the usual model for people on foot. */
class RandomWaypoint(private val s: Scenario, private val rng: Random) : Mobility {
    private val x = DoubleArray(s.nodes) { rng.nextDouble(s.areaM) }
    private val y = DoubleArray(s.nodes) { rng.nextDouble(s.areaM) }
    private val toX = DoubleArray(s.nodes)
    private val toY = DoubleArray(s.nodes)
    private val speed = DoubleArray(s.nodes)
    private val pauseUntil = IntArray(s.nodes) { rng.nextInt(s.maxPauseS + 1) }

    init {
        repeat(s.nodes) { pickWaypoint(it) }
    }

    override fun positions(timeS: Int): List<Pair<Double, Double>> {
        if (timeS > 0) repeat(s.nodes) { move(it, timeS) }
        return List(s.nodes) { x[it] to y[it] }
    }

    private fun move(i: Int, timeS: Int) {
        if (timeS < pauseUntil[i]) return
        val dx = toX[i] - x[i]
        val dy = toY[i] - y[i]
        val left = Math.hypot(dx, dy)
        if (left <= speed[i]) {
            x[i] = toX[i]
            y[i] = toY[i]
            pauseUntil[i] = timeS + rng.nextInt(s.maxPauseS + 1)
            pickWaypoint(i)
        } else {
            x[i] += dx / left * speed[i]
            y[i] += dy / left * speed[i]
        }
    }

    private fun pickWaypoint(i: Int) {
        toX[i] = rng.nextDouble(s.areaM)
        toY[i] = rng.nextDouble(s.areaM)
        speed[i] = rng.nextDouble(s.minSpeed, s.maxSpeed)
    }
}

/**
 * Runs one [Scenario] on a simulated clock. Every phone runs mesh-core's real [Router] with real
 * Ed25519-signed packets; only the radio is simulated. Mobility, gateways and the SOS schedule come
 * from [Scenario.seed], so the two schemes are compared on exactly the same movement.
 */
class Simulation(
    private val s: Scenario,
    mobility: Mobility? = null,
    gateways: Set<Int>? = null,
    /** (second, phone) pairs; random when null. */
    private val senders: List<Pair<Int, Int>>? = null,
) {
    private var now = START
    private val clock = Clock { now }
    private val world = Random(s.seed)
    private val radio = Random(s.seed * 31 + 7)
    private val mobility = mobility ?: RandomWaypoint(s, world)
    private val gatewaySet = gateways ?: (0 until s.nodes).shuffled(world).take(maxOf(1, Math.round(s.nodes * s.gatewayShare).toInt())).toSet()
    private val serverKey = NodeKeys.fromPrivateKey(Random(s.seed - 1).nextBytes(32)).publicKey
    private val phones = List(s.nodes) { Phone(it) }
    private val byId = phones.associateBy { it.id }
    private val queue = PriorityQueue<Event>(compareBy<Event> { it.atMs }.thenBy { it.seq })
    private var seq = 0L

    private val inRangeSince = HashMap<Long, Int>()
    private val linked = HashSet<Long>()

    private class Tracked(val sentAtMs: Long) {
        var gatewayAtMs: Long? = null
        var hops: Int? = null
        val reached = HashSet<Int>()
    }
    private val tracked = LinkedHashMap<String, Tracked>()
    private var dataTx = 0L
    private var controlTx = 0L
    private var bytes = 0L
    private var degreeSum = 0L

    private sealed class Event(val atMs: Long, val seq: Long)
    private class Transfer(atMs: Long, seq: Long, val from: Int, val to: Int, val bytes: ByteArray) : Event(atMs, seq)
    private class SendSos(atMs: Long, seq: Long, val sender: Int) : Event(atMs, seq)

    private inner class Phone(val index: Int) : Links, RouterEvents {
        val keys = NodeKeys.fromPrivateKey(Random(s.seed * 100_003 + index).nextBytes(32))
        val id: NodeId = keys.nodeId
        val factory = PacketFactory(keys, clock)
        val neighbours = LinkedHashSet<NodeId>()
        val router = Router(PacketValidator(serverKey, clock), this, this, clock, s.scheme.config)

        override fun linked(): Set<NodeId> = neighbours

        override fun send(to: NodeId, bytes: ByteArray) = transmit(index, byId.getValue(to).index, bytes)

        override fun onDelivered(packet: Packet, body: PacketBody, from: NodeId) {
            // packet.hops counts the phones that relayed it; radio hops are one more.
            if (body is SosBody) received(index, body.id, packet.hops + 1)
        }
    }

    fun run(): RunResult {
        val sosSenders = senders ?: scheduleSos()
        sosSenders.forEach { (atS, sender) -> queue += SendSos(START + atS * 1000L + world.nextLong(1000), seq++, sender) }

        for (t in 0 until s.durationS) {
            now = START + t * 1000L
            updateLinks(t, mobility.positions(t))
            degreeSum += linked.size * 2L
            val end = START + (t + 1) * 1000L
            while (queue.isNotEmpty() && queue.peek().atMs < end) {
                val event = queue.poll()
                now = event.atMs
                when (event) {
                    is SendSos -> {
                        val packet = phones[event.sender].factory.sos(Category.GENERAL, location = null)
                        val id = BodyJson.decode(packet.body).id
                        tracked[id] = Tracked(now)
                        received(event.sender, id, 0)
                        phones[event.sender].router.originate(packet)
                    }
                    is Transfer -> if (pairKey(event.from, event.to) in linked) {
                        phones[event.to].router.onReceive(phones[event.from].id, event.bytes)
                    }
                }
            }
        }
        return RunResult(
            scenario = s,
            sos = tracked.values.map { SosOutcome((it.sentAtMs - START) / 1000.0, it.gatewayAtMs?.minus(it.sentAtMs), it.hops, it.reached.size) },
            dataTransmissions = dataTx,
            controlTransmissions = controlTx,
            bytes = bytes,
            meanDegree = degreeSum.toDouble() / s.nodes / s.durationS,
        )
    }

    /** Distinct non-gateway senders at random times, so no sender hits the per-sender rate limit. */
    private fun scheduleSos(): List<Pair<Int, Int>> {
        val candidates = (0 until s.nodes).filter { it !in gatewaySet }.shuffled(world)
        return List(minOf(s.sosCount, candidates.size)) { world.nextInt(s.sosFromS, s.sosUntilS + 1) to candidates[it] }
    }

    private fun received(phone: Int, sosId: String, hops: Int) {
        val sos = tracked[sosId] ?: return
        sos.reached += phone
        if (phone in gatewaySet && sos.gatewayAtMs == null) {
            sos.gatewayAtMs = now
            sos.hops = hops
        }
    }

    private fun transmit(from: Int, to: Int, payload: ByteArray) {
        if (ControlFrame.isControl(payload)) controlTx++ else dataTx++
        bytes += payload.size
        val at = now + s.hopLatencyMs + radio.nextLong(s.hopJitterMs + 1)
        queue += Transfer(at, seq++, from, to, payload)
    }

    /** Links come up after [Scenario.connectDelayS] in range (from the start, phones already in range are linked) and drop when out of range. */
    private fun updateLinks(t: Int, at: List<Pair<Double, Double>>) {
        val inRange = pairsInRange(at)
        inRangeSince.keys.retainAll(inRange)
        for (key in linked.toList()) {
            if (key !in inRange) {
                linked -= key
                val (a, b) = unpair(key)
                phones[a].neighbours -= phones[b].id
                phones[b].neighbours -= phones[a].id
            }
        }
        for (key in inRange) {
            val since = inRangeSince.getOrPut(key) { if (t == 0) -s.connectDelayS else t }
            if (key in linked || t - since < s.connectDelayS) continue
            linked += key
            val (a, b) = unpair(key)
            phones[a].neighbours += phones[b].id
            phones[b].neighbours += phones[a].id
            phones[a].router.onLinked(phones[b].id)
            phones[b].router.onLinked(phones[a].id)
        }
    }

    /** Pairs closer than the radio range, found through a grid of range-sized cells. */
    private fun pairsInRange(at: List<Pair<Double, Double>>): Set<Long> {
        val cell = s.rangeM
        val grid = HashMap<Long, MutableList<Int>>()
        at.forEachIndexed { i, (x, y) -> grid.getOrPut(cellKey((x / cell).toInt(), (y / cell).toInt())) { mutableListOf() } += i }
        val pairs = HashSet<Long>()
        val r2 = s.rangeM * s.rangeM
        at.forEachIndexed { i, (x, y) ->
            val cx = (x / cell).toInt()
            val cy = (y / cell).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                for (j in grid[cellKey(cx + dx, cy + dy)] ?: continue) {
                    if (j <= i) continue
                    val ddx = at[j].first - x
                    val ddy = at[j].second - y
                    if (ddx * ddx + ddy * ddy <= r2) pairs += pairKey(i, j)
                }
            }
        }
        return pairs
    }

    private fun pairKey(a: Int, b: Int): Long = if (a < b) a.toLong() * s.nodes + b else b.toLong() * s.nodes + a

    private fun unpair(key: Long): Pair<Int, Int> = (key / s.nodes).toInt() to (key % s.nodes).toInt()

    private fun cellKey(x: Int, y: Int): Long = x.toLong() * 1_000_003 + y

    private companion object {
        const val START = 1_793_000_000_000L
    }
}
