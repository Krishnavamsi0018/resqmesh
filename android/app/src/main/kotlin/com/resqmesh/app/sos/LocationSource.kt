package com.resqmesh.app.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.resqmesh.mesh.packet.Location
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import android.location.Location as AndroidLocation

/**
 * Where the sender is, for the SOS (F-12, F-13). Listening starts when the countdown opens, so
 * GPS has those 5 seconds to find a fix. The SOS never waits on GPS for long: with no fix 10 s
 * after the countdown opened, it takes the last known location with its age, or none at all.
 * Uses the platform providers, so it needs no extra Play services library. Main thread only.
 */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var best: AndroidLocation? = null
    private var startedAt = 0L
    private var listening = false

    private val listener = LocationListenerCompat { location -> if (isBetter(location)) best = location }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun start() {
        stop()
        best = null
        startedAt = SystemClock.elapsedRealtime()
        if (!hasPermission()) return
        val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MS).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        for (provider in PROVIDERS) {
            if (!manager.isProviderEnabled(provider)) continue
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, listener, Looper.getMainLooper())
            listening = true
        }
    }

    @SuppressLint("MissingPermission") // removing our own listener needs no permission
    fun stop() {
        if (listening) LocationManagerCompat.removeUpdates(manager, listener)
        listening = false
    }

    /** The best fix so far, waiting at most until [MAX_WAIT_MS] after [start]; then stops listening. */
    suspend fun fix(): Location? {
        while (best == null && listening && SystemClock.elapsedRealtime() - startedAt < MAX_WAIT_MS) delay(POLL_MS)
        stop()
        return (best ?: lastKnown())?.toFix()
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private fun lastKnown(): AndroidLocation? {
        if (!hasPermission()) return null
        return PROVIDERS.mapNotNull { manager.getLastKnownLocation(it) }.minByOrNull { ageSeconds(it) }
    }

    /** A fix replaces the current best if it's more accurate, or much newer. */
    private fun isBetter(candidate: AndroidLocation): Boolean {
        val current = best ?: return true
        if (ageSeconds(current) - ageSeconds(candidate) > 10) return true
        return candidate.hasAccuracy() && (!current.hasAccuracy() || candidate.accuracy <= current.accuracy)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun AndroidLocation.toFix() = Location(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy.roundToInt() else null,
        fixAgeS = ageSeconds(this),
    )

    private fun ageSeconds(location: AndroidLocation): Int =
        ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000L).toInt().coerceAtLeast(0)

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MAX_WAIT_MS = 10_000L
        const val POLL_MS = 250L
    }
}
