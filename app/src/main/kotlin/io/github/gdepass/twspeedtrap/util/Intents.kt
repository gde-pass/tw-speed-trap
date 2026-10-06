package io.github.gdepass.twspeedtrap.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.gdepass.twspeedtrap.R

/**
 * Implicit intents into system screens (TTS install, battery exemption,
 * overlay permission, a browser) have no handler on some builds — and the
 * TTS-install one is shown exactly when no engine is installed, which is
 * when it does not resolve. A missing handler must inform, not crash.
 */
fun Context.startActivitySafely(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Intents", "no activity for $intent", e)
        Toast.makeText(this, R.string.no_app_for_action, Toast.LENGTH_LONG).show()
        false
    }

/** This app's page in system settings: the only way out of a permanent permission denial. */
fun Context.appSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
