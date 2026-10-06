package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
        timeMs: Long = 0L,
        lon: Double = 120.65000,
    ) = Fix(
        lat = lat,
        lon = lon,
        speedMps = speedKmh / 3.6,
        bearingDeg = bearing,
        accuracyM = accuracy,
        timestampMs = timeMs,
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
        // Beyond 1.5 × alert distance (450 m): re-arms. Looping back for a
        // fresh southbound approach from the north fires again; 150 m south
        // of it heading south is behind the camera and must not.
        assertTrue(engine.onFix(fix(camera.lat - 500 * degPerMeterLat)).isEmpty())
        assertTrue(engine.onFix(fix(camera.lat - 150 * degPerMeterLat)).isEmpty())
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
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
        // Northbound approach from 150 m south: the southbound-only camera
        // would ignore it; without a bearing it must alert.
        assertEquals(1, engine.onFix(fix(camera.lat - 150 * degPerMeterLat, bearing = 0.0)).size)
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

    // ---- firing only while the camera is ahead ------------------------------

    @Test
    fun `re-armed camera behind must not re-fire when crossing 100 kmh`() {
        val engine = AlertEngine(listOf(camera))
        // Expressway pattern: brake under 100 for the camera (300 m ring fires,
        // re-arm at 450 m), pass it, accelerate past 100 (ring widens to 500 m).
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat, speedKmh = 95.0)).size)
        assertEquals(
            listOf<AlertEvent>(AlertEvent.AllClear(camera)),
            engine.onFix(fix(camera.lat - 100 * degPerMeterLat)),
        )
        assertTrue(engine.onFix(fix(camera.lat - 460 * degPerMeterLat, speedKmh = 105.0)).isEmpty())
        val behind = engine.onFix(fix(camera.lat - 485 * degPerMeterLat, speedKmh = 105.0))
        assertTrue(behind.isEmpty(), "a camera 485 m behind must not fire, got $behind")
        assertTrue(engine.nearestCamera == null, "a camera behind is not the next camera")
    }

    @Test
    fun `camera behind in the enforced direction never fires`() {
        val engine = AlertEngine(listOf(camera))
        // Southbound rider 200 m south of a southbound-enforcing camera: the
        // bearing matches and the ring contains it, but it is already passed.
        assertTrue(engine.onFix(fix(camera.lat - 200 * degPerMeterLat)).isEmpty())
        assertTrue(engine.nearestCamera == null)
    }

    @Test
    fun `bearingless camera behind the rider never fires`() {
        val engine = AlertEngine(listOf(camera.copy(bearingDeg = null)))
        assertTrue(engine.onFix(fix(camera.lat - 200 * degPerMeterLat)).isEmpty())
    }

    // ---- bearing memory at low speed ---------------------------------------

    @Test
    fun `stopped at a red light before an opposite-direction camera does not alert`() {
        val engine = AlertEngine(listOf(camera))
        // Northbound at speed 400 m south of the southbound-enforcing camera.
        assertTrue(engine.onFix(fix(camera.lat - 400 * degPerMeterLat, bearing = 0.0, timeMs = 0)).isEmpty())
        // Queued 50 m south of it: GPS bearing gone, speed under the threshold.
        // The old fail-open fired here; the remembered northbound bearing must not.
        val queued =
            engine.onFix(
                fix(camera.lat - 50 * degPerMeterLat, speedKmh = 3.0, bearing = null, timeMs = 20_000),
            )
        assertTrue(queued.isEmpty(), "remembered bearing must keep the bearing filter alive, got $queued")
    }

    @Test
    fun `bearing memory expires so a long stop falls back to fail-open`() {
        val engine = AlertEngine(listOf(camera))
        assertTrue(engine.onFix(fix(camera.lat + 400 * degPerMeterLat, bearing = 0.0, timeMs = 0)).isEmpty())
        val stale = AlertEngine.BEARING_MEMORY_MS + 1_000
        val crawling =
            engine.onFix(
                fix(camera.lat + 100 * degPerMeterLat, speedKmh = 10.0, bearing = null, timeMs = stale),
            )
        assertEquals(1, crawling.size, "with no fresh bearing the direction is unknown: alert (fail-safe)")
    }

    @Test
    fun `remembered matching bearing still alerts while crawling`() {
        val engine = AlertEngine(listOf(camera))
        assertTrue(engine.onFix(fix(camera.lat + 400 * degPerMeterLat, timeMs = 0)).isEmpty())
        val crawling =
            engine.onFix(
                fix(camera.lat + 250 * degPerMeterLat, speedKmh = 5.0, bearing = null, timeMs = 30_000),
            )
        assertEquals(1, crawling.size)
    }

    // ---- accuracy gate --------------------------------------------------------

    @Test
    fun `an untrusted fix inside the ring does not fire`() {
        val engine = AlertEngine(listOf(camera))
        // Network fallback in a canyon: no speed, no bearing, 500 m accuracy.
        val network =
            engine.onFix(
                fix(camera.lat + 150 * degPerMeterLat, speedKmh = 0.0, bearing = null, accuracy = 500.0),
            )
        assertTrue(network.isEmpty(), "a 500 m-accuracy point must not fire, got $network")
        assertTrue(engine.nearestCamera == null)
        // GPS back: the same approach fires normally.
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
    }

    @Test
    fun `the no-accuracy sentinel still resolves the pass`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        val passed = engine.onFix(fix(camera.lat - 100 * degPerMeterLat, accuracy = 99.0))
        assertEquals(listOf<AlertEvent>(AlertEvent.AllClear(camera)), passed)
    }

    // ---- camera-axis pass test ----------------------------------------------

    @Test
    fun `hairpin apex heading away does not fake the all clear`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // Apex of a hairpin: 100 m north and 150 m east of the camera, heading
        // east. By travel bearing the camera is in the rear half-plane; by the
        // camera's own axis the rider is still upstream.
        val apex =
            engine.onFix(
                fix(camera.lat + 100 * degPerMeterLat, bearing = 90.0, lon = camera.lon + 150 * degPerMeterLon),
            )
        assertTrue(apex.isEmpty(), "camera still ahead by road must not clear, got $apex")
        assertTrue(engine.activeAlert != null)
        // Down the second leg past the camera: now clear.
        assertEquals(
            listOf<AlertEvent>(AlertEvent.AllClear(camera)),
            engine.onFix(fix(camera.lat - 100 * degPerMeterLat)),
        )
    }

    @Test
    fun `a fix right at the camera decides nothing about the pass`() {
        val engine = AlertEngine(listOf(camera))
        assertEquals(1, engine.onFix(fix(camera.lat + 150 * degPerMeterLat)).size)
        // 10 m past the camera: the camera→rider bearing is noise at that range.
        assertTrue(engine.onFix(fix(camera.lat - 10 * degPerMeterLat)).isEmpty())
        assertEquals(
            listOf<AlertEvent>(AlertEvent.AllClear(camera)),
            engine.onFix(fix(camera.lat - 40 * degPerMeterLat)),
        )
    }

    // ---- bends and configuration -------------------------------------------

    @Test
    fun `a bend inside the ring does not postpone the alert`() {
        val engine = AlertEngine(listOf(camera))
        // 250 m north of the southbound camera, heading east round a bend:
        // 90° off the enforced direction but plainly upstream on its axis.
        val onBend = engine.onFix(fix(camera.lat + 250 * degPerMeterLat, bearing = 90.0))
        assertEquals(1, onBend.size, "upstream on the camera's axis must alert even mid-bend")
    }

    @Test
    fun `oncoming rider on the camera's axis never alerts`() {
        val engine = AlertEngine(listOf(camera))
        // North of the southbound camera but heading north: upstream on the
        // axis, yet opposite the enforced direction.
        assertTrue(engine.onFix(fix(camera.lat + 150 * degPerMeterLat, bearing = 0.0)).isEmpty())
    }

    @Test
    fun `a re-arm ring wider than the index coverage is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            AlertEngine(listOf(camera), EngineConfig(highSpeedAlertDistanceM = 700.0, rearmFactor = 1.5))
        }
        // The widest the settings allow (600 m × 1.5) still fits.
        AlertEngine(listOf(camera), EngineConfig(alertDistanceM = 600.0, highSpeedAlertDistanceM = 600.0))
    }

    @Test
    fun `a camera on a diagonal road tagged with a cardinal direction still alerts`() {
        // Source data carries only 北往南-style directions: this southbound-tagged
        // camera sits on a road running 235°, and the heading reads 10° noisier.
        val engine = AlertEngine(listOf(camera))
        val upstream = fix(camera.lat + 180 * degPerMeterLat, lon = camera.lon + 180 * degPerMeterLon, bearing = 235.0)
        assertEquals(
            1,
            engine.onFix(upstream).size,
            "a 55° skew between road and cardinal tag must not silence the camera",
        )
        // Cross traffic through the camera's junction (90° off, camera dead ahead) is still rejected.
        val cross = AlertEngine(listOf(camera))
        assertTrue(cross.onFix(fix(camera.lat, lon = camera.lon + 250 * degPerMeterLon, bearing = 270.0)).isEmpty())
    }

    @Test
    fun `a camera left behind in one jump re-arms and fires on the way back`() {
        val both = camera.copy(id = "any", bearingDeg = null)
        val engine = AlertEngine(listOf(both))
        assertEquals(1, engine.onFix(fix(both.lat + 250 * degPerMeterLat, timeMs = 0L)).size)
        // Tunnel: the next fix is 3 km south, far outside the index cells around
        // the camera (only the pending pass is forgotten there).
        val gap = engine.onFix(fix(both.lat - 3_000 * degPerMeterLat, timeMs = 60_000L))
        assertTrue(gap.none { it is AlertEvent.CameraAhead })
        // Back the same way, approaching northbound from 250 m south: must fire again.
        val back = engine.onFix(fix(both.lat - 250 * degPerMeterLat, bearing = 0.0, timeMs = 120_000L))
        assertEquals(
            1,
            back.filterIsInstance<AlertEvent.CameraAhead>().size,
            "disarmed must not outlive a gap in the index",
        )
    }

    private val degPerMeterLon = 1.0 / 101_560.0
}
