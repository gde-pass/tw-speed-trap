package io.github.gdepass.twspeedtrap.detection

import org.w3c.dom.Element
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Replays a recorded GPX track through the engine, deriving speed and bearing
 * from consecutive points — the same signals a live GPS would provide. This
 * is how alert logic is tested without riding the route.
 */
object GpxReplay {
    data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
    )

    fun parse(input: InputStream): List<TrackPoint> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(input)
        val points = document.getElementsByTagNameNS("*", "trkpt")
        return buildList {
            for (i in 0 until points.length) {
                val element = points.item(i) as Element
                val times = element.getElementsByTagNameNS("*", "time")
                require(times.length > 0) { "trkpt without <time> at index $i" }
                add(
                    TrackPoint(
                        lat = element.getAttribute("lat").toDouble(),
                        lon = element.getAttribute("lon").toDouble(),
                        timeMs = Instant.parse(times.item(0).textContent.trim()).toEpochMilli(),
                    ),
                )
            }
        }
    }

    fun toFixes(points: List<TrackPoint>): List<Fix> =
        points.mapIndexed { i, point ->
            if (i == 0) {
                Fix(point.lat, point.lon, 0.0, null, ACCURACY_M, point.timeMs)
            } else {
                val previous = points[i - 1]
                val dtSeconds = (point.timeMs - previous.timeMs) / 1000.0
                val distance = GeoMath.distanceMeters(previous.lat, previous.lon, point.lat, point.lon)
                Fix(
                    lat = point.lat,
                    lon = point.lon,
                    speedMps = if (dtSeconds > 0) distance / dtSeconds else 0.0,
                    bearingDeg = GeoMath.bearingDegrees(previous.lat, previous.lon, point.lat, point.lon),
                    accuracyM = ACCURACY_M,
                    timestampMs = point.timeMs,
                )
            }
        }

    fun replay(
        engine: AlertEngine,
        fixes: List<Fix>,
    ): List<AlertEvent> = fixes.flatMap(engine::onFix)

    private const val ACCURACY_M = 5.0
}
