package io.github.gdepass.twspeedtrap.detection

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tracks average-speed (區間測速) sections.
 *
 * The final average uses the official section length and the entry/exit
 * timestamps — not the GPS path — so it stays correct through tunnels where
 * GPS dies. Exit detection is tunnel-safe: if GPS reacquires past the exit
 * gantry (the normal case after a long tunnel), the first fix beyond the exit
 * closes the traversal with an average over length + overshoot, flagged
 * [AlertEvent.SectionExited.estimated]. A traversal can also end by U-turn
 * (fix behind the entry), by leaving the corridor (further from the entry
 * than length + margin), by a backwards clock jump, or by the timeout — so a
 * missed exit never blocks the next section entry for long, and the entry
 * check re-runs on the same fix that closed a traversal (chained sections).
 *
 * Entry requires a direction: live GPS bearing at speed, a recent remembered
 * bearing, or a displacement-derived bearing while crawling. There is no
 * fail-open at low speed — five shipped section pairs share exactly colocated
 * opposite-direction endpoints, and entering the wrong one used to lock
 * detection out for the whole timeout.
 *
 * Known limits (information-theoretic, documented on purpose): with zero
 * fixes between two chained tunnels the second section cannot be entered; a
 * gantry more than ~55 m lateral of the ridden track can be straddled by
 * 1 Hz fixes at highway speed without a gap, which ends the traversal
 * silently. Point-camera alerts are unaffected in both cases.
 */
