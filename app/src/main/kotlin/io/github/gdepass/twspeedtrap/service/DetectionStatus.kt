package io.github.gdepass.twspeedtrap.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth the UI observes; written by [DetectionService]. */
object DetectionStatus {
    data class UiState(
        val running: Boolean = false,
        val speedKmh: Int? = null,
        val accuracyM: Int? = null,
        val nextCameraDistanceM: Int? = null,
        val nextCameraLimitKmh: Int? = null,
        val cameraCount: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    internal fun update(transform: (UiState) -> UiState) = _state.update(transform)

    internal fun reset() {
        _state.value = UiState()
    }
}
