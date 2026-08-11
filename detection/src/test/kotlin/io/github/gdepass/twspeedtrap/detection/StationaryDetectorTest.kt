package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationaryDetectorTest {
    private val baseLat = 24.16000
    private val baseLon = 120.65000
    private val degPerMeter = 1.0 / 110_540.0

    private fun fix(
        northM: Double = 0.0,
        speed: Double = 0.0,
        acc: Double = 10.0,
        tSec: Double,
    ) = Fix(
        lat = baseLat + northM * degPerMeter,
        lon = baseLon,
        speedMps = speed,
        bearingDeg = null,
        accuracyM = acc,
        timestampMs = (tSec * 1000.0).toLong(),
    )

    /** Feeds [fixes] and returns the time of the first stationary verdict, if any. */
    private fun firstStopAt(fixes: List<Fix>): Double? {
        val detector = StationaryDetector()
        for (f in fixes) {
            if (detector.onFix(f)) return f.timestampMs / 1000.0
        }
        return null
    }

    private fun parkedFixes(
        untilSec: Double,
        stepSec: Double = 5.0,
        northM: (Double) -> Double = { if ((it / 5.0).toInt() % 2 == 0) 10.0 else -10.0 },
        speed: (Double) -> Double = { 0.0 },
        acc: (Double) -> Double = { 10.0 },
        tOverride: (Double) -> Double = { it },
    ): List<Fix> =
        generateSequence(0.0) { it + stepSec }
            .takeWhile { it <= untilSec }
            .map { t -> fix(northM = northM(t), speed = speed(t), acc = acc(t), tSec = tOverride(t)) }
            .toList()

    @Test
    fun `parked with drift stops at exactly the hold time`() {
        val fixes =
            parkedFixes(untilSec = 660.0, northM = {
                if (it ==
                    300.0
                ) {
                    90.0
                } else if ((it / 5.0).toInt() % 2 == 0) {
                    10.0
                } else {
                    -10.0
                }
            })
        assertEquals(600.0, firstStopAt(fixes))
    }

    @Test
    fun `a wide drift excursion delays but does not prevent the stop`() {
        val fixes =
            parkedFixes(untilSec = 1000.0, northM = {
                if (it ==
                    360.0
                ) {
                    120.0
                } else if ((it / 5.0).toInt() % 2 == 0) {
                    10.0
                } else {
                    -10.0
                }
            })
        assertEquals(965.0, firstStopAt(fixes))
    }

    @Test
    fun `riding with fabricated zero speed never stops`() {
        // Worst-case regression: the provider stops reporting speed at 130 km/h.
        val fixes =
            generateSequence(0.0) { it + 1.0 }
                .takeWhile { it <= 1800.0 }
                .map { t -> fix(northM = t * 36.1, speed = 0.0, tSec = t) }
                .toList()
        assertNull(firstStopAt(fixes), "position confinement must break while genuinely moving")
    }

    @Test
    fun `stop-and-go traffic never stops detection`() {
        val fixes = mutableListOf<Fix>()
        var t = 0.0
        var north = 0.0
        repeat(20) {
            repeat(9) {
                fixes.add(fix(northM = north, tSec = t))
                t += 5.0
            }
            north += 300.0 // next red light
        }
        assertNull(firstStopAt(fixes))
    }

    @Test
    fun `tight loops inside the radius are vetoed by speed`() {
        val fixes =
            generateSequence(0.0) { it + 5.0 }
                .takeWhile { it <= 1800.0 }
                .map { t -> fix(northM = 40.0 + 40.0 * kotlin.math.sin(t / 10.0), speed = 8.0, tSec = t) }
                .toList()
        assertNull(firstStopAt(fixes), "practice-pad loops must keep detection alive")
    }

    @Test
    fun `backward clock jump restarts the window`() {
        val fixes = parkedFixes(untilSec = 900.0, tOverride = { if (it < 300.0) it else it - 60.0 })
        // Restarted at the jump (t=240 on the jumped clock): stops 600 s later.
        assertEquals(840.0, firstStopAt(fixes))
    }

    @Test
    fun `short fix outage while parked is tolerated`() {
        val fixes = parkedFixes(untilSec = 660.0).filterNot { it.timestampMs in 301_000..539_000 }
        assertEquals(600.0, firstStopAt(fixes))
    }

    @Test
    fun `long fix outage restarts the window`() {
        val fixes = parkedFixes(untilSec = 1300.0).filterNot { it.timestampMs in 301_000..659_000 }
        assertEquals(1260.0, firstStopAt(fixes))
    }

    @Test
    fun `parked speed spikes only delay the stop`() {
        val spikes = setOf(180.0, 360.0, 540.0)
        val fixes =
            parkedFixes(
                untilSec = 1200.0,
                speed = { if (it in spikes) 2.5 else 0.0 },
                acc = { if (it in spikes) 25.0 else 10.0 },
            )
        assertEquals(1140.0, firstStopAt(fixes), "each trusted speed spike restarts the clock")
    }

    @Test
    fun `garbage accuracy fixes decide nothing`() {
        val fixes = parkedFixes(untilSec = 1800.0, acc = { 150.0 })
        assertNull(firstStopAt(fixes))
    }

    @Test
    fun `identical streams give identical verdicts`() {
        val fixes = parkedFixes(untilSec = 900.0)
        val first = StationaryDetector()
        val second = StationaryDetector()
        assertTrue(fixes.map(first::onFix) == fixes.map(second::onFix))
    }
}
