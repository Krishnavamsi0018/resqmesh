package com.resqmesh.mesh.packet

/**
 * Packet type, as written in header byte 3.
 * [priority]: lower is sent first; SOS and cancels jump ahead of ACKs (F-23).
 * Code 4 is reserved for the SAFE check-in, planned for after the MVP (decision D7).
 */
enum class PacketType(val code: Int, val priority: Int) {
    SOS(1, 0),
    CANCEL(2, 0),
    ACK(3, 1);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}
