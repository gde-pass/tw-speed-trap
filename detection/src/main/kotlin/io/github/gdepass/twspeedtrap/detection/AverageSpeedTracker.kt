package io.github.gdepass.twspeedtrap.detection

import kotlin.math.abs
import kotlin.math.cos
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
        /** Signed along-track offset of the entry fix from its gantry (negative = short of it). */
        val entryAlongM: Double,
        var warned: Boolean = false,
    ) {
        /** Straight-line entry→exit distance; the road is [Section.lengthM] long. */
        private val chordM = GeoMath.distanceMeters(entry.lat, entry.lon, exit.lat, exit.lon)

        /**
         * Road distance still to ride, from the straight-line distance to the
         * exit. A curved section is longer than its chord (壽卡: 6.0 km of road
         * on a 3.5 km chord), so projecting the straight-line remainder at
         * the current speed would overstate the average by the same ratio and
         * warn a rider holding exactly the limit. Scaling by length/chord is
         * exact at both gantries and the only honest estimate in between.
         */
        fun remainingRoadM(remainingM: Double): Double {
            if (chordM < 1.0) return remainingM
            return (remainingM * section.lengthM / chordM).coerceIn(0.0, section.lengthM)
        }
    }

    /** Fixes inside an entry ball whose direction of travel is not yet known. */
    private class PendingEntry(
        val anchor: Fix,
        val candidates: MutableList<Camera>,
        /** Per candidate id: closest approach so far (distance, fix timestamp). */
        val bestByCandidate: MutableMap<String, Pair<Double, Long>>,
    )

    private val sectionEndpoints = endpoints.filter { it.type == CameraType.SECTION }
    private val exitsBySection: Map<String?, Camera> =
        sectionEndpoints
            .filter { it.sectionRole == "end" }
            .groupBy { it.sectionId }
            .mapValues { (_, exits) -> exits.minBy { it.id } }
    private val entries =
        sectionEndpoints.filter {
            it.sectionRole == "start" &&
                it.sectionId != null &&
                it.sectionId in sections &&
                it.sectionId in exitsBySection
        }

    /** Section endpoints that cannot take part — an entry whose section or
     * exit is missing, a second exit for one section — counted, never silent,
     * so a pipeline regression shows up in the service log instead of as a
     * section that quietly stopped announcing. */
    val unusableEndpoints: Int =
        sectionEndpoints.count { it.sectionRole == "start" && it !in entries } +
            sectionEndpoints.count { it.sectionRole == "end" } - exitsBySection.size
    private var active: Traversal? = null
    private var pending: PendingEntry? = null

    /** Entry gantries announced ahead and not yet re-armed: id → ring that fired. */
    private val preAlerted = HashMap<String, Double>()
    private var lastReliableBearingDeg: Double? = null
    private var lastReliableBearingTimeMs: Long = Long.MIN_VALUE
    private var lastGeometricFixMs: Long = Long.MIN_VALUE

    /** True while a section traversal is in progress. */
    val isActive: Boolean get() = active != null

    /** Live view of the traversal for the UI: (section, projected exit average
     * km/h). Frozen at the last good value while stopped — a projection
     * through zero speed is meaningless. Null outside sections. */
    var liveStatus: Pair<Section, Int>? = null
        private set

    fun onFix(fix: Fix): List<AlertEvent> {
        updateBearingMemory(fix)
        val current = active
        val progressEvents = if (current != null) trackProgress(current, fix) else emptyList()
        // Re-check entry on the fix that closed a traversal: chained sections
        // (e.g. 觀音隧道 exit → 谷風 entry, 78 m apart) share bridge fixes.
        val entryEvents = if (active == null) checkEntry(fix) else emptyList()
        val aheadEvents = if (active == null) checkPreAlerts(fix) else emptyList()
        if (fix.accuracyM <= ACCURACY_GATE_M) lastGeometricFixMs = fix.timestampMs
        updateLiveStatus(fix)
        return progressEvents + aheadEvents + entryEvents
    }

    // ---- pre-alert -----------------------------------------------------------

    /**
     * "Zone ahead" at the point-camera ring, so the rider can settle on the
     * limit before the gantry instead of hearing about it at the gantry. Same
     * rules as a point camera: trusted fix, direction known (no fail-open — a
     * rider leaving the opposite direction's zone passes this gantry's twin),
     * gantry still ahead along its axis, once per approach with re-arm
     * beyond ring × rearmFactor. Inside the entry ball the entry itself speaks.
     */
    private fun checkPreAlerts(fix: Fix): List<AlertEvent> {
        sweepPreAlerted(fix)
        if (fix.accuracyM > ACCURACY_GATE_M) return emptyList()
        val travel = effectiveBearing(fix) ?: return emptyList()
        val ring =
            if (fix.speedMps * MPS_TO_KMH >= AlertEngine.HIGH_SPEED_THRESHOLD_KMH) {
                config.highSpeedAlertDistanceM
            } else {
                config.alertDistanceM
            }
        var best: Pair<Camera, Double>? = null
        for (entry in entries) {
            val distance = preAlertDistance(entry, fix, travel, ring) ?: continue
            if (best == null || distance < best.second) best = entry to distance
        }
        val (entry, distance) = best ?: return emptyList()
        preAlerted[entry.id] = ring
        return listOf(AlertEvent.SectionAhead(sections.getValue(entry.sectionId!!), distance))
    }

    /** Distance to [entry] when it qualifies for a pre-alert on this fix, else null. */
    private fun preAlertDistance(
        entry: Camera,
        fix: Fix,
        travel: Double,
        ring: Double,
    ): Double? {
        if (entry.id in preAlerted) return null
        val outsideBox =
            abs(fix.lat - entry.lat) > PRE_ALERT_PREFILTER_DEG || abs(fix.lon - entry.lon) > PRE_ALERT_PREFILTER_DEG
        if (outsideBox) return null
        val distance = GeoMath.distanceMeters(fix.lat, fix.lon, entry.lat, entry.lon)
        // Inside the entry ball the entry itself speaks.
        if (distance > ring || distance <= ENDPOINT_RADIUS_M) return null
        return if (approaching(entry, travel, fix)) distance else null
    }

    /** Re-arms by distance on every fix, so a gantry left behind in one jump cannot stay silenced. */
    private fun sweepPreAlerted(fix: Fix) {
        if (preAlerted.isEmpty()) return
        val iterator = preAlerted.entries.iterator()
        while (iterator.hasNext()) {
            val (id, ring) = iterator.next()
            val entry = entries.firstOrNull { it.id == id } ?: continue
            if (GeoMath.distanceMeters(fix.lat, fix.lon, entry.lat, entry.lon) >
                ring * config.rearmFactor
            ) {
                iterator.remove()
            }
        }
    }

    /** Heading roughly along the gantry's axis and still upstream of it. */
    private fun approaching(
        entry: Camera,
        travel: Double,
        fix: Fix,
    ): Boolean {
        val exit = exitsBySection.getValue(entry.sectionId)
        val axis = entry.bearingDeg ?: GeoMath.bearingDegrees(entry.lat, entry.lon, exit.lat, exit.lon)
        if (AlertEngine.angularDifference(travel, axis) > config.bearingToleranceDeg) return false
        val toGantry = GeoMath.bearingDegrees(fix.lat, fix.lon, entry.lat, entry.lon)
        return AlertEngine.angularDifference(toGantry, axis) <= HALF_PLANE_DEG
    }

    private fun updateLiveStatus(fix: Fix) {
        val traversal = active
        if (traversal == null) {
            liveStatus = null
            return
        }
        val elapsedS = (fix.timestampMs - traversal.entryTimeMs) / 1000.0
        if (fix.speedMps <= 1.0 || elapsedS <= 0.0 || fix.accuracyM > ACCURACY_GATE_M) {
            // Stopped or untrusted fix: freeze; before any projection exists,
            // "riding at the limit" is the only honest placeholder.
            if (liveStatus == null) liveStatus = traversal.section to traversal.section.speedLimitKmh
            return
        }
        val remainingM = GeoMath.distanceMeters(fix.lat, fix.lon, traversal.exit.lat, traversal.exit.lon)
        liveStatus = traversal.section to projectedKmh(traversal, fix, elapsedS, remainingM)
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
            // The entry and exit fixes each sit up to a fix step (or, after a
            // tunnel gap, up to 60 m) off their gantry. The distance actually
            // ridden between the two fixes is the section length plus the
            // signed along-track offsets; without them a 487 m tunnel exited
            // 46 m late read 55 km/h for a true 60.
            val riddenM = section.lengthM + alongAxis(traversal.exit, traversal, fix) - traversal.entryAlongM
            return exitEvents(traversal, elapsedS, riddenM, overshootM = 0.0, estimated = false)
        }
        // First fix past the exit after a GPS gap (tunnel reacquisition). The
        // gap gate keeps curved sections from tripping this under continuous
        // coverage, where the exit ball itself is guaranteed to catch a fix.
        if (passedExit(traversal, fix) && fix.timestampMs - lastGeometricFixMs >= OVERSHOOT_MIN_GAP_MS) {
            return exitEvents(traversal, elapsedS, section.lengthM + remainingM, remainingM, estimated = true)
        }
        val distFromEntryM = GeoMath.distanceMeters(fix.lat, fix.lon, traversal.entry.lat, traversal.entry.lon)
        // Corridor bound, safe on curves: every point of the section road is
        // within lengthM of the entry *and* of the exit by road, so its
        // straight-line distances sum to at most lengthM. Outside that
        // ellipse the rider has left the section (turned off after the
        // portal) — the old entry-only bound let a 8 km section linger for
        // 9 km of unrelated riding.
        if (behindEntry(traversal, fix, distFromEntryM) ||
            distFromEntryM + remainingM > section.lengthM + CORRIDOR_MARGIN_M
        ) {
            return abandon()
        }
        return overPaceEvents(traversal, fix, elapsedS, remainingM)
    }

    /** Signed distance of [fix] past [gantry] along the gantry's axis
     * (negative = still short of it); the entry→exit bearing stands in for
     * a gantry without one. */
    private fun alongAxis(
        gantry: Camera,
        traversal: Traversal,
        fix: Fix,
    ): Double = alongAxis(gantry, gantry.bearingDeg ?: chordBearing(traversal), fix)

    private fun chordBearing(traversal: Traversal): Double =
        GeoMath.bearingDegrees(traversal.entry.lat, traversal.entry.lon, traversal.exit.lat, traversal.exit.lon)

    private fun alongAxis(
        gantry: Camera,
        axisDeg: Double,
        fix: Fix,
    ): Double {
        val distance = GeoMath.distanceMeters(gantry.lat, gantry.lon, fix.lat, fix.lon)
        val toFix = GeoMath.bearingDegrees(gantry.lat, gantry.lon, fix.lat, fix.lon)
        return distance * cos(Math.toRadians(AlertEngine.angularDifference(toFix, axisDeg)))
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
        val projectedKmh = projectedKmh(traversal, fix, elapsedS, remainingM)
        if (projectedKmh <= traversal.section.speedLimitKmh + config.speedToleranceKmh) return null
        traversal.warned = true
        return listOf(AlertEvent.SectionOverPace(traversal.section, projectedKmh))
    }

    /** Section average the rider is heading for if the current speed is held to the exit. */
    private fun projectedKmh(
        traversal: Traversal,
        fix: Fix,
        elapsedS: Double,
        remainingM: Double,
    ): Int {
        val projectedTotalS = elapsedS + traversal.remainingRoadM(remainingM) / fix.speedMps
        return (traversal.section.lengthM / projectedTotalS * MPS_TO_KMH).roundToInt()
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
        // Backdating is bounded: a rider who parked at a shared portal for
        // ten minutes and rode on crossed the gantry when they moved off,
        // not when they arrived — unbounded, the average read 22 km/h for a
        // true 60.
        val startMs =
            entryTimeMs
                ?: best[chosen.id]?.second?.coerceAtLeast(fix.timestampMs - MAX_BACKDATE_MS)
                ?: fix.timestampMs
        val exit = exitsBySection.getValue(chosen.sectionId)
        // A live entry records where the fix sat relative to the gantry; a
        // backdated crawl entry is timed at the closest approach, i.e. at it.
        val entryAlongM =
            if (entryTimeMs != null) {
                val axis = chosen.bearingDeg ?: GeoMath.bearingDegrees(chosen.lat, chosen.lon, exit.lat, exit.lon)
                alongAxis(chosen, axis, fix)
            } else {
                0.0
            }
        active = Traversal(section, chosen, exit, startMs, entryAlongM)
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

        /** A crawl entry is backdated to its closest approach, but never further than this. */
        const val MAX_BACKDATE_MS = 30_000L

        /** Bounding-box prefilter (~110 m) so 19 island-wide entries cost comparisons, not haversines. */
        const val ENTRY_PREFILTER_DEG = 0.001

        /** Pre-alert prefilter: covers the widest ring the settings allow (600 m) at Taiwan's latitudes. */
        const val PRE_ALERT_PREFILTER_DEG = 0.007
        private const val MPS_TO_KMH = 3.6
    }
}
