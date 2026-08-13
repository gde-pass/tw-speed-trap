package io.github.gdepass.twspeedtrap.service

import io.github.gdepass.twspeedtrap.detection.CameraType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bubble must never look protected when the app is blind: the mapping
 * from detection status to bubble state is where that promise lives. */
class BubbleStateMappingTest {
    private val runningWithFix =
        DetectionStatus.UiState(running = true, speedKmh = 50, accuracyM = 5)

    @Test
    fun `not running maps to idle`() {
        assertEquals(BubbleState.Idle, BubbleOverlayController.stateFor(DetectionStatus.UiState()))
    }

    @Test
    fun `running with a fix and nothing active is clear`() {
        assertEquals(BubbleState.Clear, BubbleOverlayController.stateFor(runningWithFix))
    }

    @Test
    fun `location off while running is never green`() {
        val state = runningWithFix.copy(locationOff = true)
        assertEquals(BubbleState.NoGps, BubbleOverlayController.stateFor(state))
    }

    @Test
    fun `waiting for the first fix is never green`() {
        val state = DetectionStatus.UiState(running = true, accuracyM = null)
        assertEquals(BubbleState.NoGps, BubbleOverlayController.stateFor(state))
    }

    @Test
    fun `an active alert renders the camera card`() {
        val state =
            runningWithFix.copy(
                activeAlert = DetectionStatus.ActiveAlert(CameraType.FIXED, 50, 249),
            )
        assertEquals(
            BubbleState.Alert(CameraType.FIXED, 50, 249),
            BubbleOverlayController.stateFor(state),
        )
    }

    @Test
    fun `a point alert wins over a section`() {
        val state =
            runningWithFix.copy(
                activeAlert = DetectionStatus.ActiveAlert(CameraType.RED_LIGHT, null, 120),
                activeSection = DetectionStatus.ActiveSection(70, 68),
            )
        assertEquals(
            BubbleState.Alert(CameraType.RED_LIGHT, null, 120),
            BubbleOverlayController.stateFor(state),
        )
    }

    @Test
    fun `a section without a point alert renders the section card`() {
        val state = runningWithFix.copy(activeSection = DetectionStatus.ActiveSection(70, 68))
        assertEquals(BubbleState.Section(70, 68), BubbleOverlayController.stateFor(state))
    }
}
