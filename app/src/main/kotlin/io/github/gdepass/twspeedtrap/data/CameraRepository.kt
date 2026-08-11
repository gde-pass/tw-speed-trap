package io.github.gdepass.twspeedtrap.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.gdepass.twspeedtrap.detection.Camera
import io.github.gdepass.twspeedtrap.detection.CameraType
import io.github.gdepass.twspeedtrap.detection.Section
import java.io.File

/**
 * Owns the camera SQLite file. The database is produced by the pipeline and
 * treated as read-only here; on first run it is copied out of assets/ so that
 * later self-updates can atomically replace the same path.
 */
class CameraRepository(
    private val context: Context,
) {
    fun databaseFile(): File = File(context.noBackupFilesDir, DB_NAME)

    fun ensureDatabase(): File {
        val file = databaseFile()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

    fun loadCameras(): List<Camera> =
        openReadOnly().use { db ->
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
                                    city = cursor.getString(6),
                                    description = cursor.getString(7),
                                    sectionId = cursor.getString(8),
                                    sectionRole = cursor.getString(9),
                                ),
                            )
                        }
                    }
                }
        }

    fun loadSections(): Map<String, Section> =
        openReadOnly().use { db ->
            db.rawQuery("SELECT id, speed_limit, length_m FROM sections", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        put(id, Section(id, cursor.getInt(1), cursor.getDouble(2)))
                    }
                }
            }
        }

    fun metadata(): Map<String, String> =
        openReadOnly().use { db ->
            db.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        }

    private fun openReadOnly(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(ensureDatabase().path, null, SQLiteDatabase.OPEN_READONLY)

    companion object {
        const val DB_NAME = "cameras.db"
    }
}
