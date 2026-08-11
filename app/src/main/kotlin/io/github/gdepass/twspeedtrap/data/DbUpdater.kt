package io.github.gdepass.twspeedtrap.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface UpdateResult {
    data class Updated(
        val dataVersion: String,
        val count: Int,
    ) : UpdateResult

    data object UpToDate : UpdateResult

    data class Failed(
        val reason: String,
    ) : UpdateResult
}

/**
 * Fetches manifest.json from the rolling `data` release, and if it announces
 * a newer database: downloads it, verifies the SHA-256 and that SQLite can
 * actually read it, then swaps it in atomically (same-directory rename).
 * Any failure leaves the current database untouched.
 */
class DbUpdater(
    context: Context,
) {
    private val repository = CameraRepository(context)
    private val client = OkHttpClient()

    @Suppress("TooGenericExceptionCaught") // an update must never crash the app, whatever fails
    suspend fun checkAndUpdate(): UpdateResult =
        withContext(Dispatchers.IO) {
            try {
                update()
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                UpdateResult.Failed(e.message ?: "unknown error")
            }
        }

    @Suppress("ReturnCount") // guard-clause validation chain reads best with early returns
    private fun update(): UpdateResult {
        val manifestText =
            fetch(MANIFEST_URL) ?: return UpdateResult.Failed("manifest download failed")
        val manifest = UpdateVerifier.parseManifest(manifestText.decodeToString())
        if (manifest.schemaVersion > UpdateVerifier.SUPPORTED_SCHEMA_VERSION) {
            return UpdateResult.Failed("data schema ${manifest.schemaVersion} needs a newer app")
        }
        val localVersion = repository.metadata()["data_version"]
        if (!UpdateVerifier.isNewer(manifest.dataVersion, localVersion)) return UpdateResult.UpToDate

        val dbBytes = fetch(manifest.url) ?: return UpdateResult.Failed("database download failed")
        if (!UpdateVerifier.sha256Hex(dbBytes).equals(manifest.sha256, ignoreCase = true)) {
            return UpdateResult.Failed("checksum mismatch")
        }

        val target = repository.databaseFile()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(dbBytes)
        if (!sqliteReadable(tmp)) {
            tmp.delete()
            return UpdateResult.Failed("downloaded database is not readable")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return UpdateResult.Failed("could not replace database file")
        }
        Log.i(TAG, "database updated to ${manifest.dataVersion} (${manifest.count} cameras)")
        return UpdateResult.Updated(manifest.dataVersion, manifest.count)
    }

    private fun fetch(url: String): ByteArray? =
        client
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build(),
            ).execute()
            .use { response ->
                if (!response.isSuccessful) return null
                response.body.bytes()
            }

    private fun sqliteReadable(file: File): Boolean =
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM cameras", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) > 0
                }
            }
        }.getOrDefault(false)

    companion object {
        private const val TAG = "DbUpdater"
        private const val USER_AGENT = "tw-speed-trap-app"
        const val MANIFEST_URL =
            "https://github.com/gde-pass/tw-speed-trap/releases/download/data/manifest.json"
    }
}
