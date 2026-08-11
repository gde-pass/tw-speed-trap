package io.github.gdepass.twspeedtrap.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Pure helpers for the update flow, kept side-effect-free for unit testing. */
object UpdateVerifier {
    const val SUPPORTED_SCHEMA_VERSION = 1

    @Serializable
    data class DataManifest(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("data_version") val dataVersion: String,
        val count: Int,
        val sha256: String,
        val url: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(text: String): DataManifest = json.decodeFromString(text)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Data versions are ISO dates, so lexicographic comparison is correct. */
    fun isNewer(
        remote: String,
        local: String?,
    ): Boolean = local.isNullOrEmpty() || remote > local
}
