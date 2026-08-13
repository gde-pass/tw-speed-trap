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
        accuracy: Double = 5.0,
    ) = Fix(
        lat = lat,
        lon = 120.65000,
        speedMps = speedKmh / 3.6,
        bearingDeg = bearing,
        accuracyM = accuracy,
        timestampMs = 0L,
    )

    private val degPerMeterLat = 1.0 / 110_540.0

    @Test
    fun `fires once when entering alert distance and not again inside`() {
        val engine = AlertEngine(listOf(camera))
        // Heading south towards the camera from 400 m north of it.
        val events400 = engine.onFix(fix(camera.lat + 400 * degPerMeterLat))
        assertTrue(events400.isEmpty(), "400 m is outside the 300 m alert radius")
        val events150 = engine.onFix(fix(camera.lat + 150 * degPerMeterLat))
        assertEquals(1, events150.size)
        val events100 = engine.onFix(fix(camera.lat + 100 * degPerMeterLat))
        assertTrue(events100.isEmpty(), "must not re-fire while still approaching")
    }

    @Test
    fun `re-arms only after leaving hysteresis radius`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Passing the camera (the all-clear fires) and stopping just beyond
        // alert distance: still disarmed.
        val passed = engine.onFix(fix(camera.lat - 220 * degPerMeterLat))
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(camera)), passed)
        assertTrue(engine.onFix(fix(camera.lat - 150 * degPerMeterLat)).isEmpty())
        // Beyond 1.5 × alert distance (450 m): re-arms, and a fresh approach fires again.
        assertTrue(engine.onFix(fix(camera.lat - 500 * degPerMeterLat)).isEmpty())
        assertEquals(1, engine.onFix(fix(camera.lat - 150 * degPerMeterLat)).size)
    }

    @Test
    fun `below 100 kmh the standard alert distance applies`() {
        val engine = AlertEngine(listOf(camera))
        // 90 km/h at 350 m: outside the 300 m standard radius; the 500 m
        // high-speed radius only applies from 100 km/h.
        assertTrue(engine.onFix(fix(camera.lat + 350 * degPerMeterLat, speedKmh = 90.0)).isEmpty())
        assertEquals(1, engine.onFix(fix(camera.lat + 280 * degPerMeterLat, speedKmh = 90.0)).size)
    }

    @Test
    fun `at highway speed the high-speed alert distance applies`() {
        val engine = AlertEngine(listOf(camera))
        // 110 km/h at 550 m: outside the 500 m high-speed radius.
        assertTrue(engine.onFix(fix(camera.lat + 550 * degPerMeterLat, speedKmh = 110.0)).isEmpty())
        assertEquals(1, engine.onFix(fix(camera.lat + 450 * degPerMeterLat, speedKmh = 110.0)).size)
    }

    @Test
    fun `alert distances are adjustable per speed band`() {
        val config = EngineConfig(alertDistanceM = 150.0, highSpeedAlertDistanceM = 600.0)
        val slow = AlertEngine(listOf(camera), config)
        assertTrue(slow.onFix(fix(camera.lat + 200 * degPerMeterLat)).isEmpty(), "200 m at 60 km/h: outside 150 m")
        assertEquals(1, slow.onFix(fix(camera.lat + 140 * degPerMeterLat)).size)
        val fast = AlertEngine(listOf(camera), config)
        assertEquals(1, fast.onFix(fix(camera.lat + 550 * degPerMeterLat, speedKmh = 110.0)).size)
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
        // Turn east and ride 500 m away: the bearing filter no longer matches,
        // but the camera must still re-arm (it is beyond 1.5 × alert distance).
        val eastAway =
            Fix(
                lat = camera.lat,
                lon = camera.lon + 500 * degPerMeterLon,
                speedMps = 60 / 3.6,
                bearingDeg = 90.0,
                accuracyM = 5.0,
                timestampMs = 0,
            )
        // Turning away emits the all-clear but must not re-fire the camera.
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(camera)), engine.onFix(eastAway))
        // Looping the block and re-approaching southbound must fire again.
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size, "second approach must alert")
    }

    @Test
    fun `exactly 100 kmh uses the high-speed distance`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 450 * degPerMeterLat, speedKmh = 100.0)).size)
    }

    @Test
    fun `braking through 100 kmh must not re-alert the same approach`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 480 * degPerMeterLat, speedKmh = 105.0)).size)
        // Braking (the designed reaction) drops the band, but re-arm distance
        // must come from the ring that fired, not from the new speed.
        assertTrue(engine.onFix(fix(camera.lat + 460 * degPerMeterLat, speedKmh = 95.0)).isEmpty())
        assertTrue(engine.onFix(fix(camera.lat + 300 * degPerMeterLat, speedKmh = 80.0)).isEmpty())
    }

    @Test
    fun `a noisy fix cannot fake the all clear`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Multipath jump: reported position far past the margin, but the fix
        // says not to trust it. The camera must stay pending.
        val noisy = engine.onFix(fix(camera.lat + 300 * degPerMeterLat, bearing = null, accuracy = 300.0))
        assertTrue(noisy.isEmpty(), "bad accuracy must not fake a pass")
        assertTrue(engine.activeAlert != null, "alert stays active through the noise")
    }

    @Test
    fun `an implausibly distant pending camera is forgotten even on bad fixes`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // 2 km away with garbage accuracy and no speed: no ring is that big,
        // so the pending pass must clear instead of sticking forever.
        val far =
            engine.onFix(
                fix(camera.lat + 2000 * degPerMeterLat, speedKmh = 0.0, bearing = null, accuracy = 300.0),
            )
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(camera)), far)
        assertTrue(engine.activeAlert == null)
    }

    @Test
    fun `a noisy close fix must not ratchet the closest approach`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Garbage fix apparently 60 m from the camera: must not become the
        // recorded closest approach.
        assertTrue(engine.onFix(fix(camera.lat + 60 * degPerMeterLat, accuracy = 300.0)).isEmpty())
        // Honest fix at 200 m: within margin of the true minimum (150 m), so
        // still pending. A ratcheted 60 m minimum would clear here falsely.
        assertTrue(engine.onFix(fix(camera.lat + 200 * degPerMeterLat, bearing = null)).isEmpty())
        assertTrue(engine.activeAlert != null)
    }

    @Test
    fun `all clear fires exactly once when the fired camera falls behind`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        val passed = engine.onFix(fix(camera.lat - 100 * degPerMeterLat))
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(camera)), passed)
        assertTrue(engine.onFix(fix(camera.lat - 200 * degPerMeterLat)).isEmpty(), "all clear must not repeat")
    }

    @Test
    fun `no all clear while braking to a stop before the camera`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Slowing below the bearing threshold while still approaching: GPS
        // bearing is unreliable there, and the rider is NOT past the camera.
        val braking = engine.onFix(fix(camera.lat + 50 * degPerMeterLat, speedKmh = 10.0, bearing = null))
        assertTrue(braking.isEmpty(), "braking at the camera must not be declared clear")
        assertTrue(engine.activeAlert != null, "alert stays active while stopped in front of the camera")
    }

    @Test
    fun `all clear falls back to the distance margin when bearing is unknown`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Bearing lost but clearly moving away: closest approach was 150 m,
        // now 250 m — beyond the 75 m pass margin.
        val events = engine.onFix(fix(camera.lat + 250 * degPerMeterLat, bearing = null))
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(camera)), events)
    }

    @Test
    fun `second alert takes over without an intermediate all clear`() {
        val follower = camera.copy(id = "cam2", lat = camera.lat - 250 * degPerMeterLat)
        val engine = AlertEngine(listOf(camera, follower))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // 10 m short of the first camera the follower enters its alert ring.
        val handover = engine.onFix(fix(camera.lat + 10 * degPerMeterLat))
        assertEquals(1, handover.size)
        assertEquals("cam2", (handover.single() as AlertEvent.CameraAhead).camera.id)
        // Passing the follower clears the ride in a single all-clear.
        val passed = engine.onFix(fix(follower.lat - 100 * degPerMeterLat))
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(follower)), passed)
    }

    @Test
    fun `active alert exposes a live countdown until the pass`() {
        val engine = AlertEngine(listOf(camera))
        engine.onFix(fix(camera.lat + 150 * degPerMeterLat))
        engine.onFix(fix(camera.lat + 80 * degPerMeterLat))
        val active = engine.activeAlert
        assertTrue(active != null && active.second in 70.0..90.0, "expected ~80 m to the fired camera, got $active")
        engine.onFix(fix(camera.lat - 100 * degPerMeterLat))
        assertTrue(engine.activeAlert == null, "a passed camera is no longer an active alert")
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
