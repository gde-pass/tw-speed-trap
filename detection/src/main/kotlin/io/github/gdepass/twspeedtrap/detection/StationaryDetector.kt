package io.github.gdepass.twspeedtrap.detection

/**
 * Position-anchored stationary detection for auto-stop.
 *
 * Position is primary: the rider is stationary only when every trusted fix
 * over a [holdMs] window stays within [radiusM] of one anchor. Speed is only
 * ever a veto — a provider that fabricates 0 m/s while riding (the
 * hasSpeed() == false fallback) has no pathway to stop detection, because
 * genuine movement breaks position confinement instead. Every timestamp
 * anomaly (backwards clock, fix outage) resets the window, so the failure
 * mode is always "keeps running", never "stops mid-ride".
 */
class StationaryDetector(
    private val radiusM: Double = RADIUS_M,
    private val holdMs: Long = HOLD_MS,
) {
    private var anchor: Fix? = null
    private var windowStartMs: Long = 0L
    private var lastTimestampMs: Long = Long.MIN_VALUE

    /** True when the rider has provably been stationary for [holdMs]. */
    fun onFix(fix: Fix): Boolean {
        val sinceLast = fix.timestampMs - lastTimestampMs
        val timestampAnomaly = lastTimestampMs != Long.MIN_VALUE && (sinceLast < 0 || sinceLast > GAP_RESET_MS)
        lastTimestampMs = fix.timestampMs
        if (timestampAnomaly) anchor = null
        if (fix.accuracyM > ACCURACY_IGNORE_M) return false
        val current = anchor
        return when {
            fix.speedMps >= SPEED_VETO_MPS && fix.accuracyM <= SPEED_VETO_MAX_ACCURACY_M -> {
                // Confidently moving (tight loops stay inside the radius):
                // restart the clock but keep the anchor.
                windowStartMs = fix.timestampMs
                false
            }
            current == null || GeoMath.distanceMeters(fix.lat, fix.lon, current.lat, current.lon) > radiusM -> {
                restartAt(fix)
                false
            }
            else -> fix.timestampMs - windowStartMs >= holdMs
        }
    }

    fun reset() {
        anchor = null
        windowStartMs = 0L
        lastTimestampMs = Long.MIN_VALUE
    }

    private fun restartAt(fix: Fix) {
        anchor = fix
        windowStartMs = fix.timestampMs
    }

    companion object {
        /** Parked GPS drift wanders tens of meters; excursions past this merely delay the stop. */
        const val RADIUS_M = 100.0
        const val HOLD_MS = 10 * 60_000L

        /** Outages or clock jumps longer than this restart the count. */
        const val GAP_RESET_MS = 5 * 60_000L

        /** Garbage fixes decide nothing; the 99 m no-accuracy sentinel still participates. */
        const val ACCURACY_IGNORE_M = 100.0

        /** Above walking pace with a trustworthy fix: definitely moving. */
        const val SPEED_VETO_MPS = 2.0
        const val SPEED_VETO_MAX_ACCURACY_M = 30.0
    }
}
