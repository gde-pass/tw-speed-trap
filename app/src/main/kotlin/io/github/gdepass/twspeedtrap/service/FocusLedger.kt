package io.github.gdepass.twspeedtrap.service

/**
 * Pairs every audio-focus request with a guaranteed abandon by refcounting
 * in-flight utterance ids. Pure JVM on purpose: [Announcer] injects the
 * AudioManager calls as lambdas so this bookkeeping stays unit-testable.
 *
 * Threading: [announce] runs on the main thread; [complete] arrives on the
 * TTS engine's binder thread. Every decision (request, rollback, abandon)
 * happens inside one lock, so a completion can never abandon focus between
 * a new announcement's focus request and its ids being tracked.
 */
class FocusLedger(
    private val requestFocus: () -> Unit,
    private val abandonFocus: () -> Unit,
) {
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private var holding = false

    /**
     * Requests focus, runs [enqueue] (returning the utterance ids the TTS
     * engine actually accepted), and tracks them. If nothing was accepted
     * and no earlier utterance is still in flight, rolls the focus request
     * back immediately. Returns true when this announcement put at least
     * one id in flight (earlier utterances don't count).
     */
    fun announce(enqueue: () -> List<String>): Boolean =
        synchronized(lock) {
            requestFocus()
            holding = true
            val accepted = enqueue()
            inFlight += accepted
            if (inFlight.isEmpty()) {
                abandonFocus()
                holding = false
            }
            accepted.isNotEmpty()
        }

    /** Marks one utterance finished (done, error, or stopped); abandons focus
     * when it was the last one in flight. Unknown or null ids are ignored. */
    fun complete(id: String?) {
        if (id == null) return
        synchronized(lock) {
            val drained = inFlight.remove(id) && inFlight.isEmpty()
            if (drained && holding) {
                abandonFocus()
                holding = false
            }
        }
    }

    /** Watchdog / shutdown path: drops all bookkeeping and abandons focus if
     * still held. Safe to call when idle. */
    fun forceRelease() {
        synchronized(lock) {
            inFlight.clear()
            if (holding) {
                abandonFocus()
                holding = false
            }
        }
    }
}
