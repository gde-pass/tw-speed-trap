package io.github.gdepass.twspeedtrap.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GridIndexTest {
    private fun cam(
        id: String,
        lat: Double,
        lon: Double,
    ) = Camera(id, lat, lon, CameraType.FIXED, 50, null, "臺中市", "test")

    @Test
    fun `finds cameras in same and adjacent cells`() {
        val index =
            GridIndex(
                listOf(
                    cam("same", 24.155, 120.655),
                    cam("adjacent", 24.165, 120.665),
                    cam("far", 24.255, 120.755),
                ),
            )
        val near = index.near(24.155, 120.655).map { it.id }
        assertTrue("same" in near)
        assertTrue("adjacent" in near)
        assertTrue("far" !in near)
    }

    @Test
    fun `works across the cell boundary`() {
        val index = GridIndex(listOf(cam("edge", 24.1599, 120.65)))
        assertEquals(1, index.near(24.1601, 120.65).size)
    }

    @Test
    fun `empty area returns empty list`() {
        val index = GridIndex(listOf(cam("a", 24.15, 120.65)))
        assertTrue(index.near(25.05, 121.55).isEmpty())
    }
}
