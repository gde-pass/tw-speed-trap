package io.github.gdepass.twspeedtrap.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.gdepass.twspeedtrap.detection.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 1 Hz high-accuracy GPS as a cold [Flow] of [Fix]es. */
class LocationSource(
    context: Context,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // caller gates on the runtime permission
    fun fixes(): Flow<Fix> =
        callbackFlow {
            val request =
                LocationRequest
                    .Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                    .setMinUpdateIntervalMillis(INTERVAL_MS)
                    .build()
            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val location = result.lastLocation ?: return
                        trySend(
                            Fix(
                                lat = location.latitude,
                                lon = location.longitude,
                                speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0,
                                timestampMs = location.time,
                            ),
                        )
                    }
                }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            awaitClose { client.removeLocationUpdates(callback) }
        }

    companion object {
        const val INTERVAL_MS = 1000L
    }
}
