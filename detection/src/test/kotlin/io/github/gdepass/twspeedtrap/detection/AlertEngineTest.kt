package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertEngineTest {
    // A camera on a north-south road in Taichung, enforcing southbound traffic.
    private val camera =
        Camera(
            id = "cam1",
            lat = 24.16000,
            lon = 120.65000,
            type = CameraType.FIXED,
            speedLimitKmh = 60,
            bearingDeg = 180.0,
            city = "臺中市",
            description = "測試",
        )

    private fun fix(
        lat: Double,
        speedKmh: Double = 60.0,
        bearing: Double? = 180.0,
    ) = Fix(
        lat = lat,
        lon = 120.65000,
        speedMps = speedKmh / 3.6,
        bearingDeg = bearing,
        accuracyM = 5.0,
        timestampMs = 0L,
    )

    private val degPerMeterLat = 1.0 / 110_540.0

    @Test
    fun `fires once when entering alert distance and not again inside`() {
        val engine = AlertEngine(listOf(camera))
        // Heading south towards the camera from 400 m north of it.
        val events400 = engine.onFix(fix(camera.lat + 400 * degPerMeterLat))
        assertTrue(events400.isEmpty(), "400 m at 60 km/h is outside the 200 m alert radius")
        val events150 = engine.onFix(fix(camera.lat + 150 * degPerMeterLat))
        assertEquals(1, events150.size)
        val events100 = engine.onFix(fix(camera.lat + 100 * degPerMeterLat))
        assertTrue(events100.isEmpty(), "must not re-fire while still approaching")
    }

    @Test
    fun `re-arms only after leaving hysteresis radius`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Passing the camera and stopping just beyond alert distance: still disarmed.
        assertTrue(engine.onFix(fix(camera.lat - 220 * degPerMeterLat)).isEmpty())
        assertTrue(engine.onFix(fix(camera.lat - 150 * degPerMeterLat)).isEmpty())
        // Beyond 1.5 × alert distance: re-arms, and a fresh approach fires again.
        assertTrue(engine.onFix(fix(camera.lat - 400 * degPerMeterLat)).isEmpty())
        assertEquals(1, engine.onFix(fix(camera.lat - 150 * degPerMeterLat)).size)
    }

    @Test
    fun `alert distance scales with speed`() {
        val engine = AlertEngine(listOf(camera))
        // 110 km/h → 30.6 m/s × 12 ≈ 367 m alert distance.
        val events = engine.onFix(fix(camera.lat + 350 * degPerMeterLat, speedKmh = 110.0))
        assertEquals(1, events.size)
    }

    @Test
    fun `camera facing the other way is ignored at speed`() {
        val northbound =
            Fix(
                lat = camera.lat - 150 * degPerMeterLat,
                lon = camera.lon,
                speedMps = 60 / 3.6,
                bearingDeg = 0.0,
                accuracyM = 5.0,
                timestampMs = 0,
            )
        val engine = AlertEngine(listOf(camera))
        assertTrue(engine.onFix(northbound).isEmpty(), "southbound-enforcing camera must not alert northbound rider")
    }

    @Test
    fun `bearing filter disabled at walking speed`() {
        val engine = AlertEngine(listOf(camera))
        val crawling = fix(camera.lat + 100 * degPerMeterLat, speedKmh = 10.0, bearing = 0.0)
        assertEquals(1, engine.onFix(crawling).size, "below 15 km/h GPS bearing is unreliable; alert anyway")
    }

    @Test
    fun `null-bearing camera alerts both directions`() {
        val engine = AlertEngine(listOf(camera.copy(bearingDeg = null)))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat, bearing = 0.0)).size)
    }

    @Test
    fun `disabled types are ignored`() {
        val engine =
            AlertEngine(
                listOf(camera),
                EngineConfig(enabledTypes = setOf(CameraType.RED_LIGHT)),
            )
        assertTrue(engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).isEmpty())
    }

    @Test
    fun `re-arms even when the bearing no longer matches`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Turn east and ride 400 m away: the bearing filter no longer matches,
        // but the camera must still re-arm (it is beyond 1.5 × alert distance).
        val eastAway =
            Fix(
                lat = camera.lat,
                lon = camera.lon + 400 * degPerMeterLon,
                speedMps = 60 / 3.6,
                bearingDeg = 90.0,
                accuracyM = 5.0,
                timestampMs = 0,
            )
        assertTrue(engine.onFix(eastAway).isEmpty())
        // Looping the block and re-approaching southbound must fire again.
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size, "second approach must alert")
    }

    @Test
    fun `camera behind the rider is not the nearest camera`() {
        val engine = AlertEngine(listOf(camera))
        engine.onFix(fix(camera.lat + 150 * degPerMeterLat))
        assertTrue(engine.nearestCamera != null, "approaching a fired camera keeps the countdown")
        engine.onFix(fix(camera.lat - 100 * degPerMeterLat))
        assertTrue(engine.nearestCamera == null, "a passed camera must not count up behind the rider")
    }

    @Test
    fun `fired camera still ahead keeps the countdown`() {
        val engine = AlertEngine(listOf(camera))
        engine.onFix(fix(camera.lat + 150 * degPerMeterLat))
        engine.onFix(fix(camera.lat + 80 * degPerMeterLat))
        val nearest = engine.nearestCamera
        assertTrue(nearest != null && nearest.second in 70.0..90.0, "expected ~80 m, got $nearest")
    }

    private val degPerMeterLon = 1.0 / 101_560.0
}
