package com.resqmesh.mesh

import java.util.Base64

/** URL-safe base64 without padding, used for IDs and public keys inside packet bodies. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Returns null instead of throwing, because packet fields come from untrusted phones. */
    fun decodeOrNull(text: String): ByteArray? =
        try {
            decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string has an odd length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
