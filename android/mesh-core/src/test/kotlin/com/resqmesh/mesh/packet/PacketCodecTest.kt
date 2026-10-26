package com.resqmesh.mesh.packet

import com.resqmesh.mesh.Protocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PacketCodecTest {
    private fun packet(type: PacketType = PacketType.ACK, ttl: Int = 5, hops: Int = 3, bodySize: Int = 10) =
        Packet(type, ttl, hops, ByteArray(bodySize) { 'a'.code.toByte() }, ByteArray(Protocol.SIGNATURE_SIZE) { 7 })

    private fun assertMalformed(expected: MalformedReason, bytes: ByteArray) {
        val e = assertFailsWith<MalformedPacketException> { PacketCodec.decode(bytes) }
        assertEquals(expected, e.reason)
    }

    @Test
    fun `header bytes follow the TRD layout`() {
        val bytes = PacketCodec.encode(packet(bodySize = 300))

        assertEquals(8 + 300 + 64, bytes.size)
        assertContentEquals(byteArrayOf(0x52, 0x51, 1, 3, 5, 3, 0x01, 0x2C), bytes.copyOfRange(0, 8))
        assertEquals('a'.code.toByte(), bytes[8])
        assertEquals(7.toByte(), bytes.last())
    }

    @Test
    fun `decode reverses encode`() {
        for (type in PacketType.entries) {
            val original = packet(type = type, ttl = 8, hops = 0, bodySize = Protocol.MAX_BODY_SIZE)
            assertEquals(original, PacketCodec.decode(PacketCodec.encode(original)))
        }
    }

    @Test
    fun `largest packet fits in 1 KB`() {
        assertEquals(Protocol.MAX_PACKET_SIZE, PacketCodec.encode(packet(bodySize = Protocol.MAX_BODY_SIZE)).size)
        assertEquals(true, Protocol.MAX_PACKET_SIZE <= 1024)
    }

    @Test
    fun `rejects buffers that are too short or too long`() {
        assertMalformed(MalformedReason.TOO_SHORT, ByteArray(72))
        assertMalformed(MalformedReason.TOO_LONG, ByteArray(Protocol.MAX_PACKET_SIZE + 1))
    }

    @Test
    fun `rejects a wrong magic, version or type`() {
        val good = PacketCodec.encode(packet())

        assertMalformed(MalformedReason.BAD_MAGIC, good.copyOf().also { it[0] = 'X'.code.toByte() })
        assertMalformed(MalformedReason.UNSUPPORTED_VERSION, good.copyOf().also { it[2] = 2 })
        assertMalformed(MalformedReason.UNKNOWN_TYPE, good.copyOf().also { it[3] = 0 })
        // 4 is reserved for SAFE, which is not in the MVP (D7).
        assertMalformed(MalformedReason.UNKNOWN_TYPE, good.copyOf().also { it[3] = 4 })
    }

    @Test
    fun `rejects a body length of zero or over the limit`() {
        val zeroLength = ByteArray(8 + 1 + 64).also {
            it[0] = 0x52; it[1] = 0x51; it[2] = 1; it[3] = 1; it[4] = 8
        }
        assertMalformed(MalformedReason.BAD_BODY_LENGTH, zeroLength)

        val tooLong = PacketCodec.encode(packet(bodySize = Protocol.MAX_BODY_SIZE)).also {
            it[6] = (901 ushr 8).toByte(); it[7] = 901.toByte()
        }
        assertMalformed(MalformedReason.BAD_BODY_LENGTH, tooLong)
    }

    @Test
    fun `rejects truncated or padded packets`() {
        val good = PacketCodec.encode(packet())

        assertMalformed(MalformedReason.LENGTH_MISMATCH, good.copyOf(good.size - 1))
        assertMalformed(MalformedReason.LENGTH_MISMATCH, good + byteArrayOf(0))
    }

    @Test
    fun `forwarding lowers ttl and raises hops, nothing else`() {
        val original = packet(ttl = 8, hops = 0)
        val next = original.forwarded()

        assertEquals(7, next.ttl)
        assertEquals(1, next.hops)
        assertContentEquals(original.body, next.body)
        assertContentEquals(original.signature, next.signature)
    }

    @Test
    fun `a packet with ttl 1 cannot be forwarded`() {
        assertFailsWith<IllegalStateException> { packet(ttl = 1).forwarded() }
    }

    @Test
    fun `packet refuses a wrong-size signature or empty body`() {
        assertFailsWith<IllegalArgumentException> { Packet(PacketType.SOS, 8, 0, ByteArray(10), ByteArray(63)) }
        assertFailsWith<IllegalArgumentException> { Packet(PacketType.SOS, 8, 0, ByteArray(0), ByteArray(64)) }
    }
}
