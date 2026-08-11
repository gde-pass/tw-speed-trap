package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AverageSpeedTrackerTest {
    // Straight northbound section, exactly 2000 m long (limit 50, tolerance +10).
    private val entryLat = 24.10000
    private val lengthM = 2000.0
    private val degPerMeter = 1.0 / 110_540.0
    private val exitLat = entryLat + lengthM * degPerMeter
    private val lon = 120.65000

    private val section = Section("test", 50, lengthM)
    private val endpoints =
        listOf(
            Camera(
                "sec-test-entry",
                entryLat,
                lon,
                CameraType.SECTION,
                50,
                0.0,
                "臺中市",
                "測試入口",
                sectionId = "test",
                sectionRole = "start",
            ),
            Camera(
                "sec-test-exit",
                exitLat,
                lon,
                CameraType.SECTION,
                50,
                0.0,
                "臺中市",
                "測試出口",
                sectionId = "test",
                sectionRole = "end",
            ),
        )

    /** Northbound fixes at constant speed, from 200 m before entry to 200 m past exit. */
    private fun drive(
        speedKmh: Double,
        gapInTunnel: Boolean = false,
    ): List<Fix> {
        val speedMps = speedKmh / 3.6
        val fixes = mutableListOf<Fix>()
        var position = -200.0
        var timeMs = 0L
        while (position < lengthM + 200.0) {
            val insideTunnel = gapInTunnel && position > 200.0 && position < lengthM - 200.0
            if (!insideTunnel) {
                fixes.add(
                    Fix(
                        lat = entryLat + position * degPerMeter,
                        lon = lon,
                        speedMps = speedMps,
                        bearingDeg = 0.0,
                        accuracyM = 5.0,
                        timestampMs = timeMs,
                    ),
                )
            }
            position += speedMps
            timeMs += 1000L
        }
        return fixes
    }

    private fun replay(fixes: List<Fix>): List<AlertEvent> {
        val tracker = AverageSpeedTracker(endpoints, mapOf("test" to section))
        return fixes.flatMap(tracker::onFix)
    }

    @Test
    fun `legal traversal announces entry and exit without warnings`() {
        val events = replay(drive(speedKmh = 55.0))
        assertEquals(2, events.size, "expected entry + exit only, got $events")
        assertTrue(events[0] is AlertEvent.SectionEntered)
        val exit = events[1] as AlertEvent.SectionExited
        assertTrue(exit.averageKmh in 53..57, "average ${exit.averageKmh} should be ~55")
        assertTrue(!exit.overLimit)
    }

    @Test
    fun `speeding traversal warns once and flags the exit`() {
        val events = replay(drive(speedKmh = 80.0))
        assertEquals(3, events.size, "expected entry + over-pace + exit, got $events")
        assertTrue(events[1] is AlertEvent.SectionOverPace)
        val exit = events[2] as AlertEvent.SectionExited
        assertTrue(exit.overLimit)
        assertTrue(exit.averageKmh in 77..83, "average ${exit.averageKmh} should be ~80")
    }

    @Test
    fun `average survives GPS loss in a tunnel`() {
        val events = replay(drive(speedKmh = 55.0, gapInTunnel = true))
        val exit = events.last() as AlertEvent.SectionExited
        assertTrue(exit.averageKmh in 53..57, "tunnel gap must not corrupt the average, got ${exit.averageKmh}")
    }

    @Test
    fun `southbound rider does not enter a northbound section`() {
        val southbound =
            drive(speedKmh = 55.0).asReversed().mapIndexed { i, fix ->
                fix.copy(bearingDeg = 180.0, timestampMs = i * 1000L)
            }
        assertTrue(replay(southbound).isEmpty())
    }
}
