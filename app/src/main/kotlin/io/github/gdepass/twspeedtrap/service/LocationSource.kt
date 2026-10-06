package io.github.gdepass.twspeedtrap.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.gdepass.twspeedtrap.detection.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 1 Hz high-accuracy GPS as a cold [Flow] of [Fix]es. [onAvailability]
 * relays the provider's own availability signal (false in a tunnel or car
 * park) so blind detection can show as such; a rejected request (no Play
 * Services, outdated GMS) fails the flow instead of leaving it silently empty.
 */
class LocationSource(
    context: Context,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // caller gates on the runtime permission
    fun fixes(onAvailability: (Boolean) -> Unit = {}): Flow<Fix> =
        callbackFlow {
            val request =
                LocationRequest
                    .Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                    .setMinUpdateIntervalMillis(INTERVAL_MS)
                    .build()
            val callback =
                object : LocationCallback() {
                    override fun onLocationAvailability(availability: LocationAvailability) {
                        onAvailability(availability.isLocationAvailable)
                    }

                    override fun onLocationResult(result: LocationResult) {
                        // Every fix of a batched result, oldest first: dropping
                        // all but the last would hide the fixes that crossed a
                        // gantry. Timestamps are monotonic (boot clock) so an
                        // NTP step can neither rewind the engine nor inflate
                        // a section's elapsed time.
                        for (location in result.locations) {
                            trySend(
                                Fix(
                                    lat = location.latitude,
                                    lon = location.longitude,
                                    speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                                    bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                                    accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0,
                                    timestampMs = location.elapsedRealtimeNanos / 1_000_000L,
                                ),
                            )
                        }
                    }
                }
            client
                .requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close(it) }
            awaitClose { client.removeLocationUpdates(callback) }
        }

    companion object {
        const val INTERVAL_MS = 1000L
    }
}
