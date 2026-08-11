package io.github.gdepass.twspeedtrap.detection

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class EngineConfig(
    /** Alert distance = max(minAlertDistanceM, speed_m/s × distanceMultiplier). */
    val distanceMultiplier: Double = 12.0,
    val minAlertDistanceM: Double = 200.0,
    val bearingToleranceDeg: Double = 45.0,
    /** Below this speed GPS bearing is noise; skip the bearing filter. */
    val minSpeedForBearingMps: Double = 15.0 / 3.6,
    val speedToleranceKmh: Double = 10.0,
    val enabledTypes: Set<CameraType> = CameraType.entries.toSet(),
    /** A fired camera re-arms only once you are this factor beyond its alert distance. */
    val rearmFactor: Double = 1.5,
)

/**
 * Turns a stream of GPS fixes into alert events. Each camera fires at most
 * once per approach: after firing it stays disarmed until the rider has moved
 * away beyond rearmFactor × alert distance (hysteresis).
 */
class AlertEngine(
    cameras: List<Camera>,
    private val config: EngineConfig = EngineConfig(),
    sections: Map<String, Section> = emptyMap(),
) {
    private val index = GridIndex(cameras)
    private val disarmed = HashSet<String>()
    private val sectionTracker = AverageSpeedTracker(cameras, sections, config)
    private val sectionsEnabled = CameraType.SECTION in config.enabledTypes

    /** Distance to the nearest relevant camera, for the UI. */
    var nearestCamera: Pair<Camera, Double>? = null
        private set

    fun onFix(fix: Fix): List<AlertEvent> {
        val events = ArrayList<AlertEvent>(1)
        val alertDistance = max(config.minAlertDistanceM, fix.speedMps * config.distanceMultiplier)
        var nearest: Pair<Camera, Double>? = null

        for (camera in index.near(fix.lat, fix.lon)) {
            val distance = evaluate(camera, fix, alertDistance, events) ?: continue
            if (nearest == null || distance < nearest.second) nearest = camera to distance
        }
        nearestCamera = nearest
        if (sectionsEnabled) events.addAll(sectionTracker.onFix(fix))
        return events
    }

    /** Returns the distance when the camera is relevant to this fix, else null. */
    private fun evaluate(
        camera: Camera,
        fix: Fix,
        alertDistance: Double,
        events: MutableList<AlertEvent>,
    ): Double? {
        // Section endpoints are announced by the AverageSpeedTracker, not as point cameras.
        if (camera.type == CameraType.SECTION) return null
        if (camera.type !in config.enabledTypes) return null
        val distance = GeoMath.distanceMeters(fix.lat, fix.lon, camera.lat, camera.lon)
        if (camera.id in disarmed) {
            // Re-arm is checked before the bearing filter: a rider who turns
            // away after firing must not leave the camera disarmed forever.
            if (distance > alertDistance * config.rearmFactor) disarmed.remove(camera.id)
            // A just-passed camera is "next" only while it is still ahead.
            return if (isAhead(fix, camera)) distance else null
        }
        if (!bearingMatches(fix, camera)) return null
        if (distance <= alertDistance) {
            disarmed.add(camera.id)
            val speedKmh = (fix.speedMps * 3.6).roundToInt()
            val overLimit =
                camera.speedLimitKmh != null &&
                    speedKmh > camera.speedLimitKmh + config.speedToleranceKmh
            events.add(AlertEvent.CameraAhead(camera, distance, speedKmh, overLimit))
        }
        return distance
    }

    private fun isAhead(
        fix: Fix,
        camera: Camera,
    ): Boolean {
        if (fix.speedMps < config.minSpeedForBearingMps) return false
        val travel = fix.bearingDeg ?: return false
        val toCamera = GeoMath.bearingDegrees(fix.lat, fix.lon, camera.lat, camera.lon)
        return angularDifference(travel, toCamera) <= AHEAD_HALF_PLANE_DEG
    }

    private fun bearingMatches(
        fix: Fix,
        camera: Camera,
    ): Boolean {
        val enforced = camera.bearingDeg ?: return true
        if (fix.speedMps < config.minSpeedForBearingMps) return true
        val travel = fix.bearingDeg ?: return true
        return angularDifference(travel, enforced) <= config.bearingToleranceDeg
    }

    companion object {
        /** A passed camera counts as "ahead" while within this angle of the travel bearing. */
        const val AHEAD_HALF_PLANE_DEG = 90.0

        fun angularDifference(
            a: Double,
            b: Double,
        ): Double {
            val diff = abs(a - b) % 360.0
            return min(diff, 360.0 - diff)
        }
    }
}
