package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end engine test over a recorded track: eastbound pass at ~65 km/h
 * over a real Taichung camera position (limit 50).
 */
class GpxReplayTest {
    private val camera =
        Camera(
            id = "0241f57b8b0c",
            lat = 24.154503,
            lon = 120.611015,
            type = CameraType.FIXED,
            speedLimitKmh = 50,
            bearingDeg = 90.0, // test the bearing filter: track heads east
            city = "臺中市",
            description = "南屯區五權西路三段225-7號前",
        )

    private fun trackFixes(): List<Fix> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/taichung_sample.gpx"))
        return GpxReplay.toFixes(GpxReplay.parse(stream.buffered()))
    }

    @Test
    fun `eastbound pass fires exactly one over-limit alert`() {
        val events = GpxReplay.replay(AlertEngine(listOf(camera)), trackFixes())
        assertEquals(1, events.size, "one approach must produce exactly one alert")
        val alert = events.single() as AlertEvent.CameraAhead
        assertEquals("0241f57b8b0c", alert.camera.id)
        assertTrue(alert.distanceM in 150.0..250.0, "65 km/h → ~217 m alert distance, got ${alert.distanceM}")
        assertEquals(65, alert.speedKmh, "speed derived from track timestamps")
        assertTrue(alert.overLimit, "65 km/h in a 50 zone exceeds the +10 tolerance")
    }

    @Test
    fun `higher tolerance suppresses the over-limit flag`() {
        val engine = AlertEngine(listOf(camera), EngineConfig(speedToleranceKmh = 20.0))
        val alert = GpxReplay.replay(engine, trackFixes()).single() as AlertEvent.CameraAhead
        assertTrue(!alert.overLimit, "65 km/h is within limit 50 + 20 tolerance")
    }

    @Test
    fun `westbound replay does not alert an eastbound-enforcing camera`() {
        val reversed = trackFixes().asReversed()
        // Rebuild timestamps monotonically so derived speeds stay positive.
        val fixes = GpxReplay.toFixes(reversed.mapIndexed { i, f -> GpxReplay.TrackPoint(f.lat, f.lon, i * 1000L) })
        val events = GpxReplay.replay(AlertEngine(listOf(camera)), fixes)
        assertTrue(events.isEmpty(), "camera enforces eastbound; westbound pass must stay silent")
    }

    @Test
    fun `track parses with plausible kinematics`() {
        val fixes = trackFixes()
        assertEquals(120, fixes.size)
        val cruising = fixes.drop(1)
        assertTrue(cruising.all { it.speedMps in 17.0..19.5 }, "constant ~18 m/s")
        assertTrue(cruising.all { GeoMath.distanceMeters(24.154503, 120.611015, it.lat, it.lon) < 1200.0 })
        assertTrue(
            cruising.all { fix ->
                val bearing = fix.bearingDeg
                bearing != null && AlertEngine.angularDifference(bearing, 90.0) < 5
            },
        )
    }
}
