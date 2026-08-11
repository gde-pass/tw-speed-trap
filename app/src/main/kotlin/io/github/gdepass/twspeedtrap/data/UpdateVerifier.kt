package io.github.gdepass.twspeedtrap.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
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

    /** Fixed-width ISO minute format — the precondition for [isNewer] being lexicographic-safe. */
    fun isValidVersion(version: String): Boolean = VERSION_FORMAT.matches(version)

    /** The manifest names the db URL; only this repo's release downloads are followed. */
    fun isTrustedUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.rawPath.orEmpty().startsWith(DOWNLOAD_PATH_PREFIX)
        }.getOrDefault(false)

    private val VERSION_FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
    private const val DOWNLOAD_PATH_PREFIX = "/gde-pass/tw-speed-trap/releases/download/"
}