class AverageSpeedTracker(
    endpoints: List<Camera>,
    private val sections: Map<String, Section>,
    private val config: EngineConfig = EngineConfig(),
) {
    private class Traversal(
        val section: Section,
        val entry: Camera,
        val exit: Camera,
        val entryTimeMs: Long,
        var warned: Boolean = false,
    )

    /** Fixes inside an entry ball whose direction of travel is not yet known. */
    private class PendingEntry(
        val anchor: Fix,
        val candidates: MutableList<Camera>,
        /** Per candidate id: closest approach so far (distance, fix timestamp). */
        val bestByCandidate: MutableMap<String, Pair<Double, Long>>,
    )

    private val exitsBySection =
        endpoints
            .filter { it.type == CameraType.SECTION && it.sectionRole == "end" }
            .associateBy { it.sectionId }
    private val entries =
        endpoints.filter {
            it.type == CameraType.SECTION &&
                it.sectionRole == "start" &&
                it.sectionId != null &&
                it.sectionId in sections &&
                it.sectionId in exitsBySection
        }
    private var active: Traversal? = null
    private var pending: PendingEntry? = null
    private var lastReliableBearingDeg: Double? = null
    private var lastReliableBearingTimeMs: Long = Long.MIN_VALUE
    private var lastGeometricFixMs: Long = Long.MIN_VALUE

    /** True while a section traversal is in progress. */
    val isActive: Boolean get() = active != null

    fun onFix(fix: Fix): List<AlertEvent> {
        updateBearingMemory(fix)
        val current = active
        val progressEvents = if (current != null) trackProgress(current, fix) else emptyList()
        // Re-check entry on the fix that closed a traversal: chained sections
        // (e.g. 觀音隧道 exit → 谷風 entry, 78 m apart) share bridge fixes.
        val entryEvents = if (active == null) checkEntry(fix) else emptyList()
        if (fix.accuracyM <= ACCURACY_GATE_M) lastGeometricFixMs = fix.timestampMs
        return when {
            entryEvents.isEmpty() -> progressEvents
            progressEvents.isEmpty() -> entryEvents
            else -> progressEvents + entryEvents
        }
    }

    private fun updateBearingMemory(fix: Fix) {
        val bearing = fix.bearingDeg ?: return
        if (fix.speedMps < config.minSpeedForBearingMps) return
        lastReliableBearingDeg = bearing
        lastReliableBearingTimeMs = fix.timestampMs
    }

    // ---- traversal progress ------------------------------------------------

    private fun trackProgress(
        traversal: Traversal,
        fix: Fix,
    ): List<AlertEvent> {
        val elapsedS = (fix.timestampMs - traversal.entryTimeMs) / 1000.0
        if (elapsedS < 0.0) return abandon() // clock jumped backwards
        val geometric = if (fix.accuracyM <= ACCURACY_GATE_M) geometricEvents(traversal, fix, elapsedS) else null
        if (geometric != null) return geometric
        if (elapsedS > TIMEOUT_S) return abandon()
        return emptyList()
    }

    /** Geometric decisions for a trusted fix; null = nothing decided yet. */
    private fun geometricEvents(
        traversal: Traversal,
        fix: Fix,
        elapsedS: Double,
    ): List<AlertEvent>? {
        val section = traversal.section
        val remainingM = GeoMath.distanceMeters(fix.lat, fix.lon, traversal.exit.lat, traversal.exit.lon)
        if (remainingM <= ENDPOINT_RADIUS_M) {
            return exitEvents(traversal, elapsedS, section.lengthM, overshootM = 0.0, estimated = false)
        }
        // First fix past the exit after a GPS gap (tunnel reacquisition). The
        // gap gate keeps curved sections from tripping this under continuous
        // coverage, where the exit ball itself is guaranteed to catch a fix.
        if (passedExit(traversal, fix) && fix.timestampMs - lastGeometricFixMs >= OVERSHOOT_MIN_GAP_MS) {
            return exitEvents(traversal, elapsedS, section.lengthM + remainingM, remainingM, estimated = true)
        }
        val distFromEntryM = GeoMath.distanceMeters(fix.lat, fix.lon, traversal.entry.lat, traversal.entry.lon)
        // Corridor bound is safe on curves: any point on the section road is at
        // most lengthM from the entry in road distance, hence straight-line too.
        if (behindEntry(traversal, fix, distFromEntryM) ||
            distFromEntryM > section.lengthM + CORRIDOR_MARGIN_M
        ) {
            return abandon()
        }
        return overPaceEvents(traversal, fix, elapsedS, remainingM)
    }

    private fun passedExit(
        traversal: Traversal,
        fix: Fix,
    ): Boolean {
        val travelBearing =
            traversal.exit.bearingDeg
                ?: GeoMath.bearingDegrees(
                    traversal.entry.lat,
                    traversal.entry.lon,
                    traversal.exit.lat,
                    traversal.exit.lon,
                )
        val toFix = GeoMath.bearingDegrees(traversal.exit.lat, traversal.exit.lon, fix.lat, fix.lon)
        return AlertEngine.angularDifference(toFix, travelBearing) < HALF_PLANE_DEG
    }

    private fun behindEntry(
        traversal: Traversal,
        fix: Fix,
        distFromEntryM: Double,
    ): Boolean {
        if (distFromEntryM <= BACKWARD_ABANDON_M) return false
        val travelBearing =
            traversal.entry.bearingDeg
                ?: GeoMath.bearingDegrees(
                    traversal.entry.lat,
                    traversal.entry.lon,
                    traversal.exit.lat,
                    traversal.exit.lon,
                )
        val toFix = GeoMath.bearingDegrees(traversal.entry.lat, traversal.entry.lon, fix.lat, fix.lon)
        return AlertEngine.angularDifference(toFix, travelBearing) > HALF_PLANE_DEG
    }

    private fun overPaceEvents(
        traversal: Traversal,
        fix: Fix,
        elapsedS: Double,
        remainingM: Double,
    ): List<AlertEvent>? {
        if (traversal.warned || fix.speedMps <= 1.0) return null
        val projectedTotalS = elapsedS + remainingM / fix.speedMps
        val projectedKmh = (traversal.section.lengthM / projectedTotalS * MPS_TO_KMH).roundToInt()
        if (projectedKmh <= traversal.section.speedLimitKmh + config.speedToleranceKmh) return null
        traversal.warned = true
        return listOf(AlertEvent.SectionOverPace(traversal.section, projectedKmh))
    }

    private fun abandon(): List<AlertEvent> {
        active = null
        return emptyList()
    }

    private fun exitEvents(
        traversal: Traversal,
        elapsedS: Double,
        travelledM: Double,
        overshootM: Double,
        estimated: Boolean,
    ): List<AlertEvent> {
        active = null
        val section = traversal.section
        // Faster than any road vehicle plausibly traverses the section: the
        // entry timestamp is corrupt (clock jump) or this is a drive-by of a
        // colocated portal — announcing would yield absurdities like 7200 km/h.
        if (elapsedS < section.lengthM / MAX_PLAUSIBLE_MPS) return emptyList()
        if (estimated && overshootM > OVERSHOOT_ANNOUNCE_MAX_M) return emptyList()
        val averageKmh = (travelledM / elapsedS * MPS_TO_KMH).roundToInt()
        if (averageKmh < MIN_AVG_ANNOUNCE_KMH || averageKmh > MAX_AVG_ANNOUNCE_KMH) return emptyList()
        val overLimit = averageKmh > section.speedLimitKmh + config.speedToleranceKmh
        return listOf(AlertEvent.SectionExited(section, averageKmh, overLimit, estimated))
    }

    // ---- entry detection ---------------------------------------------------

    private fun checkEntry(fix: Fix): List<AlertEvent> {
        if (fix.accuracyM > ACCURACY_GATE_M) return emptyList()
        expireStalePending(fix)
        val inBall = entriesNear(fix)
        val current = pending
        return when {
            current != null -> resolvePending(current, fix, inBall)
            inBall.isEmpty() -> emptyList()
            else -> {
                val bearing = effectiveBearing(fix)
                if (bearing == null) {
                    pending = newPending(fix, inBall)
                    emptyList()
                } else {
                    enterBest(inBall, bearing, fix, entryTimeMs = fix.timestampMs)
                }
            }
        }
    }

    private fun expireStalePending(fix: Fix) {
        val current = pending ?: return
        if (fix.timestampMs - current.anchor.timestampMs > PENDING_MAX_AGE_MS) pending = null
    }

    private fun newPending(
        fix: Fix,
        inBall: List<Camera>,
    ): PendingEntry {
        val best = HashMap<String, Pair<Double, Long>>()
        for (candidate in inBall) {
            best[candidate.id] =
                GeoMath.distanceMeters(fix.lat, fix.lon, candidate.lat, candidate.lon) to fix.timestampMs
        }
        return PendingEntry(fix, inBall.toMutableList(), best)
    }

    private fun resolvePending(
        current: PendingEntry,
        fix: Fix,
        inBall: List<Camera>,
    ): List<AlertEvent> {
        for (candidate in inBall) {
            if (current.candidates.none { it.id == candidate.id }) current.candidates.add(candidate)
        }
        var minDistance = Double.MAX_VALUE
        for (candidate in current.candidates) {
            val distance = GeoMath.distanceMeters(fix.lat, fix.lon, candidate.lat, candidate.lon)
            minDistance = minOf(minDistance, distance)
            val best = current.bestByCandidate[candidate.id]
            if (best == null || distance < best.first) {
                current.bestByCandidate[candidate.id] = distance to fix.timestampMs
            }
        }
        if (minDistance > PENDING_RESOLVE_MAX_M) {
            pending = null
            return emptyList()
        }
        val bearing = effectiveBearing(fix) ?: displacementBearing(current.anchor, fix) ?: return emptyList()
        pending = null
        return enterBest(current.candidates, bearing, fix, entryTimeMs = null, best = current.bestByCandidate)
    }

    /**
     * Enters the closest bearing-matching candidate. [entryTimeMs] null means
     * "backdate to the candidate's closest recorded approach" (crawl entries:
     * the crossing happened before the direction became known).
     */
    private fun enterBest(
        candidates: List<Camera>,
        travelBearing: Double,
        fix: Fix,
        entryTimeMs: Long?,
        best: Map<String, Pair<Double, Long>> = emptyMap(),
    ): List<AlertEvent> {
        val matching =
            candidates.filter { camera ->
                val enforced = camera.bearingDeg ?: return@filter true
                AlertEngine.angularDifference(travelBearing, enforced) <= config.bearingToleranceDeg
            }
        val chosen =
            matching.minWithOrNull(
                compareBy({ GeoMath.distanceMeters(fix.lat, fix.lon, it.lat, it.lon) }, { it.id }),
            ) ?: return emptyList()
        val section = sections.getValue(chosen.sectionId!!)
        val startMs = entryTimeMs ?: best[chosen.id]?.second ?: fix.timestampMs
        active = Traversal(section, chosen, exitsBySection.getValue(chosen.sectionId), startMs)
        return listOf(AlertEvent.SectionEntered(section))
    }

    private fun entriesNear(fix: Fix): List<Camera> =
        entries.filter {
            abs(fix.lat - it.lat) <= ENTRY_PREFILTER_DEG &&
                abs(fix.lon - it.lon) <= ENTRY_PREFILTER_DEG &&
                GeoMath.distanceMeters(fix.lat, fix.lon, it.lat, it.lon) <= ENDPOINT_RADIUS_M
        }

    private fun effectiveBearing(fix: Fix): Double? {
        if (fix.speedMps >= config.minSpeedForBearingMps && fix.bearingDeg != null) return fix.bearingDeg
        val remembered = lastReliableBearingDeg ?: return null
        if (fix.timestampMs - lastReliableBearingTimeMs > BEARING_MEMORY_MS) return null
        return remembered
    }

    private fun displacementBearing(
        anchor: Fix,
        fix: Fix,
    ): Double? {
        val displacementM = GeoMath.distanceMeters(anchor.lat, anchor.lon, fix.lat, fix.lon)
        val threshold = max(PENDING_MIN_DISPLACEMENT_M, 2.0 * max(anchor.accuracyM, fix.accuracyM))
        if (displacementM < threshold) return null
        return GeoMath.bearingDegrees(anchor.lat, anchor.lon, fix.lat, fix.lon)
    }

    companion object {
        /** How close to an endpoint counts as crossing it (GPS noise + 1 Hz at 110 km/h ≈ 30 m/tick). */
        const val ENDPOINT_RADIUS_M = 60.0
        const val TIMEOUT_S = 30.0 * 60.0

        /** Fixes worse than this decide nothing geometric; the 99 m no-accuracy sentinel still participates. */
        const val ACCURACY_GATE_M = 100.0

        /** Estimated exits need a fix gap: continuous 1 Hz coverage always hits the exit ball instead. */
        const val OVERSHOOT_MIN_GAP_MS = 2_500L

        /** Beyond this overshoot the average is too contaminated to announce (state still clears). */
        const val OVERSHOOT_ANNOUNCE_MAX_M = 500.0

        /** 250 km/h — faster entry-to-exit than this means a corrupt entry timestamp, not a ride. */
        const val MAX_PLAUSIBLE_MPS = 250.0 / 3.6
        const val MIN_AVG_ANNOUNCE_KMH = 5
        const val MAX_AVG_ANNOUNCE_KMH = 250

        /** U-turn detection: behind the entry half-plane by more than this. Generous for hairpins near portals. */
        const val BACKWARD_ABANDON_M = 250.0
        const val CORRIDOR_MARGIN_M = 1_000.0
        const val HALF_PLANE_DEG = 90.0

        /** A bearing seen at speed stays trustworthy this long while stopped at a portal. */
        const val BEARING_MEMORY_MS = 120_000L
        const val PENDING_RESOLVE_MAX_M = 500.0
        const val PENDING_MIN_DISPLACEMENT_M = 30.0
        const val PENDING_MAX_AGE_MS = 600_000L

        /** Bounding-box prefilter (~110 m) so 19 island-wide entries cost comparisons, not haversines. */
        const val ENTRY_PREFILTER_DEG = 0.001
        private const val MPS_TO_KMH = 3.6
    }
}
