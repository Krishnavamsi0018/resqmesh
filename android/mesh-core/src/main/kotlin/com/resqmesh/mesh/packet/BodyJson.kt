package com.resqmesh.mesh.packet

import kotlinx.serialization.json.Json

/**
 * JSON encoding of packet bodies. Bodies are signed as the exact bytes produced here and never
 * re-encoded afterwards, so Kotlin and Node don't need byte-identical JSON writers (decision D5).
 */
object BodyJson {
    val json: Json = Json {
        classDiscriminator = "t"
        encodeDefaults = true
        explicitNulls = false
        // A newer app may add fields; older relays must still accept and forward its packets.
        ignoreUnknownKeys = true
    }

    fun encode(body: PacketBody): ByteArray =
        json.encodeToString(PacketBody.serializer(), body).encodeToByteArray()

    /**
     * @throws IllegalArgumentException (including kotlinx's SerializationException) for bad JSON,
     *   or [CharacterCodingException] for bytes that aren't UTF-8.
     */
    fun decode(bytes: ByteArray): PacketBody =
        json.decodeFromString(PacketBody.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
}
