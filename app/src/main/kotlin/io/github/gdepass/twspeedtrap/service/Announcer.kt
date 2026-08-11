package io.github.gdepass.twspeedtrap.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    /** Focus is released when the *last queued* utterance finishes, so a chime
     * followed by speech holds focus across both. */
    @Volatile
    private var lastUtteranceId: String? = null

    /** True when the requested locale has no installed voice (surfaced in M4 UX). */
    var voiceMissing = false
        private set

    private val tts: TextToSpeech =
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                onInitialized()
            }
        }

    private fun onInitialized() {
        val result = tts.setLanguage(locale)
        voiceMissing = result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
        onVoiceStatus(voiceMissing)
        tts.setAudioAttributes(attributes)
        tts.addEarcon(EARCON_CHIME, context.packageName, R.raw.chime)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastUtteranceId) audioManager.abandonAudioFocusRequest(focusRequest)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == lastUtteranceId) audioManager.abandonAudioFocusRequest(focusRequest)
                }
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
        audioManager.requestAudioFocus(focusRequest)
        if (chime) {
            tts.playEarcon(EARCON_CHIME, TextToSpeech.QUEUE_ADD, null, "twsp-${utteranceSeq++}")
        }
        val utteranceId = "twsp-${utteranceSeq++}"
        lastUtteranceId = utteranceId
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun release() {
        tts.stop()
        tts.shutdown()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        private const val EARCON_CHIME = "[twsp_chime]"
    }
}
