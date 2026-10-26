package com.resqmesh.mesh.packet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The signed part of a packet (TRD 2.4). Field names are short on purpose: every byte crosses Bluetooth.
 * The JSON also carries "t" (SOS, CANCEL or ACK), which [BodyJson] writes as the type discriminator.
 */
@Serializable
sealed class PacketBody {
    /** Packet ID: 16 random bytes, base64url. The duplicate-suppression key. */
    abstract val id: String

    /** Signer's Ed25519 public key, base64url. The packet verifies against it with no prior key exchange. */
    abstract val pk: String

    /** Created, epoch ms on the sender's clock. */
    abstract val ts: Long

    /** Expires, epoch ms. Relays drop the packet after this. */
    abstract val exp: Long

    abstract val type: PacketType
}

@Serializable
@SerialName("SOS")
data class SosBody(
    override val id: String,
    override val pk: String,
    override val ts: Long,
    override val exp: Long,
    val cat: Category,
    /** Latitude and longitude, 5 decimal places (about 1 m); both absent when there is no fix. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** GPS accuracy, metres. */
    val acc: Int? = null,
    /** Age of the location fix, seconds. */
    val fixAge: Int? = null,
    /** Battery, percent. */
    val bat: Int? = null,
    /** People who need help. */
    val ppl: Int = 1,
    val note: String? = null,
    /** Language the sender reads replies in: en, hi or kn. */
    val lang: String = "en",
    /** Test packets never trigger SMS and show as TEST on the dashboard (F-60). */
    val test: Boolean = false,
) : PacketBody() {
    override val type: PacketType get() = PacketType.SOS
}

/** Withdraws an SOS. Only valid when signed by the same key as the SOS it names. */
@Serializable
@SerialName("CANCEL")
data class CancelBody(
    override val id: String,
    override val pk: String,
    override val ts: Long,
    override val exp: Long,
    /** ID of the SOS being cancelled. */
    val ref: String,
    val reason: CancelReason,
) : PacketBody() {
    override val type: PacketType get() = PacketType.CANCEL
}

/**
 * GATEWAY acks are signed by the phone that uploaded or texted the SOS ([up], [sms]).
 * RESPONDER acks are signed by the server key pinned in the app and carry a reply [code].
 */
@Serializable
@SerialName("ACK")
data class AckBody(
    override val id: String,
    override val pk: String,
    override val ts: Long,
    override val exp: Long,
    /** ID of the SOS being acknowledged. */
    val ref: String,
    val kind: AckKind,
    val up: Boolean? = null,
    val sms: Boolean? = null,
    /** A [ResponderCodes] value; phones show it in the sender's language. */
    val code: String? = null,
    val msg: String? = null,
    /** Responding unit, e.g. "District Control Room". */
    val by: String? = null,
) : PacketBody() {
    override val type: PacketType get() = PacketType.ACK
}

@Serializable
enum class Category { MEDICAL, FLOOD, FIRE, TRAPPED, WATER, GENERAL }

@Serializable
enum class CancelReason { SAFE, MISTAKE }

@Serializable
enum class AckKind { GATEWAY, RESPONDER }

/**
 * Reply codes a responder can send. Kept as strings, not an enum, so a phone running an older
 * app still relays and displays a code it doesn't know (falling back to `msg`).
 */
object ResponderCodes {
    const val TEAM_DISPATCHED = "TEAM_DISPATCHED"
    const val STAY_PUT = "STAY_PUT"
    const val MOVE_HIGHER = "MOVE_HIGHER"
}
