package io.github.gdepass.twspeedtrap.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Pure helpers for the update flow, kept side-effect-free for unit testing. */
object UpdateVerifier {
    const val SUPPORTED_SCHEMA_VERSION = 1

    /**
     * P-256 public key (X.509 SubjectPublicKeyInfo, PEM body) whose private
     * half signs `manifest.json` in the data-update workflow. The SHA-256 in
     * the manifest proves the db matches the manifest; this signature proves
     * the manifest came from the release pipeline and not from anyone who
     * can write the `data` release. Rotating it means shipping a new app.
     */
    private const val MANIFEST_PUBLIC_KEY_PEM =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9W2c1pXu7wpWjhrtvPaCM60mKw+Z" +
            "StwWAn4LHl70UFa/VteUj4A8T/2rfEriBS674YvWk1ssFGVqC8bJ7j9N9w=="

    val manifestPublicKey: PublicKey by lazy { decodePublicKey(MANIFEST_PUBLIC_KEY_PEM) }

    fun decodePublicKey(pemBody: String): PublicKey =
        KeyFactory
            .getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(pemBody)))

    /**
     * Verifies an `openssl dgst -sha256 -sign` signature (DER-encoded ECDSA)
     * over the exact manifest bytes. Any decoding or key error is "not
     * verified", never an exception into the update flow.
     */
    fun verifySignature(
        manifestBytes: ByteArray,
        signature: ByteArray,
        key: PublicKey = manifestPublicKey,
    ): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(manifestBytes)
                verify(signature)
            }
        }.getOrDefault(false)

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

    /** The manifest names the db URL; only this repo's release downloads are
     * followed. The path is normalised first so `..` segments cannot escape
     * the prefix. */
    fun isTrustedUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url).normalize()
            uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.userInfo == null &&
                uri.rawPath.orEmpty().startsWith(DOWNLOAD_PATH_PREFIX)
        }.getOrDefault(false)

    private val VERSION_FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
    private const val DOWNLOAD_PATH_PREFIX = "/gde-pass/tw-speed-trap/releases/download/"
}
