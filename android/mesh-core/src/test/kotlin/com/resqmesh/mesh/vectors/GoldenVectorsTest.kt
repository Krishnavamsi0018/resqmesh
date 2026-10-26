package com.resqmesh.mesh.vectors

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.FixedClock
import com.resqmesh.mesh.hexToBytes
import com.resqmesh.mesh.packet.PacketValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenVectorsTest {
    private val file = File(
        System.getProperty("resqmesh.vectorsFile") ?: error("Run through Gradle so resqmesh.vectorsFile is set"),
    )

    @Test
    fun `committed vectors match the current packet format`() {
        assertTrue(file.exists(), "Missing $file. Run ./gradlew :mesh-core:writeTestVectors")
        assertEquals(
            TestVectors.render(),
            file.readText().replace("\r\n", "\n"),
            "$file is out of date. Run ./gradlew :mesh-core:writeTestVectors and commit the result",
        )
    }

    @Test
    fun `every vector gets its expected verdict`() {
        val vectorFile = TestVectors.json.decodeFromString(VectorFile.serializer(), file.readText())
        val validator = PacketValidator(Base64Url.decodeOrNull(vectorFile.serverPublicKey)!!, FixedClock(vectorFile.verifyAtMillis))

        for (vector in vectorFile.vectors) {
            assertEquals(
                vector.expect to vector.reason,
                TestVectors.verdictOf(vector.envelopeHex.hexToBytes(), validator),
                vector.name,
            )
        }
    }
}
