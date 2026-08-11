package io.github.gdepass.twspeedtrap.detection

enum class CameraType {
    FIXED,
    MOBILE,
    RED_LIGHT,
    SECTION,
    TECH,
    OTHER,
    ;

    companion object {
        fun from(raw: String): CameraType =
            when (raw) {
                "fixed" -> FIXED
                "mobile" -> MOBILE
                "red_light" -> RED_LIGHT
                "section" -> SECTION
                "tech" -> TECH
                else -> OTHER
            }
    }
}

data class Camera(
    val id: String,
    val lat: Double,
    val lon: Double,
    val type: CameraType,
    val speedLimitKmh: Int?,
    /** Direction of travel being enforced, degrees [0, 360). Null = both directions. */
    val bearingDeg: Double?,
    val city: String,
    val description: String,
    val sectionId: String? = null,
    val sectionRole: String? = null,
)

/** One GPS sample. [bearingDeg] is the direction of travel; null when unknown. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Double?,
    val accuracyM: Double,
    val timestampMs: Long,
)

sealed interface AlertEvent {
    data class CameraAhead(
        val camera: Camera,
        val distanceM: Double,
    ) : AlertEvent
}
