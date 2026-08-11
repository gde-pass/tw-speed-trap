package io.github.gdepass.twspeedtrap.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks alerts through the navigation-guidance audio channel.
 *
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + transient-may-duck focus is what
 * makes music duck instead of stopping, and keeps alerts audible over
 * Bluetooth while Android Auto owns the media stream.
 */
class Announcer(
    context: Context,
    private val locale: Locale,
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
    private var pendingText: String? = null
    private var utteranceSeq = 0

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
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }
            },
        )
        ready = true
        pendingText?.let { speak(it) }
        pendingText = null
    }

    fun speak(text: String) {
        if (!ready) {
            // TTS engines take a moment to bind; keep the most recent alert.
            pendingText = text
            return
        }
        audioManager.requestAudioFocus(focusRequest)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "twsp-${utteranceSeq++}")
    }

    fun release() {
        tts.stop()
        tts.shutdown()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
