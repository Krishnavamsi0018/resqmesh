package com.resqmesh.mesh

/**
 * Wire-format constants and limits shared by every ResQMesh node (TRD 2.4 and 2.5).
 * Changing any of these changes the protocol: bump [VERSION] and regenerate the test vectors.
 */
object Protocol {
    /** First two bytes of every packet: ASCII "RQ". */
    const val MAGIC_0: Byte = 0x52
    const val MAGIC_1: Byte = 0x51

    const val VERSION: Int = 1

    const val HEADER_SIZE: Int = 8
    const val SIGNATURE_SIZE: Int = 64
    const val MAX_BODY_SIZE: Int = 900
    const val MAX_PACKET_SIZE: Int = HEADER_SIZE + MAX_BODY_SIZE + SIGNATURE_SIZE

    /** Packet IDs are 16 random bytes, written as base64url. */
    const val ID_SIZE: Int = 16

    const val DEFAULT_TTL: Int = 8
    /** Relays drop anything that arrives claiming more hops left than this. */
    const val MAX_TTL: Int = 8

    const val DEFAULT_LIFETIME_MS: Long = 12 * 60 * 60 * 1000L
    const val MAX_LIFETIME_MS: Long = 24 * 60 * 60 * 1000L

    /** Phone clocks drift; a packet counts as expired only this long after its `exp`. */
    const val CLOCK_SKEW_MS: Long = 10 * 60 * 1000L

    const val MAX_NOTE_LENGTH: Int = 120
    const val MAX_ACK_MESSAGE_LENGTH: Int = 160
    const val MAX_UNIT_NAME_LENGTH: Int = 60
    const val MAX_PEOPLE: Int = 999
}
