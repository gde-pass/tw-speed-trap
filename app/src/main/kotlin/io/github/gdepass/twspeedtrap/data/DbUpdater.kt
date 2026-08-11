package io.github.gdepass.twspeedtrap.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File

sealed interface UpdateResult {
    data class Updated(
        val dataVersion: String,
        val count: Int,
    ) : UpdateResult

    data object UpToDate : UpdateResult

    data class Failed(
        val reason: String,
        /** True when retrying cannot help (e.g. the data schema needs a newer app). */
        val permanent: Boolean = false,
    ) : UpdateResult
}

/**
 * Fetches manifest.json from the rolling `data` release, and if it announces
 * a newer database: downloads it (from this repo's release URLs only, with
 * size caps), verifies the SHA-256, that SQLite can read it, and that its
 * own metadata matches the manifest, then swaps it in atomically
 * (same-directory rename). Any failure leaves the current database untouched.
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
            fetch(MANIFEST_URL, MAX_MANIFEST_BYTES) ?: return UpdateResult.Failed("manifest download failed")
        val manifest = UpdateVerifier.parseManifest(manifestText.decodeToString())
        if (manifest.schemaVersion > UpdateVerifier.SUPPORTED_SCHEMA_VERSION) {
            return UpdateResult.Failed("data schema ${manifest.schemaVersion} needs a newer app", permanent = true)
        }
        if (!UpdateVerifier.isValidVersion(manifest.dataVersion)) {
            return UpdateResult.Failed("unrecognized data_version format")
        }
        if (!UpdateVerifier.isTrustedUrl(manifest.url)) {
            return UpdateResult.Failed("untrusted database URL")
        }
        val localVersion = repository.metadata()["data_version"]
        if (!UpdateVerifier.isNewer(manifest.dataVersion, localVersion)) return UpdateResult.UpToDate

        val dbBytes = fetch(manifest.url, MAX_DB_BYTES) ?: return UpdateResult.Failed("database download failed")
        if (!UpdateVerifier.sha256Hex(dbBytes).equals(manifest.sha256, ignoreCase = true)) {
            return UpdateResult.Failed("checksum mismatch")
        }

        val target = repository.databaseFile()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.delete() // orphan from an earlier crashed attempt
        tmp.writeBytes(dbBytes)
        if (!sqliteValid(tmp, manifest)) {
            tmp.delete()
            return UpdateResult.Failed("downloaded database failed validation")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return UpdateResult.Failed("could not replace database file")
        }
        Log.i(TAG, "database updated to ${manifest.dataVersion} (${manifest.count} cameras)")
        return UpdateResult.Updated(manifest.dataVersion, manifest.count)
    }

    /** Reads at most [maxBytes]; anything larger is treated as a failed download. */
    private fun fetch(
        url: String,
        maxBytes: Long,
    ): ByteArray? =
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
                val body = response.body
                if (body.contentLength() > maxBytes) return null
                val buffer = Buffer()
                val source = body.source()
                while (source.read(buffer, SEGMENT_BYTES) != -1L) {
                    if (buffer.size > maxBytes) return null
                }
                buffer.readByteArray()
            }

    /** The database must be readable AND describe itself exactly as the manifest does. */
    private fun sqliteValid(
        file: File,
        manifest: UpdateVerifier.DataManifest,
    ): Boolean =
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cameraCount =
                    db.rawQuery("SELECT COUNT(*) FROM cameras", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                // The sections table must exist too: a db missing it would only
                // crash later, at the next detection start.
                db.rawQuery("SELECT COUNT(*) FROM sections", null).use { it.moveToFirst() }
                val dataVersion =
                    db.rawQuery("SELECT value FROM meta WHERE key = 'data_version'", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                cameraCount >= MIN_CAMERA_COUNT &&
                    cameraCount == manifest.count &&
                    dataVersion == manifest.dataVersion
            }
        }.getOrDefault(false)

    companion object {
        private const val TAG = "DbUpdater"
        private const val USER_AGENT = "tw-speed-trap-app"
        const val MANIFEST_URL =
            "https://github.com/gde-pass/tw-speed-trap/releases/download/data/manifest.json"
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_DB_BYTES = 32L * 1024 * 1024
        private const val SEGMENT_BYTES = 8_192L

        /** A plausible national database is thousands of rows; a tiny one means a broken build upstream. */
        private const val MIN_CAMERA_COUNT = 500
    }
}
