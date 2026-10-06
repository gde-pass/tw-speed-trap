package io.github.gdepass.twspeedtrap.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface UpdateResult {
    data class Updated(
        val dataVersion: String,
        val count: Int,
    ) : UpdateResult

    data object UpToDate : UpdateResult

    data class Failed(
        val reason: String,
        /** True when retrying cannot help until the published data changes. */
        val permanent: Boolean = false,
    ) : UpdateResult
}

/**
 * Fetches manifest.json and its detached signature from the rolling `data`
 * release. The signature (P-256, key compiled into the app) must verify
 * before anything in the manifest is trusted; if it then announces a newer
 * database: downloads it (from this repo's release URLs only, with size
 * caps), verifies the SHA-256, runs the exact queries detection runs
 * against it and checks its metadata, then swaps it in (fsync + same-
 * directory rename). Any failure leaves the current database untouched.
 *
 * Bad published data (signature, checksum, validation) is a permanent
 * failure: it cannot improve until the next publish, and retrying would
 * re-download megabytes several times a day. Only network errors retry.
 */
class DbUpdater(
    context: Context,
) {
    private val repository = CameraRepository(context)

    @Suppress("TooGenericExceptionCaught") // an update must never crash the app, whatever fails
    suspend fun checkAndUpdate(): UpdateResult =
        // The manual button and the worker must not race on the same file.
        updateLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    update()
                } catch (e: Exception) {
                    Log.w(TAG, "update failed", e)
                    UpdateResult.Failed(e.message ?: "unknown error")
                }
            }
        }

    @Suppress("ReturnCount") // guard-clause validation chain reads best with early returns
    private fun update(): UpdateResult {
        val manifestBytes =
            fetch(MANIFEST_URL, MAX_MANIFEST_BYTES) ?: return UpdateResult.Failed("manifest download failed")
        val signature =
            fetch(SIGNATURE_URL, MAX_SIGNATURE_BYTES) ?: return UpdateResult.Failed("signature download failed")
        if (!UpdateVerifier.verifySignature(manifestBytes, signature)) {
            // Not permanent: the release uploads manifest and signature one
            // after the other, so a check landing in that window sees a new
            // manifest with last week's signature. Retrying costs two tiny
            // fetches on the backoff curve; a genuinely bad signature keeps
            // failing and the database is never touched.
            return UpdateResult.Failed("manifest signature invalid")
        }
        val manifest = UpdateVerifier.parseManifest(manifestBytes.decodeToString())
        if (manifest.schemaVersion > UpdateVerifier.SUPPORTED_SCHEMA_VERSION) {
            return UpdateResult.Failed("data schema ${manifest.schemaVersion} needs a newer app", permanent = true)
        }
        if (manifest.schemaVersion != UpdateVerifier.SUPPORTED_SCHEMA_VERSION) {
            return UpdateResult.Failed("unexpected data schema ${manifest.schemaVersion}", permanent = true)
        }
        if (!UpdateVerifier.isValidVersion(manifest.dataVersion)) {
            return UpdateResult.Failed("unrecognized data_version format", permanent = true)
        }
        if (!UpdateVerifier.isTrustedUrl(manifest.url)) {
            return UpdateResult.Failed("untrusted database URL", permanent = true)
        }
        // An unreadable local file (the repository restores the bundled copy
        // on read) must not block the download that would replace it.
        val localVersion = runCatching { repository.metadata()["data_version"] }.getOrNull()
        if (!UpdateVerifier.isNewer(manifest.dataVersion, localVersion)) return UpdateResult.UpToDate

        val dbBytes = fetch(manifest.url, MAX_DB_BYTES) ?: return UpdateResult.Failed("database download failed")
        if (!UpdateVerifier.sha256Hex(dbBytes).equals(manifest.sha256, ignoreCase = true)) {
            return UpdateResult.Failed("checksum mismatch", permanent = true)
        }
        return install(dbBytes, manifest)
    }

    private fun install(
        dbBytes: ByteArray,
        manifest: UpdateVerifier.DataManifest,
    ): UpdateResult {
        val target = repository.databaseFile()
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("cameras-", ".tmp", target.parentFile)
        try {
            // fsync before the rename: on ext4/f2fs the rename can be journaled
            // before the data blocks, and a power loss then leaves an empty db.
            FileOutputStream(tmp).use { out ->
                out.write(dbBytes)
                out.fd.sync()
            }
            if (!sqliteValid(tmp, manifest)) {
                return UpdateResult.Failed("downloaded database failed validation", permanent = true)
            }
            if (!tmp.renameTo(target)) return UpdateResult.Failed("could not replace database file")
        } finally {
            tmp.delete()
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

    /** The database must load through the app's own queries AND describe
     * itself exactly as the manifest does. */
    private fun sqliteValid(
        file: File,
        manifest: UpdateVerifier.DataManifest,
    ): Boolean =
        runCatching {
            val (cameraCount, _) = repository.probe(file)
            val meta = repository.metadataOf(file)
            cameraCount >= MIN_CAMERA_COUNT &&
                cameraCount == manifest.count &&
                meta["data_version"] == manifest.dataVersion &&
                meta["schema_version"] == manifest.schemaVersion.toString()
        }.onFailure { Log.w(TAG, "downloaded database failed to load", it) }
            .getOrDefault(false)

    companion object {
        private const val TAG = "DbUpdater"
        private const val USER_AGENT = "tw-speed-trap-app"
        const val MANIFEST_URL =
            "https://github.com/gde-pass/tw-speed-trap/releases/download/data/manifest.json"
        const val SIGNATURE_URL =
            "https://github.com/gde-pass/tw-speed-trap/releases/download/data/manifest.json.sig"
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_SIGNATURE_BYTES = 4L * 1024
        private const val MAX_DB_BYTES = 32L * 1024 * 1024
        private const val SEGMENT_BYTES = 8_192L

        /** A plausible national database is thousands of rows; a tiny one means a broken build upstream. */
        private const val MIN_CAMERA_COUNT = 500

        private val updateLock = Mutex()

        /** One client per process: a dispatcher and connection pool are not per-check resources. */
        private val client: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .callTimeout(2, TimeUnit.MINUTES)
                // The trusted URL is https; a redirect must not downgrade it.
                .followSslRedirects(false)
                .build()
        }
    }
}
