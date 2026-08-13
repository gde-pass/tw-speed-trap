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

    /** Fired cameras that must not re-fire, with the ring distance that fired:
     * re-arm distance is computed from that ring, not the current speed band,
     * or braking through 100 km/h would shrink the ring and re-alert. */
    private val disarmed = HashMap<String, Double>()

    /** Fired cameras not yet passed, each with its closest approach so far. */
    private class PendingPass(
        val camera: Camera,
        var minDistanceM: Double,
    )

    private val pendingPasses = LinkedHashMap<String, PendingPass>()
    private val sectionTracker = AverageSpeedTracker(cameras, sections, config)
    private val sectionsEnabled = CameraType.SECTION in config.enabledTypes

    /** Distance to the nearest relevant camera, for the UI. */
    var nearestCamera: Pair<Camera, Double>? = null
        private set

    /** The nearest fired camera still ahead, with its current distance. */
    var activeAlert: Pair<Camera, Double>? = null
        private set

    /** Live section traversal: (section, projected exit average km/h); null outside. */
    val activeSection: Pair<Section, Int>? get() = sectionTracker.liveStatus

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

    /** Keeps [activeAlert] on the nearest fired-but-not-passed camera and emits
     * [AlertEvent.AllClear] once the last of them is behind: the rider is not
     * "clear" while any fired camera is still ahead. */
    private fun trackActiveAlert(
        fix: Fix,
        events: MutableList<AlertEvent>,
    ) {
        // A camera fired on this very fix gets its first pass check on the
        // next one: a null-bearing camera alerting from behind (fail-safe)
        // must not fire and clear in the same breath.
        val firedNow = HashSet<String>()
        for (event in events) {
            if (event is AlertEvent.CameraAhead) {
                pendingPasses[event.camera.id] = PendingPass(event.camera, event.distanceM)
                firedNow.add(event.camera.id)
            }
        }
        if (pendingPasses.isEmpty()) {
            activeAlert = null
            return
        }
        var nearest: Pair<Camera, Double>? = null
        var lastPassed: Camera? = null
        val iterator = pendingPasses.values.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val distance = GeoMath.distanceMeters(fix.lat, fix.lon, pending.camera.lat, pending.camera.lon)
            if (pending.camera.id !in firedNow && isPassed(fix, pending, distance)) {
                iterator.remove()
                lastPassed = pending.camera
            } else if (nearest == null || distance < nearest.second) {
                nearest = pending.camera to distance
            }
        }
        activeAlert = nearest
        if (lastPassed != null && pendingPasses.isEmpty()) events.add(AlertEvent.AllClear(lastPassed))
    }

    /** A noisy fix must never fake a pass: both signals require decent
     * accuracy, and the distance fallback additionally requires real movement
     * (stationary GPS drift and one-off multipath jumps stay pending). */
    private fun isPassed(
        fix: Fix,
        pending: PendingPass,
        distance: Double,
    ): Boolean {
        if (fix.accuracyM > PASS_ACCURACY_GATE_M) return false
        if (isBehind(fix, pending.camera)) return true
        if (fix.speedMps < config.minSpeedForBearingMps) return false
        pending.minDistanceM = min(pending.minDistanceM, distance)
        return distance > pending.minDistanceM + PASS_CLEAR_MARGIN_M
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
        val firedRingM = disarmed[camera.id]
        if (firedRingM != null) {
            // Re-arm is checked before the bearing filter: a rider who turns
            // away after firing must not leave the camera disarmed forever.
            if (distance > firedRingM * config.rearmFactor) disarmed.remove(camera.id)
            // A just-passed camera is "next" only while it is still ahead.
            return if (isAhead(fix, camera)) distance else null
        }
        if (!bearingMatches(fix, camera)) return null
        if (distance <= alertDistance) {
            disarmed[camera.id] = alertDistance
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

        /** Fixes with worse accuracy than this decide nothing about passing a camera. */
        const val PASS_ACCURACY_GATE_M = 50.0

        fun angularDifference(
            a: Double,
            b: Double,
        ): Double {
            val diff = abs(a - b) % 360.0
            return min(diff, 360.0 - diff)
        }
    }
}
