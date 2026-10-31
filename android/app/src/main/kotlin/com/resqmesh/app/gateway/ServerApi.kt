package com.resqmesh.app.gateway

import com.resqmesh.mesh.Base64Url
import com.resqmesh.mesh.gateway.UploadStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The two backend calls a gateway phone makes (TRD 2.7, 2.10). Blocking: call them off the main
 * thread. Plain HttpURLConnection and org.json, so the app needs no HTTP library for two requests.
 */
class ServerApi(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    class UploadReply(val statuses: List<UploadStatus?>, val acks: List<ByteArray>)

    /** POST /api/v1/packets: one status per envelope (null if the server didn't say), plus waiting replies. */
    fun upload(gateway: String, envelopes: List<ByteArray>): UploadReply {
        val body = JSONObject()
            .put("gateway", gateway)
            .put("packets", JSONArray(envelopes.map(Base64Url::encode)))
        val reply = JSONObject(request("POST", "/api/v1/packets", body.toString()))
        val statuses = arrayOfNulls<UploadStatus>(envelopes.size)
        val results = reply.getJSONArray("results")
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val index = result.optInt("index", -1)
            if (index !in statuses.indices) continue
            statuses[index] = when (result.optString("status")) {
                "stored" -> UploadStatus.STORED
                "duplicate" -> UploadStatus.DUPLICATE
                "rejected" -> UploadStatus.REJECTED
                else -> null
            }
        }
        return UploadReply(statuses.toList(), envelopes(reply.optJSONArray("acks")))
    }

    /** GET /api/v1/acks: responder replies for these SOS, to flood into the mesh (F-44). */
    fun acks(sosIds: List<String>): List<ByteArray> {
        val ids = URLEncoder.encode(sosIds.joinToString(","), Charsets.UTF_8.name())
        return envelopes(JSONObject(request("GET", "/api/v1/acks?ids=$ids", null)).optJSONArray("acks"))
    }

    private fun envelopes(array: JSONArray?): List<ByteArray> =
        (0 until (array?.length() ?: 0)).mapNotNull { Base64Url.decodeOrNull(array!!.getString(it)) }

    private fun request(method: String, path: String, json: String?): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("server answered $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000

        fun isValidUrl(url: String): Boolean = Regex("^https?://[^\\s/]+(/[^\\s]*)?$").matches(url.trim())
    }
}
