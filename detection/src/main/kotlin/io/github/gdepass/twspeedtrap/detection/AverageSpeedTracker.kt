package io.github.gdepass.twspeedtrap.detection

import kotlin.math.roundToInt

/**
 * Tracks average-speed (區間測速) sections.
 *
 * The final average uses the official section length and the entry/exit
 * timestamps — not the GPS path — so it stays correct through tunnels where
 * GPS dies. The in-section pace warning projects the achievable average:
 * elapsed time so far plus the straight-line remaining distance at current
 * speed; it fires once per traversal.
 */
class AverageSpeedTracker(
    endpoints: List<Camera>,
    private val sections: Map<String, Section>,
    private val config: EngineConfig = EngineConfig(),
) {
    private data class Traversal(
        val section: Section,
        val exit: Camera,
        val entryTimeMs: Long,
        var warned: Boolean = false,
    )

    private val entries = endpoints.filter { it.type == CameraType.SECTION && it.sectionRole == "start" }
    private val exitsBySection =
        endpoints
            .filter { it.type == CameraType.SECTION && it.sectionRole == "end" }
            .associateBy { it.sectionId }
    private var active: Traversal? = null

    fun onFix(fix: Fix): List<AlertEvent> {
        val current = active
        return if (current == null) checkEntry(fix) else trackProgress(current, fix)
    }

    private fun checkEntry(fix: Fix): List<AlertEvent> {
        val entry = entries.firstOrNull { isCrossing(fix, it) } ?: return emptyList()
        val section = sections.getValue(entry.sectionId!!)
        active = Traversal(section, exitsBySection.getValue(entry.sectionId), fix.timestampMs)
        return listOf(AlertEvent.SectionEntered(section))
    }

    private fun isCrossing(
        fix: Fix,
        endpoint: Camera,
    ): Boolean =
        endpoint.sectionId in sections &&
            endpoint.sectionId in exitsBySection &&
            GeoMath.distanceMeters(fix.lat, fix.lon, endpoint.lat, endpoint.lon) <= ENDPOINT_RADIUS_M &&
            bearingMatches(fix, endpoint)

    private fun trackProgress(
        traversal: Traversal,
        fix: Fix,
    ): List<AlertEvent> {
        val section = traversal.section
        val elapsedS = (fix.timestampMs - traversal.entryTimeMs) / 1000.0
        if (elapsedS > TIMEOUT_S) {
            // Missed the exit (turned off inside the zone): give up silently.
            active = null
            return emptyList()
        }

        val remainingM = GeoMath.distanceMeters(fix.lat, fix.lon, traversal.exit.lat, traversal.exit.lon)
        if (remainingM <= ENDPOINT_RADIUS_M) {
            active = null
            val averageKmh = (section.lengthM / (elapsedS.coerceAtLeast(1.0)) * 3.6).roundToInt()
            val overLimit = averageKmh > section.speedLimitKmh + config.speedToleranceKmh
            return listOf(AlertEvent.SectionExited(section, averageKmh, overLimit))
        }

        if (!traversal.warned && fix.speedMps > 1.0) {
            val projectedTotalS = elapsedS + remainingM / fix.speedMps
            val projectedKmh = (section.lengthM / projectedTotalS * 3.6).roundToInt()
            if (projectedKmh > section.speedLimitKmh + config.speedToleranceKmh) {
                traversal.warned = true
                return listOf(AlertEvent.SectionOverPace(section, projectedKmh))
            }
        }
        return emptyList()
    }

    private fun bearingMatches(
        fix: Fix,
        endpoint: Camera,
    ): Boolean {
        val enforced = endpoint.bearingDeg ?: return true
        if (fix.speedMps < config.minSpeedForBearingMps) return true
        val travel = fix.bearingDeg ?: return true
        return AlertEngine.angularDifference(travel, enforced) <= config.bearingToleranceDeg
    }

    companion object {
        /** How close to an endpoint counts as crossing it (GPS noise + 1 Hz at 110 km/h ≈ 30 m/tick). */
        const val ENDPOINT_RADIUS_M = 60.0
        const val TIMEOUT_S = 30.0 * 60.0
    }
}
