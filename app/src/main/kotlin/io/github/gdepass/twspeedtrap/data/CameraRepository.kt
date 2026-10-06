package io.github.gdepass.twspeedtrap.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import io.github.gdepass.twspeedtrap.detection.Camera
import io.github.gdepass.twspeedtrap.detection.CameraType
import io.github.gdepass.twspeedtrap.detection.Section
import java.io.File
import java.io.FileOutputStream

/**
 * Owns the camera SQLite file. The database is produced by the pipeline and
 * treated as read-only here; on first run it is copied out of assets/ so that
 * later self-updates can atomically replace the same path.
 *
 * Fail-safe to the bundled data: a file SQLite cannot read (power loss during
 * the first copy or an update, bit rot) is replaced from assets and the read
 * retried once, so a broken file can never leave detection dead until the
 * user clears app data.
 */
class CameraRepository(
    private val context: Context,
) {
    fun databaseFile(): File = File(context.noBackupFilesDir, DB_NAME)

    fun ensureDatabase(): File {
        val file = databaseFile()
        synchronized(installLock) {
            if (!file.exists()) copyBundled(file) else refreshFromBundledOnce(file)
        }
        return file
    }

    /**
     * An app update ships a fresh bundled db, but the local copy from install
     * day would otherwise live on forever (auto-update off, or a schema bump
     * the old file predates). Once per app version, if the bundled data is
     * newer than the local file, it replaces it.
     */
    private fun refreshFromBundledOnce(local: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (prefs.getLong(KEY_CHECKED_VERSION, -1L) == versionCode) return
        val tmp = File.createTempFile("$DB_NAME.asset-", ".tmp", local.parentFile)
        runCatching {
            copyBundledTo(tmp)
            val bundledVersion = readVersionOf(tmp)
            val localVersion = runCatching { readVersionOf(local) }.getOrNull()
            if (UpdateVerifier.isNewer(bundledVersion, localVersion)) {
                Log.i(TAG, "bundled data $bundledVersion is newer than local $localVersion — replacing")
                check(tmp.renameTo(local)) { "could not replace $DB_NAME with the bundled copy" }
            }
        }.onSuccess {
            // Only a completed check is remembered: a failed one (low storage
            // on first launch) must run again next start, not next version.
            prefs.edit().putLong(KEY_CHECKED_VERSION, versionCode).apply()
        }.onFailure { Log.w(TAG, "bundled-data refresh check failed", it) }
        tmp.delete()
    }

    private fun readVersionOf(file: File): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key = 'data_version'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else ""
            }
        }

    /** tmp + fsync + rename: the final path never holds a partial file. */
    private fun copyBundled(target: File) {
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("$DB_NAME.asset-", ".tmp", target.parentFile)
        copyBundledTo(tmp)
        check(tmp.renameTo(target)) { "could not install bundled $DB_NAME" }
    }

    private fun copyBundledTo(file: File) {
        file.parentFile?.mkdirs()
        context.assets.open(DB_NAME).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    /**
     * Runs the exact queries detection runs against a candidate file (no
     * fallback, no retry): the only validation that proves a downloaded db
     * will load. Returns the camera and section counts.
     */
    fun probe(file: File): Pair<Int, Int> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            readCameras(db).size to readSections(db).size
        }

    fun metadataOf(file: File): Map<String, String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::readMeta)

    /** Both tables from one open, so a self-update landing between the two
     * reads cannot pair cameras of one data generation with sections of another. */
    fun loadAll(): Pair<List<Camera>, Map<String, Section>> = read { db -> readCameras(db) to readSections(db) }

    fun loadCameras(): List<Camera> = read(::readCameras)

    fun loadSections(): Map<String, Section> = read(::readSections)

    fun metadata(): Map<String, String> = read(::readMeta)

    private fun readMeta(db: SQLiteDatabase): Map<String, String> =
        db.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }

    /** Runs [block] on the database; an unreadable file is replaced from
     * assets and the read retried once. */
    private fun <T> read(block: (SQLiteDatabase) -> T): T =
        try {
            openReadOnly().use(block)
        } catch (e: SQLiteException) {
            Log.e(TAG, "camera database unreadable — restoring the bundled copy", e)
            synchronized(installLock) {
                val file = databaseFile()
                file.delete()
                File(file.parentFile, "${file.name}-journal").delete()
                copyBundled(file)
            }
            openReadOnly().use(block)
        }

    private fun readCameras(db: SQLiteDatabase): List<Camera> =
        db
            .rawQuery(
                """
                SELECT id, lat, lon, type, speed_limit, bearing, city, description,
                       section_id, section_role
                FROM cameras
                """.trimIndent(),
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Camera(
                                id = cursor.getString(0),
                                lat = cursor.getDouble(1),
                                lon = cursor.getDouble(2),
                                type = CameraType.from(cursor.getString(3)),
                                speedLimitKmh = if (cursor.isNull(4)) null else cursor.getInt(4),
                                bearingDeg = if (cursor.isNull(5)) null else cursor.getDouble(5),
                                city = cursor.getString(6) ?: "",
                                description = cursor.getString(7) ?: "",
                                sectionId = cursor.getString(8),
                                sectionRole = cursor.getString(9),
                            ),
                        )
                    }
                }
            }

    private fun readSections(db: SQLiteDatabase): Map<String, Section> =
        db.rawQuery("SELECT id, speed_limit, length_m FROM sections", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    put(id, Section(id, cursor.getInt(1), cursor.getDouble(2)))
                }
            }
        }

    private fun openReadOnly(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(ensureDatabase().path, null, SQLiteDatabase.OPEN_READONLY)

    companion object {
        /** Service load, map, settings and the worker can all install the
         * bundled copy at once; one process-wide lock keeps them from racing
         * on the same final path. */
        private val installLock = Any()

        const val DB_NAME = "cameras.db"
        private const val TAG = "CameraRepository"
        private const val PREFS = "camera_db"
        private const val KEY_CHECKED_VERSION = "bundled_checked_for_version_code"
    }
}
