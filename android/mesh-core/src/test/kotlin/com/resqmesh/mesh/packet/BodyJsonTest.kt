package com.resqmesh.mesh.packet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BodyJsonTest {
    private val id = "AAECAwQFBgcICQoLDA0ODw"
    private val pk = "A".repeat(43)

    private val sos = SosBody(id = id, pk = pk, ts = 1, exp = 2, cat = Category.FLOOD, ppl = 2, lang = "kn")

    @Test
    fun `SOS is written with the t discriminator and short field names`() {
        val text = BodyJson.encode(sos).decodeToString()

        assertTrue(text.startsWith("""{"t":"SOS","id":"$id","pk":"$pk","ts":1,"exp":2,"cat":"FLOOD""""), text)
        assertTrue(text.contains(""""ppl":2"""), text)
        assertTrue(text.contains(""""test":false"""), text)
    }

    @Test
    fun `absent location and note are left out, not written as null`() {
        val text = BodyJson.encode(sos).decodeToString()

        assertFalse(text.contains("lat"), text)
        assertFalse(text.contains("note"), text)
        assertFalse(text.contains("null"), text)
    }

    @Test
    fun `every body type round-trips`() {
        val bodies = listOf(
            sos.copy(lat = 12.9716, lon = 77.59456, acc = 15, fixAge = 40, bat = 42, note = "leg injury"),
            CancelBody(id = id, pk = pk, ts = 1, exp = 2, ref = id, reason = CancelReason.SAFE),
            AckBody(id = id, pk = pk, ts = 1, exp = 2, ref = id, kind = AckKind.GATEWAY, up = true, sms = false),
            AckBody(id = id, pk = pk, ts = 1, exp = 2, ref = id, kind = AckKind.RESPONDER, code = ResponderCodes.STAY_PUT, by = "Unit 4"),
        )
        for (body in bodies) assertEquals(body, BodyJson.decode(BodyJson.encode(body)))
    }

    @Test
    fun `Kannada and Hindi text survive as UTF-8`() {
        val note = "ಕಾಲಿಗೆ ಗಾಯ / पैर में चोट"
        val decoded = BodyJson.decode(BodyJson.encode(sos.copy(note = note)))
        assertEquals(note, (decoded as SosBody).note)
    }

    @Test
    fun `unknown fields from a newer app are ignored`() {
        val text = """{"t":"SOS","id":"$id","pk":"$pk","ts":1,"exp":2,"cat":"FIRE","floor":3}"""
        val body = BodyJson.decode(text.encodeToByteArray())

        assertIs<SosBody>(body)
        assertEquals(Category.FIRE, body.cat)
    }

    @Test
    fun `a body without t or with an unknown t is refused`() {
        assertFailsWith<IllegalArgumentException> {
            BodyJson.decode("""{"id":"$id","pk":"$pk","ts":1,"exp":2,"cat":"FIRE"}""".encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            BodyJson.decode("""{"t":"SAFE","id":"$id","pk":"$pk","ts":1,"exp":2}""".encodeToByteArray())
        }
    }
}
