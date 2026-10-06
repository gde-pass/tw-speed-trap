package io.github.gdepass.twspeedtrap.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.gdepass.twspeedtrap.R
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Speaks alerts through the navigation-guidance audio channel.
 *
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + transient-may-duck focus is what
 * makes music duck instead of stopping, and keeps alerts audible over
 * Bluetooth while Android Auto owns the media stream.
 *
 * Focus is held exactly as long as the engine is busy with our utterances:
 * the per-utterance callbacks release it the moment the last one completes,
 * and a poll of the engine's speaking state backs them up, so a lost or late
 * callback un-ducks the music within [BACKSTOP_POLL_MS] instead of leaving it
 * quiet until a long timeout.
 */
class Announcer(
    private val context: Context,
    private val locale: Locale,
    private val onVoiceStatus: (voiceMissing: Boolean) -> Unit = {},
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

    private var ready = false

    /** The engine refused to bind: nothing will ever be spoken by this instance. */
    private var bindFailed = false

    @Volatile
    private var released = false

    /** Alerts queued while the engine binds (or rebinds), oldest first. */
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private var utteranceSeq = 0
    private var rebuilding = false

    private val handler = Handler(Looper.getMainLooper())

    private val ledger =
        FocusLedger(
            requestFocus = { audioManager.requestAudioFocus(focusRequest) },
            abandonFocus = { audioManager.abandonAudioFocusRequest(focusRequest) },
        )

    /** Set by onStart (binder thread); cleared on the main thread just before
     * each enqueue, so a start that races the enqueue is never wiped. */
    @Volatile
    private var startSeen = false
    private var lastEnqueueUptime = 0L

    /** Backstop for callbacks that arrive late or never (engine died, binder
     * dropped them): while focus is held, asks the engine whether it is still
     * speaking and releases as soon as it is not. */
    private val backstop =
        object : Runnable {
            override fun run() {
                if (!ledger.isHolding) return
                val sinceEnqueue = SystemClock.uptimeMillis() - lastEnqueueUptime
                if (FocusLedger.shouldForceRelease(tts.isSpeaking(), startSeen, sinceEnqueue)) {
                    Log.w(
                        TAG,
                        "engine idle for ${sinceEnqueue}ms without completion callbacks — force-releasing audio focus",
                    )
                    ledger.forceRelease()
                } else {
                    handler.postDelayed(this, BACKSTOP_POLL_MS)
                }
            }
        }

    /** True when the requested locale has no installed voice (surfaced in M4 UX). */
    var voiceMissing = false
        private set

    private var tts: TextToSpeech = createEngine()

    private fun createEngine(): TextToSpeech =
        TextToSpeech(context) { status ->
            if (released) {
                // Engine bound after a quick start→stop: a shut-down engine
                // would report a missing voice onto an idle screen.
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                onInitialized()
            } else {
                // Engine failed to bind: every speak() would be silently
                // swallowed. Surface it as the voice-missing warning.
                bindFailed = true
                rebuilding = false
                voiceMissing = true
                onVoiceStatus(true)
            }
        }

    /**
     * The engine's service went away mid-ride (voice app updated by the
     * store, killed for memory): the bound instance never recovers and every
     * speak() returns ERROR from then on. Replace it, keep the alert that
     * exposed the loss, and show the voice warning until the new one speaks.
     */
    private fun rebuildEngine(
        text: String,
        chime: Boolean,
    ) {
        pending.addLast(text to chime)
        if (rebuilding) return
        Log.w(TAG, "TTS engine rejected an utterance — rebinding the engine")
        rebuilding = true
        ready = false
        voiceMissing = true
        onVoiceStatus(true)
        ledger.forceRelease()
        handler.removeCallbacks(backstop)
        runCatching { tts.shutdown() }
        tts = createEngine()
    }

    private fun onInitialized() {
        val result = tts.setLanguage(locale)
        voiceMissing = result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
        onVoiceStatus(voiceMissing)
        tts.setAudioAttributes(attributes)
        tts.addEarcon(EARCON_CHIME, context.packageName, R.raw.chime)
        tts.addEarcon(EARCON_ALL_CLEAR, context.packageName, R.raw.all_clear)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    startSeen = true
                }

                override fun onDone(utteranceId: String?) = ledger.complete(utteranceId)

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = ledger.complete(utteranceId)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = ledger.complete(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = ledger.complete(utteranceId)
            },
        )
        ready = true
        rebuilding = false
        bindFailed = false
        while (pending.isNotEmpty()) {
            val (text, chime) = pending.removeFirst()
            speak(text, chime)
        }
    }

    /**
     * Speaks [text]. An [urgent] announcement (a camera ahead) flushes
     * whatever is still queued — a section summary or an all-clear tone must
     * not hold a camera alert back for seconds while the camera closes in.
     */
    fun speak(
        text: String,
        chime: Boolean = false,
        urgent: Boolean = false,
    ) {
        if (bindFailed) return
        if (!ready) {
            // TTS engines take a moment to bind; keep every alert, in order.
            pending.addLast(text to chime)
            return
        }
        // The ledger ignores requestAudioFocus's result on purpose: safety
        // alerts must speak even when focus is denied (e.g. during a call),
        // and abandoning a never-granted request is harmless.
        startSeen = false
        var rejected = false
        val enqueued =
            ledger.announce {
                buildList {
                    var mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (chime) {
                        val chimeId = "twsp-${utteranceSeq++}"
                        if (tts.playEarcon(EARCON_CHIME, mode, null, chimeId) == TextToSpeech.SUCCESS) add(chimeId)
                        mode = TextToSpeech.QUEUE_ADD
                    }
                    val utteranceId = "twsp-${utteranceSeq++}"
                    if (tts.speak(text, mode, null, utteranceId) == TextToSpeech.SUCCESS) {
                        add(utteranceId)
                    } else {
                        rejected = true
                    }
                }
            }
        if (rejected) {
            rebuildEngine(text, chime)
            return
        }
        if (enqueued) armBackstop()
    }

    /** Descending two-tone earcon, no speech: the alerted camera is behind.
     * Deliberately dropped (not queued) while TTS is still binding — an
     * all-clear is only true at the moment it happens. */
    fun playAllClear() {
        if (!ready || bindFailed) return
        startSeen = false
        val enqueued =
            ledger.announce {
                val utteranceId = "twsp-${utteranceSeq++}"
                when (tts.playEarcon(EARCON_ALL_CLEAR, TextToSpeech.QUEUE_ADD, null, utteranceId)) {
                    TextToSpeech.SUCCESS -> listOf(utteranceId)
                    else -> emptyList()
                }
            }
        if (enqueued) armBackstop()
    }

    /** One poll chain at a time, restarted on every successful enqueue so
     * back-to-back alerts keep pushing the grace and the hard cap out. A tick
     * after the queue drained normally is a no-op. */
    private fun armBackstop() {
        lastEnqueueUptime = SystemClock.uptimeMillis()
        handler.removeCallbacks(backstop)
        handler.postDelayed(backstop, BACKSTOP_POLL_MS)
    }

    /**
     * Suspends until the queue has drained — the engine is bound, nothing is
     * waiting for it, and focus is back — or [timeoutMs] has passed. Lets a
     * final announcement ("stopping detection") finish instead of being cut
     * at a fixed delay, whatever the language or a cold engine's bind time.
     */
    suspend fun awaitIdle(timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            // An engine that refused to bind will never drain anything.
            if (bindFailed) return
            if (ready && pending.isEmpty() && !ledger.isHolding) return
            delay(IDLE_POLL_MS)
        }
        Log.w(TAG, "announcement still playing after ${timeoutMs}ms — not waiting longer")
    }

    fun release() {
        released = true
        handler.removeCallbacks(backstop)
        tts.stop()
        tts.shutdown()
        ledger.forceRelease()
    }

    companion object {
        private const val TAG = "Announcer"
        private const val EARCON_CHIME = "[twsp_chime]"
        private const val EARCON_ALL_CLEAR = "[twsp_all_clear]"
        private const val BACKSTOP_POLL_MS = 500L
        private const val IDLE_POLL_MS = 100L
    }
}
