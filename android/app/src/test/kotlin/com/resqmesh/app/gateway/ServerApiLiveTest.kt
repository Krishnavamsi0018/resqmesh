package com.resqmesh.app.gateway

import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.crypto.NodeKeys
import com.resqmesh.mesh.gateway.UploadStatus
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.PacketCodec
import com.resqmesh.mesh.packet.PacketFactory
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Talks to a real backend: run the API (cd backend && npm run dev), then
 * RESQMESH_API=http://localhost:8080 ./gradlew :app:testDebugUnitTest. Skipped otherwise.
 */
class ServerApiLiveTest {
    private val base = System.getenv("RESQMESH_API").orEmpty()

    @Test
    fun `uploads an SOS, sees it as a duplicate the second time, and asks for replies`() {
        assumeTrue("set RESQMESH_API to run", base.isNotEmpty())
        val api = ServerApi(base)
        val phone = PacketFactory(NodeKeys.generate(), Clock.SYSTEM)
        val sos = PacketCodec.encode(phone.sos(Category.WATER, location = null, note = "ServerApi live test", test = true))
        val garbage = byteArrayOf(1, 2, 3)

        val first = api.upload("BA81-12FA", listOf(sos, garbage))
        assertEquals(listOf(UploadStatus.STORED, UploadStatus.REJECTED), first.statuses)
        assertEquals(listOf(UploadStatus.DUPLICATE), api.upload("BA81-12FA", listOf(sos)).statuses)
        assertEquals(emptyList(), api.acks(listOf("AAAAAAAAAAAAAAAAAAAAAA")))
    }
}
