package com.resqmesh.mesh.packet

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.Clock
import com.resqmesh.mesh.Protocol
import com.resqmesh.mesh.crypto.NodeKeys
import java.security.SecureRandom
import kotlin.math.roundToLong

/** A GPS fix as the app hands it over; coordinates are rounded to 5 decimals (about 1 m) when packed. */
data class Location(
    val lat: Double,
    val lon: Double,
    val accuracyM: Int? = null,
    val fixAgeS: Int? = null,
)

/** Builds new signed packets for this node: its own SOS, cancels and ACKs. */
class PacketFactory(
    private val keys: NodeKeys,
    private val clock: Clock = Clock.SYSTEM,
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMs: Long = Protocol.DEFAULT_LIFETIME_MS,
) {
    fun sos(
        category: Category,
        location: Location?,
        batteryPercent: Int? = null,
        people: Int = 1,
        note: String? = null,
        lang: String = "en",
        test: Boolean = false,
    ): Packet {
        val now = clock.nowMillis()
        return seal(
            SosBody(
                id = newId(),
                pk = keys.publicKeyB64,
                ts = now,
                exp = now + lifetimeMs,
                cat = category,
                lat = location?.lat?.let(::roundTo5),
                lon = location?.lon?.let(::roundTo5),
                acc = location?.accuracyM,
                fixAge = location?.fixAgeS,
                bat = batteryPercent,
                ppl = people,
                note = note?.trim()?.take(Protocol.MAX_NOTE_LENGTH)?.ifEmpty { null },
                lang = lang,
                test = test,
            ),
        )
    }

    fun cancel(sosId: String, reason: CancelReason): Packet {
        val now = clock.nowMillis()
        return seal(CancelBody(id = newId(), pk = keys.publicKeyB64, ts = now, exp = now + lifetimeMs, ref = sosId, reason = reason))
    }

    fun gatewayAck(sosId: String, uploaded: Boolean, sms: Boolean): Packet {
        val now = clock.nowMillis()
        return seal(
            AckBody(
                id = newId(),
                pk = keys.publicKeyB64,
                ts = now,
                exp = now + lifetimeMs,
                ref = sosId,
                kind = AckKind.GATEWAY,
                up = uploaded,
                sms = sms,
            ),
        )
    }

    /** Only meaningful when this factory holds the server key; the backend signs these in production. */
    fun responderAck(sosId: String, code: String, message: String? = null, unit: String? = null): Packet {
        val now = clock.nowMillis()
        return seal(
            AckBody(
                id = newId(),
                pk = keys.publicKeyB64,
                ts = now,
                exp = now + lifetimeMs,
                ref = sosId,
                kind = AckKind.RESPONDER,
                code = code,
                msg = message,
                by = unit,
            ),
        )
    }

    /** Signs an already-built body. Public for tests and the simulator; the app uses the builders above. */
    fun seal(body: PacketBody, ttl: Int = Protocol.DEFAULT_TTL): Packet {
        val bytes = BodyJson.encode(body)
        require(bytes.size <= Protocol.MAX_BODY_SIZE) { "Body is ${bytes.size} bytes; the limit is ${Protocol.MAX_BODY_SIZE}" }
        return Packet(type = body.type, ttl = ttl, hops = 0, body = bytes, signature = keys.sign(bytes))
    }

    private fun newId(): String = Base64Url.encode(ByteArray(Protocol.ID_SIZE).also(random::nextBytes))

    private fun roundTo5(value: Double): Double = (value * 1e5).roundToLong() / 1e5
}
