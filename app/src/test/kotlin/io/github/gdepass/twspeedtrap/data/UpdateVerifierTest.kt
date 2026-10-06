package io.github.gdepass.twspeedtrap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UpdateVerifierTest {
    @Test
    fun `parses manifest and ignores unknown keys`() {
        val manifest =
            UpdateVerifier.parseManifest(
                """
                {
                  "schema_version": 1,
                  "data_version": "2026-08-11",
                  "count": 1903,
                  "sha256": "abc123",
                  "content_hash": "ignored-by-app",
                  "url": "https://example.com/cameras.db",
                  "future_field": true
                }
                """.trimIndent(),
            )
        assertEquals(1, manifest.schemaVersion)
        assertEquals("2026-08-11", manifest.dataVersion)
        assertEquals(1903, manifest.count)
        assertEquals("https://example.com/cameras.db", manifest.url)
    }

    @Test
    fun `sha256 matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateVerifier.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `version comparison is lexicographic on ISO dates`() {
        assertTrue(UpdateVerifier.isNewer("2026-08-18", "2026-08-11"))
        assertFalse(UpdateVerifier.isNewer("2026-08-11", "2026-08-11"))
        assertFalse(UpdateVerifier.isNewer("2026-08-04", "2026-08-11"))
        assertTrue(UpdateVerifier.isNewer("2026-08-11", null))
        assertTrue(UpdateVerifier.isNewer("2026-08-11", ""))
    }

    @Test
    fun `version format guard accepts the pipeline format only`() {
        assertTrue(UpdateVerifier.isValidVersion("2026-08-11T12:39"))
        assertFalse(UpdateVerifier.isValidVersion("2026-08-11"))
        assertFalse(UpdateVerifier.isValidVersion("2026-8-1T9:5"))
        assertFalse(UpdateVerifier.isValidVersion("v2"))
        assertFalse(UpdateVerifier.isValidVersion(""))
    }

    @Test
    fun `only this repo's release downloads are trusted urls`() {
        assertTrue(
            UpdateVerifier.isTrustedUrl(
                "https://github.com/gde-pass/tw-speed-trap/releases/download/data/cameras.db",
            ),
        )
        assertFalse(
            UpdateVerifier.isTrustedUrl(
                "http://github.com/gde-pass/tw-speed-trap/releases/download/data/cameras.db",
            ),
        )
        assertFalse(UpdateVerifier.isTrustedUrl("https://evil.example.com/cameras.db"))
        assertFalse(UpdateVerifier.isTrustedUrl("https://github.com/someone-else/repo/releases/download/x.db"))
        assertFalse(
            UpdateVerifier.isTrustedUrl(
                "https://github.com@evil.example.com/gde-pass/tw-speed-trap/releases/download/x",
            ),
        )
        assertFalse(UpdateVerifier.isTrustedUrl("not a url"))
    }

    @Test
    fun `dot segments cannot escape the trusted download prefix`() {
        assertFalse(
            UpdateVerifier.isTrustedUrl(
                "https://github.com/gde-pass/tw-speed-trap/releases/download/" +
                    "../../../other/repo/releases/download/x.db",
            ),
        )
        assertFalse(
            UpdateVerifier.isTrustedUrl("https://user@github.com/gde-pass/tw-speed-trap/releases/download/data/x.db"),
        )
    }

    // ---- manifest signature ------------------------------------------------

    private fun p256KeyPair() =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(
        bytes: ByteArray,
        key: java.security.PrivateKey,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(bytes)
            sign()
        }

    @Test
    fun `a manifest signed by the matching key verifies`() {
        val pair = p256KeyPair()
        val manifest = """{"schema_version":1}""".toByteArray()
        assertTrue(UpdateVerifier.verifySignature(manifest, sign(manifest, pair.private), pair.public))
    }

    @Test
    fun `a tampered manifest or a foreign key does not verify`() {
        val pair = p256KeyPair()
        val manifest = """{"schema_version":1,"count":2774}""".toByteArray()
        val signature = sign(manifest, pair.private)
        val tampered = """{"schema_version":1,"count":2775}""".toByteArray()
        assertFalse(UpdateVerifier.verifySignature(tampered, signature, pair.public))
        assertFalse(UpdateVerifier.verifySignature(manifest, signature, p256KeyPair().public))
        assertFalse(UpdateVerifier.verifySignature(manifest, byteArrayOf(1, 2, 3), pair.public))
        assertFalse(UpdateVerifier.verifySignature(manifest, ByteArray(0), pair.public))
    }

    @Test
    fun `the compiled-in public key decodes and round-trips through PEM encoding`() {
        val key = UpdateVerifier.manifestPublicKey
        assertEquals("EC", key.algorithm)
        val pem = Base64.getEncoder().encodeToString(key.encoded)
        assertEquals(key, UpdateVerifier.decodePublicKey(pem))
    }
}
