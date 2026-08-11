package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeoMathTest {
    // Taipei 101 and Taipei Main Station, ~5.5 km apart.
    private val taipei101Lat = 25.033976
    private val taipei101Lon = 121.564472
    private val mainStationLat = 25.047924
    private val mainStationLon = 121.517081

    @Test
    fun `distance between known Taipei landmarks is about 5 km`() {
        val d = GeoMath.distanceMeters(taipei101Lat, taipei101Lon, mainStationLat, mainStationLon)
        assertEquals(5000.0, d, 500.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, GeoMath.distanceMeters(24.14, 120.68, 24.14, 120.68), 1e-6)
    }

    @Test
    fun `bearing due north is zero`() {
        val b = GeoMath.bearingDegrees(24.0, 120.68, 24.1, 120.68)
        assertEquals(0.0, b, 0.5)
    }

    @Test
    fun `bearing due east is 90`() {
        val b = GeoMath.bearingDegrees(24.14, 120.6, 24.14, 120.7)
        assertEquals(90.0, b, 0.5)
    }
}
