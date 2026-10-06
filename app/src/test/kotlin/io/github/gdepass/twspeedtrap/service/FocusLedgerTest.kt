package io.github.gdepass.twspeedtrap.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLedgerTest {
    private var requests = 0
    private var abandons = 0
    private val ledger = FocusLedger(requestFocus = { requests++ }, abandonFocus = { abandons++ })

    @Test
    fun `focus held until the only utterance completes`() {
        assertTrue(ledger.announce { listOf("a") })
        assertEquals(1, requests)
        assertEquals(0, abandons)
        ledger.complete("a")
        assertEquals(1, abandons)
    }

    @Test
    fun `chime plus speech abandons only after both complete`() {
        ledger.announce { listOf("chime", "speech") }
        ledger.complete("chime")
        assertEquals(0, abandons)
        ledger.complete("speech")
        assertEquals(1, abandons)
    }

    @Test
    fun `failed enqueue rolls the focus request back`() {
        assertFalse(ledger.announce { emptyList() })
        assertEquals(1, requests)
        assertEquals(1, abandons)
    }

    @Test
    fun `failed enqueue keeps focus while an earlier utterance still plays`() {
        ledger.announce { listOf("a") }
        assertFalse(ledger.announce { emptyList() })
        assertEquals(0, abandons)
        ledger.complete("a")
        assertEquals(1, abandons)
    }

    @Test
    fun `late completion of a finished utterance cannot un-duck the next alert`() {
        ledger.announce { listOf("a") }
        ledger.announce { listOf("b") }
        ledger.complete("a")
        assertEquals(0, abandons)
        ledger.complete("b")
        assertEquals(1, abandons)
    }

    @Test
    fun `unknown and null ids are ignored`() {
        ledger.announce { listOf("a") }
        ledger.complete("nope")
        ledger.complete(null)
        assertEquals(0, abandons)
    }

    @Test
    fun `forceRelease abandons held focus once and is idle-safe`() {
        ledger.announce { listOf("a") }
        ledger.forceRelease()
        assertEquals(1, abandons)
        ledger.forceRelease()
        assertEquals(1, abandons)
    }

    @Test
    fun `completion after forceRelease does not double-abandon`() {
        ledger.announce { listOf("a") }
        ledger.forceRelease()
        ledger.complete("a")
        assertEquals(1, abandons)
    }

    @Test
    fun `back-to-back announcements hold focus continuously`() {
        ledger.announce { listOf("a") }
        ledger.announce { listOf("b") }
        ledger.announce { listOf("c") }
        ledger.complete("a")
        ledger.complete("b")
        assertEquals(0, abandons)
        ledger.complete("c")
        assertEquals(1, abandons)
    }

    @Test
    fun `isHolding tracks the request and its abandon`() {
        assertFalse(ledger.isHolding)
        ledger.announce { listOf("a") }
        assertTrue(ledger.isHolding)
        ledger.complete("a")
        assertFalse(ledger.isHolding)
    }

    @Test
    fun `backstop keeps focus while the engine is still speaking`() {
        assertFalse(FocusLedger.shouldForceRelease(engineSpeaking = true, startSeen = true, sinceEnqueueMs = 10_000))
        assertFalse(FocusLedger.shouldForceRelease(engineSpeaking = true, startSeen = false, sinceEnqueueMs = 10_000))
    }

    @Test
    fun `backstop releases as soon as a started utterance goes idle`() {
        assertTrue(FocusLedger.shouldForceRelease(engineSpeaking = false, startSeen = true, sinceEnqueueMs = 500))
    }

    @Test
    fun `backstop gives a never-started utterance a grace period`() {
        assertFalse(
            FocusLedger.shouldForceRelease(
                engineSpeaking = false,
                startSeen = false,
                sinceEnqueueMs = FocusLedger.START_GRACE_MS - 1,
            ),
        )
        assertTrue(
            FocusLedger.shouldForceRelease(
                engineSpeaking = false,
                startSeen = false,
                sinceEnqueueMs = FocusLedger.START_GRACE_MS,
            ),
        )
    }

    @Test
    fun `backstop hard cap releases even a busy engine`() {
        assertTrue(
            FocusLedger.shouldForceRelease(
                engineSpeaking = true,
                startSeen = false,
                sinceEnqueueMs = FocusLedger.HARD_CAP_MS,
            ),
        )
    }
}
