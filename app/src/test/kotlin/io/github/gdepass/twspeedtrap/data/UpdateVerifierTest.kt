package io.github.gdepass.twspeedtrap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
