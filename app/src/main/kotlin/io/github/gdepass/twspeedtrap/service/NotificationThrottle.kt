package io.github.gdepass.twspeedtrap.service

/**
 * Decides when the foreground notification is worth re-posting. Each post is
 * a binder call to NotificationManager at what used to be 1 Hz for hours;
 * most of them rendered identical text. Distance-bucket changes (a camera
 * appearing, counting down, or disappearing) always render immediately —
 * only speed-only refreshes are rate-limited. Pure; the clock is injected.
 */
class NotificationThrottle(
    private val minSpeedRefreshMs: Long = SPEED_REFRESH_MS,
) {
    data class Rendered(
        val speedKmh: Int,
        val distanceBucketM: Int?,
    )

    private var last: Rendered? = null
    private var lastNotifyMs = 0L

    /** True when the notification must be re-posted for this state. */
    fun shouldNotify(
        state: Rendered,
        nowMs: Long,
    ): Boolean {
        val previous = last
        val notify =
            when {
                previous == null -> true
                nowMs < lastNotifyMs -> true // clock went backwards: recover
                state.distanceBucketM != previous.distanceBucketM -> true
                state.speedKmh != previous.speedKmh && nowMs - lastNotifyMs >= minSpeedRefreshMs -> true
                else -> false
            }
        if (notify) {
            last = state
            lastNotifyMs = nowMs
        }
        return notify
    }

    companion object {
        const val SPEED_REFRESH_MS = 5_000L

        /** Coarse displayed distance; floor, so a camera never looks farther than it is. */
        fun bucket(distanceM: Int): Int =
            when {
                distanceM >= 1000 -> distanceM / 250 * 250
                distanceM >= 300 -> distanceM / 100 * 100
                else -> (distanceM / 50 * 50).coerceAtLeast(50)
            }
    }
}
