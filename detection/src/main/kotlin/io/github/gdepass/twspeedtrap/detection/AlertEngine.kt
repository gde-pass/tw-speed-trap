package io.github.gdepass.twspeedtrap.detection

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class EngineConfig(
    /** Alerts fire this many metres before the camera below [AlertEngine.HIGH_SPEED_THRESHOLD_KMH]. */
    val alertDistanceM: Double = 300.0,
    /** Alerts fire this many metres before the camera at highway speed. */
    val highSpeedAlertDistanceM: Double = 500.0,
    /**
     * Heading-vs-enforced-direction tolerance. Source datasets give only the
     * four cardinal directions, so a camera on a diagonal road is tagged up
     * to 45° off its true axis; add GPS heading noise and 45° flickered on
     * Taichung's skewed arterials. 60° still rejects cross traffic (90°).
     */
    val bearingToleranceDeg: Double = 60.0,
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
 *
 * Direction comes from the live GPS bearing at speed, or from the bearing
 * last seen at speed within [BEARING_MEMORY_MS] while crawling or stopped —
 * so queuing at a red light does not fail open onto the opposite-direction
 * camera of the same junction. Only a rider with no recent bearing at all
 * (start-up) is alerted regardless of direction.
 *
 * A camera fires only while it is still ahead: a camera that enforces a
 * direction is "passed" once the rider is downstream of it along that axis
 * (position only, so a hairpin apex cannot fake a pass), a bearingless
 * camera once it falls into the rear half-plane of the travel bearing.
 */
class AlertEngine(
    cameras: List<Camera>,
    private val config: EngineConfig = EngineConfig(),
    sections: Map<String, Section> = emptyMap(),
) {
    init {
        // A disarmed camera re-arms only when a fix sees it beyond its
        // re-arm ring; if that ring exceeds what the index returns, a camera
        // left while disarmed is never seen again and never fires again.
        val rearmRingM = maxOf(config.alertDistanceM, config.highSpeedAlertDistanceM) * config.rearmFactor
        require(rearmRingM <= GridIndex.MIN_COVERAGE_M) {
            "re-arm ring ${rearmRingM.toInt()} m exceeds the index coverage of ${GridIndex.MIN_COVERAGE_M.toInt()} m"
        }
    }

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
    private var lastReliableBearingDeg: Double? = null
    private var lastReliableBearingTimeMs: Long = Long.MIN_VALUE

    /** Distance to the nearest relevant camera, for the UI. */
    var nearestCamera: Pair<Camera, Double>? = null
        private set

    /** The nearest fired camera still ahead, with its current distance. */
    var activeAlert: Pair<Camera, Double>? = null
        private set

    /** Live section traversal: (section, projected exit average km/h); null outside. */
    val activeSection: Pair<Section, Int>? get() = sectionTracker.liveStatus

    /** Section endpoints the tracker had to leave out; see [AverageSpeedTracker.unusableEndpoints]. */
    val unusableSectionEndpoints: Int get() = sectionTracker.unusableEndpoints

    fun onFix(fix: Fix): List<AlertEvent> {
        updateBearingMemory(fix)
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
        sweepDisarmed(fix)
        trackActiveAlert(fix, events)
        if (sectionsEnabled) events.addAll(sectionTracker.onFix(fix))
        return events
    }

    /**
     * Re-arms fired cameras the index no longer returns. [evaluate] re-arms
     * only cameras within the index coverage, so a camera left behind in one
     * jump (tunnel, GPS outage, a 1 km gap between fixes) would otherwise
     * stay disarmed for the rest of the ride and never fire on the way back.
     */
    private fun sweepDisarmed(fix: Fix) {
        if (disarmed.isEmpty()) return
        val iterator = disarmed.entries.iterator()
        while (iterator.hasNext()) {
            val (id, firedRingM) = iterator.next()
            val camera = index.byId(id) ?: continue
            val distance = GeoMath.distanceMeters(fix.lat, fix.lon, camera.lat, camera.lon)
            if (distance > firedRingM * config.rearmFactor) iterator.remove()
        }
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
    ): Boolean =
        when {
            // Far beyond any alert ring the camera is unambiguously not
            // ahead: forget it even on untrusted fixes, or a pending pass
            // could stick forever through persistently poor accuracy.
            distance > PASS_FORGET_DISTANCE_M -> true
            fix.accuracyM > ACCURACY_GATE_M -> false
            isBehind(fix, pending.camera, distance) -> true
            fix.speedMps < config.minSpeedForBearingMps -> false
            else -> {
                pending.minDistanceM = min(pending.minDistanceM, distance)
                distance > pending.minDistanceM + PASS_CLEAR_MARGIN_M
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
        val firedRingM = disarmed[camera.id]
        if (firedRingM != null) {
            // Re-arm is checked before the bearing filter: a rider who turns
            // away after firing must not leave the camera disarmed forever.
            if (distance > firedRingM * config.rearmFactor) disarmed.remove(camera.id)
            // A just-passed camera is "next" only while it is still ahead.
            return if (isAhead(fix, camera)) distance else null
        }
        if (!isCandidate(fix, camera, distance)) return null
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

    /**
     * May this camera fire or count as the next camera on this fix? Untrusted
     * fixes (network/wifi fallback in tunnels and urban canyons, often with no
     * speed and no bearing) decide nothing: a 500 m-accuracy point would
     * otherwise fire any camera within the ring. A camera already behind the
     * rider is not "ahead" however the ring compares: without that gate a
     * camera re-armed at 450 m behind re-fired when accelerating past
     * 100 km/h widened the ring to 500 m.
     */
    private fun isCandidate(
        fix: Fix,
        camera: Camera,
        distance: Double,
    ): Boolean = fix.accuracyM <= ACCURACY_GATE_M && bearingMatches(fix, camera) && !isBehind(fix, camera, distance)

    private fun isAhead(
        fix: Fix,
        camera: Camera,
    ): Boolean {
        val travel = effectiveBearing(fix) ?: return false
        val toCamera = GeoMath.bearingDegrees(fix.lat, fix.lon, camera.lat, camera.lon)
        return angularDifference(travel, toCamera) <= AHEAD_HALF_PLANE_DEG
    }

    /**
     * Not the negation of [isAhead]. A camera enforcing a direction is behind
     * once the rider is downstream of it along that axis — decided from
     * position alone, so a hairpin apex (heading momentarily away from a
     * camera still ahead by road) cannot fake a pass, and a camera 485 m
     * back is behind whatever the ring says. Within [AXIS_MIN_DISTANCE_M]
     * the camera→rider bearing is GPS noise, so nothing is decided there.
     * A bearingless camera falls back to the travel bearing; without a
     * trustworthy one (no recent bearing at speed) the position is unknown,
     * and a rider braking to a stop at the camera must not be declared clear.
     */
    private fun isBehind(
        fix: Fix,
        camera: Camera,
        distance: Double,
    ): Boolean {
        val enforced = camera.bearingDeg
        if (enforced != null) {
            if (distance < AXIS_MIN_DISTANCE_M) return false
            val fromCamera = GeoMath.bearingDegrees(camera.lat, camera.lon, fix.lat, fix.lon)
            return angularDifference(fromCamera, enforced) < AHEAD_HALF_PLANE_DEG
        }
        val travel = effectiveBearing(fix) ?: return false
        val toCamera = GeoMath.bearingDegrees(fix.lat, fix.lon, camera.lat, camera.lon)
        return angularDifference(travel, toCamera) > AHEAD_HALF_PLANE_DEG
    }

    /**
     * Fails open only when no direction is known at all: a bearingless
     * camera, or a rider with no bearing seen at speed within the memory.
     * On a bend inside the ring the heading can be 45–90° off the enforced
     * direction while the rider is plainly upstream on the camera's own
     * axis; that still counts, so the alert is not postponed to the last
     * 100 m. Oncoming traffic (heading opposite) never matches either way.
     */
    private fun bearingMatches(
        fix: Fix,
        camera: Camera,
    ): Boolean {
        val enforced = camera.bearingDeg ?: return true
        val travel = effectiveBearing(fix) ?: return true
        val headingOff = angularDifference(travel, enforced)
        return when {
            headingOff <= config.bearingToleranceDeg -> true
            headingOff > AHEAD_HALF_PLANE_DEG -> false
            else -> {
                val toCamera = GeoMath.bearingDegrees(fix.lat, fix.lon, camera.lat, camera.lon)
                angularDifference(toCamera, enforced) <= config.bearingToleranceDeg
            }
        }
    }

    private fun updateBearingMemory(fix: Fix) {
        val bearing = fix.bearingDeg ?: return
        if (fix.speedMps < config.minSpeedForBearingMps) return
        lastReliableBearingDeg = bearing
        lastReliableBearingTimeMs = fix.timestampMs
    }

    /** Live bearing at speed, else the one remembered from the last fix at
     * speed while it is fresh; null when the direction is unknown. */
    private fun effectiveBearing(fix: Fix): Double? {
        if (fix.speedMps >= config.minSpeedForBearingMps && fix.bearingDeg != null) return fix.bearingDeg
        val remembered = lastReliableBearingDeg ?: return null
        if (fix.timestampMs - lastReliableBearingTimeMs > BEARING_MEMORY_MS) return null
        return remembered
    }

    companion object {
        /** At or above this speed the high-speed alert distance applies. */
        const val HIGH_SPEED_THRESHOLD_KMH = 100.0

        /** A passed camera counts as "ahead" while within this angle of the travel bearing. */
        const val AHEAD_HALF_PLANE_DEG = 90.0

        /** Fallback pass detection: clear once this much farther than the closest approach. */
        const val PASS_CLEAR_MARGIN_M = 75.0

        /** Fixes with worse accuracy than this decide nothing — neither firing
         * nor passing; the 99 m no-accuracy sentinel still participates. */
        const val ACCURACY_GATE_M = 100.0

        /** Closer than this the camera→rider bearing is noise; the axis test waits for the next fix. */
        const val AXIS_MIN_DISTANCE_M = 25.0

        /** A bearing seen at speed stays trustworthy this long while crawling or stopped. */
        const val BEARING_MEMORY_MS = 120_000L

        /** Beyond this distance a pending pass is forgotten regardless of fix quality. */
        const val PASS_FORGET_DISTANCE_M = 1_500.0

        fun angularDifference(
            a: Double,
            b: Double,
        ): Double {
            val diff = abs(a - b) % 360.0
            return min(diff, 360.0 - diff)
        }
    }
}
