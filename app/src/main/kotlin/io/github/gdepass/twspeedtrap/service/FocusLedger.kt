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

    /** True between the focus request and its abandon. */
    val isHolding: Boolean get() = synchronized(lock) { holding }

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

    /** Backstop / shutdown path: drops all bookkeeping and abandons focus if
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

    companion object {
        /** Before the engine has reported a start, an idle engine is trusted
         * only this long after the enqueue: speak() returns before the engine
         * has picked the utterance up, and a cold engine loads its voice first. */
        const val START_GRACE_MS = 3_000L

        /** Absolute ceiling on one announcement's focus hold, whatever the
         * engine reports (another app may keep the shared engine busy). */
        const val HARD_CAP_MS = 30_000L

        /**
         * Backstop decision, polled while focus is held, for engines whose
         * completion callbacks arrive late or never: the engine's own
         * speaking state is the source of truth once it has stopped. Pure so
         * the policy is unit-testable; [Announcer] feeds it the clock and
         * `TextToSpeech.isSpeaking()`.
         *
         * @param engineSpeaking what the engine reports right now.
         * @param startSeen whether any onStart callback arrived since the last enqueue.
         * @param sinceEnqueueMs time since the most recent successful enqueue.
         */
        fun shouldForceRelease(
            engineSpeaking: Boolean,
            startSeen: Boolean,
            sinceEnqueueMs: Long,
        ): Boolean =
            when {
                sinceEnqueueMs >= HARD_CAP_MS -> true
                engineSpeaking -> false
                else -> startSeen || sinceEnqueueMs >= START_GRACE_MS
            }
    }
}
