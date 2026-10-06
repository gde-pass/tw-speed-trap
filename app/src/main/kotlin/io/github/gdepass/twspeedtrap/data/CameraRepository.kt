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
        if (!file.exists()) copyBundled(file)
        return file
    }

    /** tmp + fsync + rename: the final path never holds a partial file. */
    private fun copyBundled(target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.asset-tmp")
        context.assets.open(DB_NAME).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(tmp.renameTo(target)) { "could not install bundled $DB_NAME" }
    }

    /** Both tables from one open, so a self-update landing between the two
     * reads cannot pair cameras of one data generation with sections of another. */
    fun loadAll(): Pair<List<Camera>, Map<String, Section>> = read { db -> readCameras(db) to readSections(db) }

    fun loadCameras(): List<Camera> = read(::readCameras)

    fun loadSections(): Map<String, Section> = read(::readSections)

    fun metadata(): Map<String, String> =
        read { db ->
            db.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        }

    /** Runs [block] on the database; an unreadable file is replaced from
     * assets and the read retried once. */
    private fun <T> read(block: (SQLiteDatabase) -> T): T =
        try {
            openReadOnly().use(block)
        } catch (e: SQLiteException) {
            Log.e(TAG, "camera database unreadable — restoring the bundled copy", e)
            val file = databaseFile()
            file.delete()
            File(file.parentFile, "${file.name}-journal").delete()
            copyBundled(file)
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
        const val DB_NAME = "cameras.db"
        private const val TAG = "CameraRepository"
    }
}
