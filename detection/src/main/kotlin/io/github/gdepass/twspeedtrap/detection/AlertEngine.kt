package io.github.gdepass.twspeedtrap.detection

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class EngineConfig(
    /** Alerts fire this many metres before the camera below [AlertEngine.HIGH_SPEED_THRESHOLD_KMH]. */
    val alertDistanceM: Double = 300.0,
    /** Alerts fire this many metres before the camera at highway speed. */
    val highSpeedAlertDistanceM: Double = 500.0,
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

    /** The camera whose alert fired and is still ahead, with its current distance. */
    var activeAlert: Pair<Camera, Double>? = null
        private set

    private var activeMinDistanceM = 0.0

    fun onFix(fix: Fix): List<AlertEvent> {
        val events = ArrayList<AlertEvent>(1)
        val alertDistance =
            if (fix.speedMps * 3.6 >= HIGH_SPEED_THRESHOLD_KMH) {
                config.highSpeedAlertDistanceM
            } else {
                config.alertDistanceM
            }
        var nearest: Pair<Camera, Double>? = null

        for (camera in index.near(fix.lat, fix.lon)) {
            val distance = evaluate(camera, fix, alertDistance, events) ?: continue
            if (nearest == null || distance < nearest.second) nearest = camera to distance
        }
        nearestCamera = nearest
        trackActiveAlert(fix, events)
        if (sectionsEnabled) events.addAll(sectionTracker.onFix(fix))
        return events
    }

    /** Keeps [activeAlert] on the last fired camera and emits [AlertEvent.AllClear]
     * once it is passed. A newer alert takes over silently: the rider is not
     * "clear" while another camera is ahead. */
    private fun trackActiveAlert(
        fix: Fix,
        events: MutableList<AlertEvent>,
    ) {
        val fired = events.lastOrNull { it is AlertEvent.CameraAhead } as? AlertEvent.CameraAhead
        if (fired != null) {
            activeAlert = fired.camera to fired.distanceM
            activeMinDistanceM = fired.distanceM
            return
        }
        val (camera, _) = activeAlert ?: return
        val distance = GeoMath.distanceMeters(fix.lat, fix.lon, camera.lat, camera.lon)
        activeMinDistanceM = min(activeMinDistanceM, distance)
        // Behind by bearing is the prompt signal; the distance margin covers a
        // rider who turns off the road (or whose GPS bearing is unreliable).
        if (isBehind(fix, camera) || distance > activeMinDistanceM + PASS_CLEAR_MARGIN_M) {
            activeAlert = null
            events.add(AlertEvent.AllClear(camera))
        } else {
            activeAlert = camera to distance
        }
    }

    /** Returns the distance when the camera is relevant to this fix, else null. */
    private fun evaluate(
        camera: Camera,
        fix: Fix,
        alertDistance: Double,
        events: MutableList<AlertEvent>,
    ): Double? {
        // Section endpoints are announced by the AverageSpeedTracker, not as point cameras.
        if (camera.type == CameraType.SECTION || camera.type !in config.enabledTypes) return null
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

    /** Not the negation of [isAhead]: without a trustworthy bearing (slow or
     * missing) the position is unknown, and a rider braking to a stop at the
     * camera must not be declared clear. */
    private fun isBehind(
        fix: Fix,
        camera: Camera,
    ): Boolean {
        if (fix.speedMps < config.minSpeedForBearingMps) return false
        val travel = fix.bearingDeg ?: return false
        val toCamera = GeoMath.bearingDegrees(fix.lat, fix.lon, camera.lat, camera.lon)
        return angularDifference(travel, toCamera) > AHEAD_HALF_PLANE_DEG
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
        /** At or above this speed the high-speed alert distance applies. */
        const val HIGH_SPEED_THRESHOLD_KMH = 100.0

        /** A passed camera counts as "ahead" while within this angle of the travel bearing. */
        const val AHEAD_HALF_PLANE_DEG = 90.0

        /** Fallback pass detection: clear once this much farther than the closest approach. */
        const val PASS_CLEAR_MARGIN_M = 75.0

        fun angularDifference(
            a: Double,
            b: Double,
        ): Double {
            val diff = abs(a - b) % 360.0
            return min(diff, 360.0 - diff)
        }
    }
}
