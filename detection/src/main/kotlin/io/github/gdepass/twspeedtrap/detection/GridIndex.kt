package io.github.gdepass.twspeedtrap.detection

import kotlin.math.floor

/**
 * Coarse spatial index: cameras bucketed into 0.01° cells (~1.1 km). A query
 * returns the 9 cells around the position, which always covers any alert
 * radius below ~1 km. A few thousand points make anything fancier pointless.
 */
class GridIndex(
    cameras: List<Camera>,
) {
    private val cells: Map<Long, List<Camera>> = cameras.groupBy { key(cellOf(it.lat), cellOf(it.lon)) }

    fun near(
        lat: Double,
        lon: Double,
    ): List<Camera> {
        val row = cellOf(lat)
        val col = cellOf(lon)
        val result = ArrayList<Camera>()
        for (dr in -1..1) {
            for (dc in -1..1) {
                cells[key(row + dr, col + dc)]?.let(result::addAll)
            }
        }
        return result
    }

    private fun cellOf(deg: Double): Int = floor(deg / CELL_DEG).toInt()

    private fun key(
        row: Int,
        col: Int,
    ): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)

    companion object {
        const val CELL_DEG = 0.01
    }
}
