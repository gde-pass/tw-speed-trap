package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            sectionCamera("sec-test-entry", entryLat, 0.0, "test", "start"),
            sectionCamera("sec-test-exit", exitLat, 0.0, "test", "end"),
        )

    private fun sectionCamera(
        id: String,
        lat: Double,
        bearing: Double?,
        sectionId: String,
        role: String,
        atLon: Double = lon,
    ) = Camera(
        id,
        lat,
        atLon,
        CameraType.SECTION,
        50,
        bearing,
        "臺中市",
        "測試 $id",
        sectionId = sectionId,
        sectionRole = role,
    )

    /** Northbound fixes at constant speed; positions in [gap] are skipped (tunnel). */
    private fun drive(
        speedKmh: Double,
        from: Double = -200.0,
        until: Double = lengthM + 200.0,
        gap: ClosedFloatingPointRange<Double>? = null,
    ): List<Fix> {
        val speedMps = speedKmh / 3.6
        val fixes = mutableListOf<Fix>()
        var position = from
        var timeMs = 0L
        while (position < until) {
            if (gap == null || position !in gap) {
                fixes.add(northboundFix(position, speedMps, timeMs))
            }
            position += speedMps
            timeMs += 1000L
        }
        return fixes
    }

    private fun northboundFix(
        position: Double,
        speedMps: Double,
        timeMs: Long,
        bearing: Double? = 0.0,
        accuracy: Double = 5.0,
    ) = Fix(
        lat = entryLat + position * degPerMeter,
        lon = lon,
        speedMps = speedMps,
        bearingDeg = bearing,
        accuracyM = accuracy,
        timestampMs = timeMs,
    )

    private fun tracker() = AverageSpeedTracker(endpoints, mapOf("test" to section))

    private fun replay(fixes: List<Fix>): List<AlertEvent> = fixes.flatMap(tracker()::onFix)

    @Test
    fun `live status projects the exit average while inside the section`() {
        val t = tracker()
        drive(speedKmh = 80.0, until = 1000.0).forEach(t::onFix)
        val live = t.liveStatus
        assertTrue(live != null && live.second in 77..83, "constant 80 km/h must project ~80, got $live")
        assertEquals("test", live?.first?.id)
    }

    @Test
    fun `live status freezes while stopped inside the section`() {
        val t = tracker()
        drive(speedKmh = 60.0, until = 500.0).forEach(t::onFix)
        val before = t.liveStatus
        assertTrue(before != null, "half-way through the section the status must be live")
        t.onFix(northboundFix(500.0, 0.0, 60_000L))
        assertEquals(before, t.liveStatus, "zero speed must freeze the projection, not distort it")
    }

    @Test
    fun `live status clears once the section is exited`() {
        val t = tracker()
        drive(speedKmh = 55.0).forEach(t::onFix)
        assertTrue(t.liveStatus == null, "no live status outside a traversal")
    }

    @Test
    fun `legal traversal announces entry and exit without warnings`() {
        val events = replay(drive(speedKmh = 55.0))
        assertEquals(2, events.size, "expected entry + exit only, got $events")
        assertTrue(events[0] is AlertEvent.SectionEntered)
        val exit = events[1] as AlertEvent.SectionExited
        assertTrue(exit.averageKmh in 53..57, "average ${exit.averageKmh} should be ~55")
        assertTrue(!exit.overLimit)
        assertFalse(exit.estimated)
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
        val events = replay(drive(speedKmh = 55.0, gap = 200.0..(lengthM - 200.0)))
        val exit = events.last() as AlertEvent.SectionExited
        assertTrue(exit.averageKmh in 53..57, "tunnel gap must not corrupt the average, got ${exit.averageKmh}")
        assertFalse(exit.estimated, "exit ball was reached live; not an estimate")
    }

    @Test
    fun `southbound rider does not enter a northbound section`() {
        val southbound =
            drive(speedKmh = 55.0).asReversed().mapIndexed { i, fix ->
                fix.copy(bearingDeg = 180.0, timestampMs = i * 1000L)
            }
        assertTrue(replay(southbound).isEmpty())
    }

    @Test
    fun `exit missed in a full tunnel is announced as estimated`() {
        // GPS dies just after the entry and reacquires ~250 m past the exit.
        val events = replay(drive(speedKmh = 55.0, gap = 100.0..(lengthM + 250.0), until = lengthM + 400.0))
        assertEquals(2, events.size, "expected entry + estimated exit, got $events")
        val exit = events[1] as AlertEvent.SectionExited
        assertTrue(exit.estimated)
        assertTrue(exit.averageKmh in 50..57, "average ${exit.averageKmh} should be ~54")
        assertFalse(exit.overLimit)
    }

    @Test
    fun `huge overshoot clears state silently instead of locking out`() {
        val active = tracker()
        val events =
            drive(speedKmh = 55.0, gap = 100.0..(lengthM + 900.0), until = lengthM + 1100.0)
                .flatMap(active::onFix)
        assertEquals(1, events.size, "contaminated average must not be announced, got $events")
        assertTrue(events[0] is AlertEvent.SectionEntered)
        assertFalse(active.isActive, "traversal must be cleared so the next entry works")
    }

    @Test
    fun `chained sections both announce when a bridge fix exists`() {
        val events = chainedReplay(bridgeFix = true)
        assertEquals(4, events.size, "expected enter/exit × 2, got $events")
        val firstExit = events[1] as AlertEvent.SectionExited
        val secondExit = events[3] as AlertEvent.SectionExited
        assertEquals("a", firstExit.section.id)
        assertFalse(firstExit.estimated)
        assertEquals("b", secondExit.section.id)
        assertTrue(secondExit.estimated)
    }

    @Test
    fun `chained sections without a bridge fix stay silent but unlocked`() {
        val events = chainedReplay(bridgeFix = false)
        assertEquals(1, events.size, "no fix between portals: second section is undetectable, got $events")
        assertTrue(events[0] is AlertEvent.SectionEntered)
    }

    /** Two 2000 m sections in series, portals 78 m apart (觀音隧道 → 谷風 geometry). */
    private fun chainedReplay(bridgeFix: Boolean): List<AlertEvent> {
        val gapM = 78.0
        val bLat = exitLat + gapM * degPerMeter
        val bExitLat = bLat + lengthM * degPerMeter
        val sections = mapOf("a" to Section("a", 80, lengthM), "b" to Section("b", 80, lengthM))
        val chain =
            AverageSpeedTracker(
                listOf(
                    sectionCamera("sec-a-entry", entryLat, 0.0, "a", "start"),
                    sectionCamera("sec-a-exit", exitLat, 0.0, "a", "end"),
                    sectionCamera("sec-b-entry", bLat, 0.0, "b", "start"),
                    sectionCamera("sec-b-exit", bExitLat, 0.0, "b", "end"),
                ),
                sections,
            )
        val speedMps = 20.0
        val positions = mutableListOf<Double>()
        var p = -100.0
        while (p <= 100.0) {
            positions.add(p)
            p += speedMps
        }
        // Tunnel A … optional bridge fix between the portals … tunnel B … reacquire past B.
        if (bridgeFix) positions.add(lengthM + 39.0)
        var reacquire = lengthM + gapM + lengthM + 300.0
        repeat(3) {
            positions.add(reacquire)
            reacquire += speedMps
        }
        return positions
            .map { pos -> northboundFix(pos, speedMps, timeMs = ((pos + 100.0) / speedMps * 1000.0).toLong()) }
            .flatMap(chain::onFix)
    }

    @Test
    fun `backward clock jump abandons instead of announcing an absurd average`() {
        val active = tracker()
        val events = mutableListOf<AlertEvent>()
        val speedMps = 55.0 / 3.6
        var timeMs = 0L
        var position = -100.0
        while (position < 500.0) {
            events += active.onFix(northboundFix(position, speedMps, timeMs))
            position += speedMps
            timeMs += 1000L
        }
        assertTrue(events.any { it is AlertEvent.SectionEntered })
        // The provider clock jumps back a minute mid-section.
        events += active.onFix(northboundFix(position, speedMps, timeMs - 60_000L))
        assertFalse(active.isActive, "negative elapsed must abandon the traversal")
        while (position < lengthM + 100.0) {
            events += active.onFix(northboundFix(position, speedMps, timeMs))
            position += speedMps
            timeMs += 1000L
        }
        assertTrue(
            events.none { it is AlertEvent.SectionExited },
            "no average can be computed across a clock jump, got $events",
        )
    }

    @Test
    fun `implausibly fast traversal is suppressed`() {
        val active = tracker()
        val speedMps = 55.0 / 3.6
        val entered = active.onFix(northboundFix(-50.0, speedMps, 0L))
        assertTrue(entered.single() is AlertEvent.SectionEntered)
        // One second later a (bogus) fix appears inside the exit ball: 2 km in 1 s.
        val exited = active.onFix(northboundFix(lengthM - 30.0, speedMps, 1000L))
        assertTrue(exited.isEmpty(), "7200 km/h is not a ride, got $exited")
        assertFalse(active.isActive)
    }

    @Test
    fun `u-turn abandons the traversal and a fresh entry works`() {
        val active = tracker()
        val events = mutableListOf<AlertEvent>()
        val speedMps = 55.0 / 3.6
        var timeMs = 0L

        fun runSegment(
            from: Double,
            to: Double,
            bearing: Double,
        ) {
            var position = from
            val step = if (to > from) speedMps else -speedMps
            while (if (step > 0) position < to else position > to) {
                events += active.onFix(northboundFix(position, speedMps, timeMs, bearing = bearing))
                position += step
                timeMs += 1000L
            }
        }
        runSegment(-100.0, 300.0, bearing = 0.0)
        runSegment(300.0, -300.0, bearing = 180.0)
        assertFalse(active.isActive, "riding 300 m back past the entry must abandon")
        runSegment(-300.0, 100.0, bearing = 0.0)
        assertEquals(2, events.count { it is AlertEvent.SectionEntered }, "got $events")
        assertEquals(0, events.count { it is AlertEvent.SectionExited })
    }

    @Test
    fun `garbage accuracy cannot fake an exit`() {
        val active = tracker()
        val events = mutableListOf<AlertEvent>()
        val speedMps = 55.0 / 3.6
        var timeMs = 0L
        var position = -100.0
        while (position < lengthM + 100.0) {
            events += active.onFix(northboundFix(position, speedMps, timeMs))
            if (position in 400.0..420.0) {
                // A 300 m-accuracy fix teleported inside the exit ball.
                events += active.onFix(northboundFix(lengthM - 10.0, speedMps, timeMs + 500L, accuracy = 300.0))
            }
            position += speedMps
            timeMs += 1000L
        }
        val exits = events.filterIsInstance<AlertEvent.SectionExited>()
        assertEquals(1, exits.size, "only the real exit may announce, got $events")
        assertFalse(exits[0].estimated)
        assertTrue(exits[0].averageKmh in 53..57)
    }

    // ---- colocated opposite-direction portals (自強/辛亥/壽卡/小馬/金門大橋) ----

    private fun mirroredTracker(): AverageSpeedTracker {
        val sections =
            mapOf("nb" to Section("nb", 50, lengthM), "sb" to Section("sb", 50, lengthM))
        return AverageSpeedTracker(
            listOf(
                sectionCamera("sec-nb-entry", entryLat, 0.0, "nb", "start"),
                sectionCamera("sec-nb-exit", exitLat, 0.0, "nb", "end"),
                sectionCamera("sec-sb-entry", exitLat, 180.0, "sb", "start"),
                sectionCamera("sec-sb-exit", entryLat, 180.0, "sb", "end"),
            ),
            sections,
        )
    }

    @Test
    fun `stopped rider at a shared portal only enters the direction actually ridden`() {
        val active = mirroredTracker()
        val events = mutableListOf<AlertEvent>()
        // Stopped exactly on the northern portal (sb entry ≡ nb exit): no bearing, no entry.
        repeat(5) { events += active.onFix(northboundFix(lengthM, 0.0, it * 1000L, bearing = null)) }
        assertTrue(events.isEmpty(), "no direction known yet: must not enter, got $events")
        // Rides off southbound: the southbound section is entered, backdated to the portal crossing.
        var position = lengthM
        var timeMs = 5000L
        while (position > -100.0) {
            events += active.onFix(northboundFix(position, 5.0, timeMs, bearing = 180.0))
            position -= 5.0
            timeMs += 1000L
        }
        assertEquals(1, events.count { it is AlertEvent.SectionEntered })
        val exit = events.last() as AlertEvent.SectionExited
        assertEquals("sb", exit.section.id)
        assertTrue(exit.averageKmh in 16..20, "5 m/s ≈ 18 km/h, got ${exit.averageKmh}")
    }

    @Test
    fun `stopped rider leaving the wrong way enters nothing`() {
        val active = mirroredTracker()
        val events = mutableListOf<AlertEvent>()
        repeat(5) { events += active.onFix(northboundFix(lengthM, 0.0, it * 1000L, bearing = null)) }
        // Rides off northbound: the southbound entry at this portal must not fire.
        var position = lengthM
        var timeMs = 5000L
        while (position < lengthM + 600.0) {
            events += active.onFix(northboundFix(position, 5.0, timeMs, bearing = 0.0))
            position += 5.0
            timeMs += 1000L
        }
        assertTrue(events.isEmpty(), "northbound departure from the sb entry portal, got $events")
    }

    @Test
    fun `crawl entry resolves via displacement when bearing is untrusted`() {
        val active = tracker()
        val events = mutableListOf<AlertEvent>()
        val crawlMps = 12.0 / 3.6 // below minSpeedForBearingMps: GPS bearing untrusted
        var position = -100.0
        var timeMs = 0L
        while (position < lengthM + 100.0) {
            events += active.onFix(northboundFix(position, crawlMps, timeMs))
            position += crawlMps
            timeMs += 1000L
        }
        assertEquals(1, events.count { it is AlertEvent.SectionEntered }, "got $events")
        val exit = events.last() as AlertEvent.SectionExited
        assertTrue(exit.averageKmh in 11..13, "crawl average ~12, got ${exit.averageKmh}")
    }

    @Test
    fun `bearing remembered from the approach enters the section after a stop`() {
        val active = tracker()
        // Approach at 55 km/h: bearing memory is populated.
        var entered = false
        var timeMs = 0L
        var position = -200.0
        while (position < -100.0) {
            active.onFix(northboundFix(position, 55.0 / 3.6, timeMs))
            position += 55.0 / 3.6
            timeMs += 1000L
        }
        // Creep into the entry ball at 5 km/h with no live bearing.
        val creepMps = 5.0 / 3.6
        while (position < -20.0) {
            val events = active.onFix(northboundFix(position, creepMps, timeMs, bearing = null))
            if (events.any { it is AlertEvent.SectionEntered }) {
                entered = true
                break
            }
            position += creepMps
            timeMs += 1000L
        }
        assertTrue(entered, "remembered approach bearing must let the entry fire while creeping")
    }

    @Test
    fun `identical replays produce identical events`() {
        val fixes = drive(speedKmh = 55.0, gap = 100.0..(lengthM + 250.0), until = lengthM + 400.0)
        assertEquals(replay(fixes), replay(fixes))
    }
}
