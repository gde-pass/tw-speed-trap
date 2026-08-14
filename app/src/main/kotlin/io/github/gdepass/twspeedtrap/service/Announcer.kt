package io.github.gdepass.twspeedtrap.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.gdepass.twspeedtrap.R
import java.util.Locale

/**
 * Speaks alerts through the navigation-guidance audio channel.
 *
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + transient-may-duck focus is what
 * makes music duck instead of stopping, and keeps alerts audible over
 * Bluetooth while Android Auto owns the media stream.
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
    private var pending: Pair<String, Boolean>? = null
    private var utteranceSeq = 0

    private val handler = Handler(Looper.getMainLooper())

    private val ledger =
        FocusLedger(
            requestFocus = { audioManager.requestAudioFocus(focusRequest) },
            abandonFocus = { audioManager.abandonAudioFocusRequest(focusRequest) },
        )

    /** Backstop for a TTS engine that dies mid-utterance and never calls back. */
    private val watchdog =
        Runnable {
            Log.w(TAG, "utterance callbacks never arrived — force-releasing audio focus")
            ledger.forceRelease()
        }

    /** True when the requested locale has no installed voice (surfaced in M4 UX). */
    var voiceMissing = false
        private set

    private val tts: TextToSpeech =
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                onInitialized()
            } else {
                // Engine failed to bind: every speak() would be silently
                // swallowed. Surface it as the voice-missing warning.
                voiceMissing = true
                onVoiceStatus(true)
            }
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
                override fun onStart(utteranceId: String?) = Unit

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
        pending?.let { (text, chime) -> speak(text, chime) }
        pending = null
    }

    fun speak(
        text: String,
        chime: Boolean = false,
    ) {
        if (!ready) {
            // TTS engines take a moment to bind; keep the most recent alert.
            pending = text to chime
            return
        }
        // The ledger ignores requestAudioFocus's result on purpose: safety
        // alerts must speak even when focus is denied (e.g. during a call),
        // and abandoning a never-granted request is harmless.
        val enqueued =
            ledger.announce {
                buildList {
                    if (chime) {
                        val chimeId = "twsp-${utteranceSeq++}"
                        val result = tts.playEarcon(EARCON_CHIME, TextToSpeech.QUEUE_ADD, null, chimeId)
                        if (result == TextToSpeech.SUCCESS) add(chimeId)
                    }
                    val utteranceId = "twsp-${utteranceSeq++}"
                    if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS) {
                        add(utteranceId)
                    }
                }
            }
        if (enqueued) armWatchdog()
    }

    /** Descending two-tone earcon, no speech: the alerted camera is behind.
     * Deliberately dropped (not queued) while TTS is still binding — an
     * all-clear is only true at the moment it happens. */
    fun playAllClear() {
        if (!ready) return
        val enqueued =
            ledger.announce {
                val utteranceId = "twsp-${utteranceSeq++}"
                when (tts.playEarcon(EARCON_ALL_CLEAR, TextToSpeech.QUEUE_ADD, null, utteranceId)) {
                    TextToSpeech.SUCCESS -> listOf(utteranceId)
                    else -> emptyList()
                }
            }
        if (enqueued) armWatchdog()
    }

    /** One pending watchdog at a time, re-armed on every successful enqueue,
     * so back-to-back alerts keep pushing the deadline out. A fire after the
     * queue drained normally is a no-op. */
    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        tts.stop()
        tts.shutdown()
        ledger.forceRelease()
    }

    companion object {
        private const val TAG = "Announcer"
        private const val EARCON_CHIME = "[twsp_chime]"
        private const val EARCON_ALL_CLEAR = "[twsp_all_clear]"
        private const val WATCHDOG_MS = 30_000L
    }
}
