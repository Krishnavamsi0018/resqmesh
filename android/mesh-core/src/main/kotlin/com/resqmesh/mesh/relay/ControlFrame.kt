package com.resqmesh.mesh.relay

/**
 * Frames two newly linked phones swap so each can fetch the packets it missed (store-carry-forward,
 * F-24, TRD 2.5). They never leave that one link, so unlike packets they are not signed.
 *
 * ```
 * offset  size   field
 * 0       2      magic "RC"
 * 2       1      version (1)
 * 3       1      kind: 1 SUMMARY ("I hold these"), 2 REQUEST ("send me these")
 * 4       2      key count, big-endian, at most 500
 * 6       16×n   packet keys
 * ```
 */
sealed interface ControlFrame {
    val keys: List<PacketKey>

    data class Summary(override val keys: List<PacketKey>) : ControlFrame

    data class Request(override val keys: List<PacketKey>) : ControlFrame

    companion object {
        const val MAX_KEYS = 500
        private const val MAGIC_0: Byte = 0x52
        private const val MAGIC_1: Byte = 0x43
        private const val VERSION = 1
        private const val KIND_SUMMARY = 1
        private const val KIND_REQUEST = 2
        private const val HEADER_SIZE = 6

        /** Control frames start with "RC"; signed packets start with "RQ". */
        fun isControl(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == MAGIC_0 && bytes[1] == MAGIC_1

        fun encode(frame: ControlFrame): ByteArray {
            require(frame.keys.size <= MAX_KEYS) { "At most $MAX_KEYS keys per frame" }
            val out = ByteArray(HEADER_SIZE + PacketKey.SIZE * frame.keys.size)
            out[0] = MAGIC_0
            out[1] = MAGIC_1
            out[2] = VERSION.toByte()
            out[3] = (if (frame is Summary) KIND_SUMMARY else KIND_REQUEST).toByte()
            out[4] = (frame.keys.size ushr 8).toByte()
            out[5] = frame.keys.size.toByte()
            frame.keys.forEachIndexed { i, key -> key.toBytes().copyInto(out, HEADER_SIZE + i * PacketKey.SIZE) }
            return out
        }

        /** Null for anything malformed; a neighbour's bad frame is ignored, never trusted. */
        fun decode(bytes: ByteArray): ControlFrame? {
            if (bytes.size < HEADER_SIZE || !isControl(bytes) || bytes[2].toInt() != VERSION) return null
            val count = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            if (count > MAX_KEYS || bytes.size != HEADER_SIZE + count * PacketKey.SIZE) return null
            val keys = List(count) { i ->
                val start = HEADER_SIZE + i * PacketKey.SIZE
                PacketKey.fromBytes(bytes.copyOfRange(start, start + PacketKey.SIZE))
            }
            return when (bytes[3].toInt()) {
                KIND_SUMMARY -> Summary(keys)
                KIND_REQUEST -> Request(keys)
                else -> null
            }
        }
    }
}
