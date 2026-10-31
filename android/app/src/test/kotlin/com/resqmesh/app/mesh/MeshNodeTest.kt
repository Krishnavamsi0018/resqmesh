package com.resqmesh.app.mesh

import com.resqmesh.app.experiment.ExperimentEvent
import com.resqmesh.app.experiment.ExperimentEventKind
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.gateway.UploadStatus
import com.resqmesh.mesh.packet.AckBody
import com.resqmesh.mesh.packet.BodyJson
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.PacketFactory
import com.resqmesh.mesh.packet.PacketValidator
import com.resqmesh.mesh.packet.ResponderCodes
import com.resqmesh.mesh.relay.Links
import com.resqmesh.mesh.sos.SosStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phones wired back to back: everything except the radio. */
class MeshNodeTest {
    private var now = 1_793_000_000_000L
    private val clock = Clock { now }
    private val server = NodeKeys.fromPrivateKey(ByteArray(32) { (0x81 + it).toByte() })

    private inner class Phone(seed: Int) : Links {
        val state = MeshState()
        val node = MeshNode(NodeKeys.fromPrivateKey(ByteArray(32) { (seed + it).toByte() }), PacketValidator(server.publicKey, clock), state, clock)
        val neighbours = LinkedHashMap<NodeId, MeshNode>()

        init {
            node.links = this
        }

        override fun linked(): Set<NodeId> = neighbours.keys

        override fun send(to: NodeId, bytes: ByteArray) {
            neighbours.getValue(to).onBytes(node.id, bytes)
        }
    }

    private fun link(a: Phone, b: Phone) {
        a.neighbours[b.node.id] = b.node
        b.neighbours[a.node.id] = a.node
    }

    /** Captures what a phone sends instead of delivering it. */
    private fun capturing(phone: Phone, to: NodeId): () -> ByteArray {
        var sent: ByteArray? = null
        phone.node.links = object : Links {
            override fun linked() = setOf(to)

            override fun send(to: NodeId, bytes: ByteArray) {
                sent = bytes
            }
        }
        return { assertNotNull(sent) }
    }

    @Test
    fun `a test SOS sent by one phone is accepted by the other`() {
        val (a, b) = Phone(1) to Phone(2)
        link(a, b)

        a.node.sendTestSos(Category.FLOOD)

        assertEquals(1, a.state.counters.value.sent)
        assertEquals(1, b.state.counters.value.accepted)
        val entry = b.state.log.value.first()
        assertEquals(LogKind.ACCEPTED, entry.kind)
        assertTrue(entry.text.startsWith("SOS FLOOD"), entry.text)
        assertTrue(entry.text.contains("hops 0"), entry.text)
    }

    @Test
    fun `the middle phone forwards to the far phone`() {
        val (a, b, c) = listOf(Phone(1), Phone(2), Phone(3))
        link(a, b)
        link(b, c)

        a.node.sendTestSos(Category.MEDICAL)

        assertEquals(1, b.state.counters.value.forwarded)
        assertTrue(b.state.log.value.any { it.kind == LogKind.FORWARDED && it.text.contains(c.node.id.short) })
        val atC = c.state.log.value.single { it.kind == LogKind.ACCEPTED }.text
        assertTrue(atC.contains("from ${a.node.id.short} via ${b.node.id.short} · hops 1"), atC)
        assertEquals(0, a.state.counters.value.accepted)
    }

    @Test
    fun `the same packet twice counts once plus a duplicate`() {
        val (a, b) = Phone(1) to Phone(2)
        val sent = capturing(a, b.node.id)
        a.node.sendTestSos(Category.MEDICAL)

        b.node.onBytes(a.node.id, sent())
        b.node.onBytes(a.node.id, sent())

        assertEquals(1, b.state.counters.value.accepted)
        assertEquals(1, b.state.counters.value.duplicates)
    }

    @Test
    fun `a phone ignores its own SOS when it comes back`() {
        val (a, b) = Phone(1) to Phone(2)
        val sent = capturing(a, b.node.id)
        a.node.sendTestSos(Category.FIRE)

        a.node.onBytes(b.node.id, sent())

        assertEquals(0, a.state.counters.value.accepted)
        assertEquals(1, a.state.counters.value.duplicates)
    }

    @Test
    fun `tampered and garbage bytes are dropped`() {
        val (a, b) = Phone(1) to Phone(2)
        val sent = capturing(a, b.node.id)
        a.node.sendTestSos(Category.FLOOD)
        val tampered = sent().copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        b.node.onBytes(a.node.id, tampered)
        b.node.onBytes(a.node.id, byteArrayOf(1, 2, 3))

        assertEquals(0, b.state.counters.value.accepted)
        assertEquals(2, b.state.counters.value.rejected)
        assertTrue(b.state.log.value.any { it.text.contains("INVALID: BAD_SIGNATURE") })
        assertTrue(b.state.log.value.any { it.text.contains("MALFORMED: TOO_SHORT") })
    }

