package io.github.gdepass.twspeedtrap.service

/**
 * Counts the live activity instances of the process so the bubble can tell
 * "the user closed the app" from "the user is looking at Google Maps for a
 * minute". Created-to-destroyed is the span the app's task exists: leaving it
 * with Home keeps every activity alive, while backing out of it or swiping it
 * off the recents screen destroys them.
 *
 * A configuration change (rotation, dark mode, an app-language switch)
 * destroys and immediately recreates the activity; that gap is not a
 * departure, or the bubble would blink every time the phone turns.
 */
internal class ActivityPresence {
    private var live = 0

    /** @return whether the user is present — after a create, always. */
    fun onCreated(): Boolean {
        live++
        return true
    }

    /** @return whether the user is still present after this destroy. */
    fun onDestroyed(changingConfigurations: Boolean): Boolean {
        live = (live - 1).coerceAtLeast(0)
        return live > 0 || changingConfigurations
    }
}
