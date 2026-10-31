package com.resqmesh.app.experiment

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a phone saw of one test SOS during a field experiment (TRD 2.12). */
enum class ExperimentEventKind {
    /** This phone sent the test SOS. */
    SENT,
    /** This phone received it for the first time. */
    RECEIVED,
    /** A copy this phone already had. */
    DUPLICATE,
    /** This phone sent a copy to a neighbour (one radio transmission). */
    TX,
    /** This phone, at the end of the chain, sent the echo back. */
    ECHO_SENT,
    /** The sender got the echo: [ExperimentEvent.rttMs] is the round trip on its own clock. */
    ECHO_RECEIVED,
}

data class ExperimentEvent(
    val atMs: Long,
    val kind: ExperimentEventKind,
    val sosId: String,
    val peer: String = "",
    /** Radio hops the SOS took to reach this phone. */
    val hops: Int? = null,
    /** When the sender made it, on the sender's clock. */
    val sentTs: Long? = null,
    val rttMs: Long? = null,
)

/**
 * The event log a phone keeps during field experiments, saved as CSV for experiments/field.
 * Phone clocks differ by up to a second or so, so latency comes from the echo's round trip, which
 * the sender measures on its own clock. Main thread.
 */
class ExperimentLog(private val phone: String, private val dir: File?) {
    private val rows = ArrayList<Pair<String, ExperimentEvent>>()

    /** The setting being measured, e.g. "hops-2"; stored with every row. */
    var label: String = "hops-1"

    val size: Int get() = rows.size

    fun add(event: ExperimentEvent) {
        if (rows.size >= MAX_ROWS) return
        rows += label to event
    }

    fun clear() = rows.clear()

    /** Writes everything logged so far to a new CSV file and returns it (null when storage is unavailable). */
    fun save(): File? {
        val folder = dir ?: return null
        folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(folder, "experiment-$phone-$stamp.csv")
        file.bufferedWriter().use { out ->
            out.appendLine("phone,label,at_ms,event,sos_id,peer,hops,sent_ts,rtt_ms")
            for ((label, e) in rows) {
                out.appendLine(listOf(phone, label, e.atMs, e.kind, e.sosId, e.peer, e.hops ?: "", e.sentTs ?: "", e.rttMs ?: "").joinToString(","))
            }
        }
        return file
    }

    private companion object {
        const val MAX_ROWS = 50_000
    }
}
