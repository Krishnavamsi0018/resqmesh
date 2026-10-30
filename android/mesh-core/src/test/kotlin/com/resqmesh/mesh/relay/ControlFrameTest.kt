package com.resqmesh.mesh.relay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlFrameTest {
    private fun key(n: Int) = PacketKey.fromBytes(ByteArray(PacketKey.SIZE) { (n + it).toByte() })

    @Test
    fun `summary and request round-trip`() {
        val keys = List(3) { key(it * 16) }
        for (frame in listOf(ControlFrame.Summary(keys), ControlFrame.Request(keys), ControlFrame.Summary(emptyList()))) {
            assertEquals(frame, ControlFrame.decode(ControlFrame.encode(frame)))
        }
    }

    @Test
    fun `header follows the documented layout`() {
        val bytes = ControlFrame.encode(ControlFrame.Request(listOf(key(0))))
        assertContentEquals(byteArrayOf(0x52, 0x43, 1, 2, 0, 1), bytes.copyOf(6))
        assertEquals(6 + 16, bytes.size)
    }

    @Test
    fun `500 keys fit in one frame, 501 do not`() {
        val most = List(ControlFrame.MAX_KEYS) { key(it) }
        assertEquals(most, ControlFrame.decode(ControlFrame.encode(ControlFrame.Summary(most)))?.keys)
        assertFailsWith<IllegalArgumentException> { ControlFrame.encode(ControlFrame.Summary(most + key(0))) }
    }

    @Test
    fun `control frames and packets are told apart by their first bytes`() {
        assertTrue(ControlFrame.isControl(ControlFrame.encode(ControlFrame.Summary(emptyList()))))
        assertFalse(ControlFrame.isControl(byteArrayOf(0x52, 0x51, 1)))
    }

    @Test
    fun `malformed frames decode to null`() {
        val good = ControlFrame.encode(ControlFrame.Summary(listOf(key(0))))
        assertNull(ControlFrame.decode(good.copyOf(good.size - 1)))
        assertNull(ControlFrame.decode(good.copyOf().also { it[2] = 2 }))
        assertNull(ControlFrame.decode(good.copyOf().also { it[3] = 9 }))
        assertNull(ControlFrame.decode(byteArrayOf(0x52, 0x43)))
    }
}