    @Test
    fun `an SOS sent with nobody around is held for later`() {
        val a = Phone(1)

        a.node.sendTestSos(Category.WATER)

        assertTrue(a.state.log.value.first().text.endsWith("to 0 phone(s)"))
        assertEquals(1, a.state.held.value)
    }

    @Test
    fun `a gateway uploads what it relays, informs the sender, and floods the server's reply back`() {
        val (sender, gateway) = Phone(1) to Phone(2)
        link(sender, gateway)
        sender.node.sendSos(Category.FLOOD, location = null, batteryPercent = 50, people = 2, lang = "kn")

        // The gateway queued the SOS it accepted; the server confirms it.
        val batch = gateway.node.uploadBatch()
        assertEquals(1, batch.size)
        gateway.node.onUploaded(batch, listOf(UploadStatus.STORED))
        assertEquals(SosStep.NOTIFIED, sender.state.mySos.value!!.step, "the gateway ack reached the sender")

        // An operator replies; the gateway pulls it from the server and floods it to the sender.
        val sosId = sender.state.mySos.value!!.id
        assertEquals(listOf(sosId), gateway.node.watchedSos())
        val reply = PacketFactory(server, clock).responderAck(sosId, ResponderCodes.TEAM_DISPATCHED, unit = "NDRF 4")
        gateway.node.fromServer(PacketCodec.encode(reply))
        gateway.node.fromServer(PacketCodec.encode(reply)) // pulled again 30 s later: ignored

        val mine = sender.state.mySos.value!!
        assertEquals(SosStep.ACKED, mine.step)
        assertEquals("NDRF 4", mine.reply?.unit)
        assertEquals(0, gateway.state.counters.value.duplicates)
    }

    @Test
    fun `one phone texts the SOS, the mesh learns it, and nobody texts it again`() {
        val (a, b) = Phone(1) to Phone(2)
        link(a, b)
        a.node.sendSos(Category.FIRE, location = null, batteryPercent = 50, people = 2, lang = "kn")
        assertTrue(b.node.smsDue().isEmpty(), "waits 5 to 20 s first")

        now += 20_000
        val job = b.node.smsDue().single()
        assertEquals(a.state.mySos.value!!.id, job.id)
        assertEquals(0, job.hops)
        b.node.onSmsResult(job.id, sent = true)

        val mine = a.state.mySos.value!!
        assertNotNull(mine.textedAt, "the sender sees it was texted")
        assertEquals(SosStep.NOTIFIED, mine.step)
        now += 60_000
        assertTrue(a.node.smsDue().isEmpty(), "the sender's own phone no longer needs to text it")
        val upload = b.node.uploadBatch().map { BodyJson.decode(PacketCodec.decode(it.envelope).body) }
        assertTrue(upload.any { it is AckBody && it.sms == true }, "the server learns it was texted")
    }

    @Test
    fun `a failed SMS leaves the sender's steps alone`() {
        val (a, b) = Phone(1) to Phone(2)
        link(a, b)
        a.node.sendSos(Category.FIRE, location = null, batteryPercent = 50, people = 2, lang = "en")
        now += 20_000
        b.node.onSmsResult(b.node.smsDue().single().id, sent = false)

        assertNull(a.state.mySos.value!!.textedAt)
        assertEquals(LogKind.ERROR, b.state.log.value.first().kind)
    }

    @Test
    fun `in an experiment the far phone echoes each test SOS and the sender times the round trip`() {
        val (a, b, c) = listOf(Phone(1), Phone(2), Phone(3))
        link(a, b)
        link(b, c)
        val events = mutableMapOf<MeshNode, MutableList<ExperimentEvent>>()
        for (p in listOf(a, b, c)) p.node.onExperimentEvent = { events.getOrPut(p.node) { mutableListOf() } += it }
        c.node.echoTestSos = true

        val id = a.node.sendTestSos(Category.FLOOD)

        val atC = events.getValue(c.node).first { it.kind == ExperimentEventKind.RECEIVED }
        assertEquals(id, atC.sosId)
        assertEquals(2, atC.hops, "radio hops A to B to C")
        val echo = events.getValue(a.node).single { it.kind == ExperimentEventKind.ECHO_RECEIVED }
        assertEquals(id, echo.sosId)
        assertEquals(0L, echo.rttMs, "the test clock stands still")
        assertEquals(1, events.getValue(b.node).count { it.kind == ExperimentEventKind.TX && it.sosId == id }, "B relayed it once")
        assertTrue(events.getValue(b.node).none { it.kind == ExperimentEventKind.ECHO_SENT }, "only the far phone echoes")
    }

    @Test
    fun `an online sender informs itself straight away`() {
        val phone = Phone(1)
        phone.node.sendSos(Category.MEDICAL, location = null, batteryPercent = null, people = 1, lang = "en")

        phone.node.onUploaded(phone.node.uploadBatch(), listOf(UploadStatus.STORED))

        assertEquals(SosStep.NOTIFIED, phone.state.mySos.value!!.step)
    }
}
