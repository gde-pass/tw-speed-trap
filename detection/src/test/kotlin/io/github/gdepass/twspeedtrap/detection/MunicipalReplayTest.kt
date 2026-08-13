package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replay validation for the municipal camera types added in v1.1: red-light
 * and tech-enforcement points must alert with the right type, respect the
 * bearing filter, and honour the per-type enable toggles. Camera rows are
 * copied verbatim from the shipped database (ids included), so a parser
 * change that reclassifies or moves one of them breaks this test.
 */
class MunicipalReplayTest {
    // gov.tw:130111 — Taipei 闖紅燈 camera, enforces southbound traffic.
    private val taipeiRedLight =
        Camera(
            id = "3b048043d787",
            lat = 25.06042103,
            lon = 121.5182331,
            type = CameraType.RED_LIGHT,
            speedLimitKmh = 50,
            bearingDeg = 180.0,
            city = "臺北市",
            description = "承德路2段 錦西街口",
        )

    // gov.tw:176549 — Kaohsiung 闖紅燈 camera from the 115年 series.
    private val kaohsiungRedLight =
        Camera(
            id = "5471f81fb43f",
            lat = 22.644858,
            lon = 120.314341,
            type = CameraType.RED_LIGHT,
            speedLimitKmh = 60,
            bearingDeg = 180.0,
            city = "高雄市",
            description = "三民 民族一路與十全一路口",
        )

    // gov.tw:170673 — Taichung 路口多功能執法 device, both directions.
    private val taichungTech =
        Camera(
            id = "a0fa3de4320e",
            lat = 24.157244,
            lon = 120.659803,
            type = CameraType.TECH,
            speedLimitKmh = null,
            bearingDeg = null,
            city = "臺中市",
            description = "西區臺灣大道與忠明南路口西區臺灣大道與忠明路口",
        )

    private val cameras = listOf(taipeiRedLight, kaohsiungRedLight, taichungTech)

    private fun fixes(resource: String): List<Fix> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/$resource"))
        return GpxReplay.toFixes(GpxReplay.parse(stream.buffered()))
    }

    @Test
    fun `southbound Taipei pass fires one red-light alert within limit`() {
        val events = GpxReplay.replay(AlertEngine(cameras), fixes("taipei_chengde_southbound.gpx"))
        val alert = events.filterIsInstance<AlertEvent.CameraAhead>().single()
        assertEquals("3b048043d787", alert.camera.id)
        assertEquals(CameraType.RED_LIGHT, alert.camera.type)
        assertTrue(alert.distanceM in 250.0..300.0, "expected the 300 m alert ring, got ${alert.distanceM}")
        assertTrue(!alert.overLimit, "50 km/h at limit 50 must not warn")
        assertEquals(1, events.filterIsInstance<AlertEvent.AllClear>().size, "one pass, one all-clear")
    }

    @Test
    fun `northbound replay stays silent for a southbound-enforcing red-light camera`() {
        val reversed = fixes("taipei_chengde_southbound.gpx").asReversed()
        val northbound =
            GpxReplay.toFixes(reversed.mapIndexed { i, f -> GpxReplay.TrackPoint(f.lat, f.lon, i * 1000L) })
        // The reversed track starts stationary just past the camera; a real
        // ride starting there would legitimately alert (speed-0 fail-safe
        // skips the bearing filter), so replay from the first moving fix.
        val events = GpxReplay.replay(AlertEngine(cameras), northbound.drop(1))
        assertTrue(events.isEmpty(), "camera enforces southbound; northbound pass must stay silent")
    }

    @Test
    fun `disabling red-light alerts suppresses the event but tech stays active`() {
        val config = EngineConfig(enabledTypes = CameraType.entries.toSet() - CameraType.RED_LIGHT)
        val redLightPass = GpxReplay.replay(AlertEngine(cameras, config), fixes("taipei_chengde_southbound.gpx"))
        assertTrue(redLightPass.isEmpty(), "RED_LIGHT disabled must silence the Taipei camera")
        val techPass = GpxReplay.replay(AlertEngine(cameras, config), fixes("taichung_taiwan_blvd_eastbound.gpx"))
        assertEquals(
            1,
            techPass.filterIsInstance<AlertEvent.CameraAhead>().size,
            "TECH stays enabled and must still alert",
        )
    }

    @Test
    fun `Kaohsiung 115-series red-light camera alerts southbound at its limit`() {
        val events = GpxReplay.replay(AlertEngine(cameras), fixes("kaohsiung_minzu_southbound.gpx"))
        val alert = events.filterIsInstance<AlertEvent.CameraAhead>().single()
        assertEquals("5471f81fb43f", alert.camera.id)
        assertEquals(CameraType.RED_LIGHT, alert.camera.type)
        assertEquals(60, alert.camera.speedLimitKmh)
        assertTrue(!alert.overLimit, "60 km/h at limit 60 must not warn")
    }

    @Test
    fun `bearingless Taichung tech point alerts regardless of direction`() {
        val eastbound = fixes("taichung_taiwan_blvd_eastbound.gpx")
        val events = GpxReplay.replay(AlertEngine(cameras), eastbound)
        val alert = events.filterIsInstance<AlertEvent.CameraAhead>().single()
        assertEquals(CameraType.TECH, alert.camera.type)

        val westbound =
            GpxReplay.toFixes(
                eastbound.asReversed().mapIndexed { i, f -> GpxReplay.TrackPoint(f.lat, f.lon, i * 1000L) },
            )
        val reverse = GpxReplay.replay(AlertEngine(cameras), westbound)
        assertEquals(
            1,
            reverse.filterIsInstance<AlertEvent.CameraAhead>().size,
            "null bearing means both directions alert",
        )
    }
}
