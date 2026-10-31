package com.resqmesh.mesh.gateway

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.crypto.NodeId
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.packet.SosBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The SMS a phone sends for an SOS (TRD 2.7). Always English in plain ASCII, which is inside the
 * GSM-7 alphabet, so it fits one 160-character SMS; Indic script would cut that to 70. The note is
 * left out for the same reason. Example:
 *
 *     SOS FLOOD via ResQMesh. 2 ppl. 12.97160,77.59456 +/-15m maps.google.com/?q=12.97160,77.59456
 *     14:32 26-Oct from 7F3A-91C2 ID q3F7xz hops 3
 */
object SmsText {
    const val MAX_LENGTH = 160

    private val TIME = DateTimeFormatter.ofPattern("HH:mm dd-MMM", Locale.US)

    fun format(sos: SosBody, hops: Int, zone: ZoneId): String {
        val what = when (sos.cat) {
            Category.GENERAL -> "EMERGENCY"
            Category.WATER -> "NEED WATER"
            else -> sos.cat.name
        }
        val place = if (sos.lat != null && sos.lon != null) {
            val at = String.format(Locale.US, "%.5f,%.5f", sos.lat, sos.lon)
            val accuracy = sos.acc?.let { " +/-${it}m" }.orEmpty()
            "$at$accuracy maps.google.com/?q=$at"
        } else {
            "No location."
        }
        val time = TIME.format(Instant.ofEpochMilli(sos.ts).atZone(zone))
        val from = Base64Url.decodeOrNull(sos.pk)?.let { " from ${NodeId.of(it).short}" }.orEmpty()
        return "SOS $what via ResQMesh. ${sos.ppl} ppl. $place $time$from ID ${sos.id.take(6)} hops $hops".take(MAX_LENGTH)
    }
}
