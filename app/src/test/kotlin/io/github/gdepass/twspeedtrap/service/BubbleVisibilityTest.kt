package io.github.gdepass.twspeedtrap.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bubble floats over every other app, so "how does it go away again" is
 * as much a promise as what it shows: closing the app must take it down, and
 * a rotation must not. */
class BubbleVisibilityTest {
    @Test
    fun `an open app is present`() {
        val presence = ActivityPresence()
        assertTrue(presence.onCreated())
    }

    @Test
    fun `closing the app ends presence`() {
        val presence = ActivityPresence()
        presence.onCreated()
        assertFalse(presence.onDestroyed(changingConfigurations = false))
    }

    @Test
    fun `a configuration change is not a departure`() {
        val presence = ActivityPresence()
        presence.onCreated()
        assertTrue(presence.onDestroyed(changingConfigurations = true))
        // The recreated instance must not inflate the count: closing the app
        // after a rotation still ends presence.
        presence.onCreated()
        assertFalse(presence.onDestroyed(changingConfigurations = false))
    }

    @Test
    fun `a second activity keeps the app present`() {
        val presence = ActivityPresence()
        presence.onCreated()
        presence.onCreated()
        assertTrue(presence.onDestroyed(changingConfigurations = false))
        assertFalse(presence.onDestroyed(changingConfigurations = false))
    }

    @Test
    fun `an unbalanced destroy never leaves presence stuck`() {
        val presence = ActivityPresence()
        presence.onDestroyed(changingConfigurations = false)
        presence.onCreated()
        assertFalse(presence.onDestroyed(changingConfigurations = false))
    }

    @Test
    fun `the bubble is up while the app is open`() {
        assertTrue(show(present = true, running = false))
    }

    @Test
    fun `the bubble is up while detection runs, app closed or not`() {
        assertTrue(show(present = false, running = true))
    }

    @Test
    fun `an idle bubble goes away with the app, and never comes back alone`() {
        assertFalse(show(present = false, running = false))
    }

    @Test
    fun `the setting and the permission each veto the bubble`() {
        assertFalse(show(present = true, running = true, enabled = false))
        assertFalse(show(present = true, running = true, canDrawOverlays = false))
    }

    private fun show(
        present: Boolean,
        running: Boolean,
        enabled: Boolean = true,
        canDrawOverlays: Boolean = true,
    ) = BubbleOverlayController.shouldShow(enabled, present, running, canDrawOverlays)
}
