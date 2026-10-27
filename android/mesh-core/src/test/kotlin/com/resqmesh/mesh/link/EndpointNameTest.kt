package com.resqmesh.mesh.link

import com.resqmesh.mesh.crypto.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndpointNameTest {
    private val id = NodeId("65B60673A1B2C3D4")

    @Test
    fun `encodes node ID and flags`() {
        assertEquals("RQ1:65B60673A1B2C3D4:-", EndpointName(id).encode())
        assertEquals("RQ1:65B60673A1B2C3D4:G", EndpointName(id, gateway = true).encode())
        assertEquals("RQ1:65B60673A1B2C3D4:S", EndpointName(id, smsCapable = true).encode())
        assertEquals("RQ1:65B60673A1B2C3D4:GS", EndpointName(id, gateway = true, smsCapable = true).encode())
    }

    @Test
    fun `decode reverses encode`() {
        for (gateway in listOf(false, true)) {
            for (sms in listOf(false, true)) {
                val name = EndpointName(id, gateway, sms)
                assertEquals(name, EndpointName.decode(name.encode()))
            }
        }
    }

    @Test
    fun `names from other apps or broken names are ignored`() {
        assertNull(EndpointName.decode("Pixel 7"))
        assertNull(EndpointName.decode("RQ2:65B60673A1B2C3D4:-"))
        assertNull(EndpointName.decode("RQ1:65B60673A1B2C3D4"))
        assertNull(EndpointName.decode("RQ1:65b60673a1b2c3d4:-"))
        assertNull(EndpointName.decode("RQ1:65B6:-"))
        assertNull(EndpointName.decode("RQ1:65B60673A1B2C3D4:"))
        assertNull(EndpointName.decode("RQ1:65B60673A1B2C3D4:SG"))
        assertNull(EndpointName.decode("RQ1:65B60673A1B2C3D4:-:extra"))
    }
}
