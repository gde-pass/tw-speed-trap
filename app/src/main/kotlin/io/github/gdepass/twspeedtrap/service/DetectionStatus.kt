package io.github.gdepass.twspeedtrap.service

import io.github.gdepass.twspeedtrap.detection.CameraType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth the UI observes; written by [DetectionService]. */
object DetectionStatus {
    /** A fired camera alert still ahead: what it enforces and how far it is. */
    data class ActiveAlert(
        val type: CameraType,
        val speedLimitKmh: Int?,
        val distanceM: Int,
    )

    /** A section traversal in progress: its limit and the projected exit average. */
    data class ActiveSection(
        val speedLimitKmh: Int,
        val projectedAverageKmh: Int,
    )

    data class UiState(
        val running: Boolean = false,
        val speedKmh: Int? = null,
        val accuracyM: Int? = null,
        val nextCameraDistanceM: Int? = null,
        val nextCameraLimitKmh: Int? = null,
        val cameraCount: Int = 0,
        val activeAlert: ActiveAlert? = null,
        val activeSection: ActiveSection? = null,
        /** The selected TTS language has no installed voice. */
        val voiceMissing: Boolean = false,
        /** Android location services were switched off while detection runs. */
        val locationOff: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    internal fun update(transform: (UiState) -> UiState) = _state.update(transform)

    internal fun reset() {
        _state.value = UiState()
    }
}
