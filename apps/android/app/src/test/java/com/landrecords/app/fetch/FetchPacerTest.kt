package com.landrecords.app.fetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6's backoff. There is no backoff anywhere in the app today, so this is the first line of
 * defence against AnyRoR's WAF — and an escalation curve is the kind of thing that looks right
 * and is off by a factor of two.
 */
class FetchPacerTest {

    private val pacer = FetchPacer(baseDelayMs = 1_000, firstPenaltyMs = 5_000, maxPenaltyMs = 60_000, maxAttempts = 5)

    @Test
    fun noPenaltyUntilSomethingGoesWrong() {
        assertEquals(0, pacer.penaltyMs())
        assertFalse(pacer.isParked())
    }

    @Test
    fun penaltyDoublesAndThenCaps() {
        assertEquals(0L, pacer.penaltyFor(0))
        assertEquals(5_000L, pacer.penaltyFor(1))
        assertEquals(10_000L, pacer.penaltyFor(2))
        assertEquals(20_000L, pacer.penaltyFor(3))
        assertEquals(40_000L, pacer.penaltyFor(4))
        // Capped: past here a longer sleep helps nobody.
        assertEquals(60_000L, pacer.penaltyFor(5))
        assertEquals(60_000L, pacer.penaltyFor(50))
    }

    @Test
    fun blocksEscalateAndSuccessClearsImmediately() {
        pacer.onBlocked()
        assertEquals(5_000L, pacer.penaltyMs())
        pacer.onBlocked()
        assertEquals(10_000L, pacer.penaltyMs())

        // Recovery is immediate, not gradual: one good response means we are not blocked.
        pacer.onSuccess()
        assertEquals(0L, pacer.penaltyMs())
        assertFalse(pacer.isParked())
    }

    @Test
    fun parksAfterRepeatedBlocksInsteadOfSpinning() {
        repeat(5) { pacer.onBlocked() }
        assertTrue("must park rather than keep asking a WAF that is refusing us", pacer.isParked())
        // And it does not escalate past the cap no matter how many more failures arrive.
        repeat(20) { pacer.onBlocked() }
        assertEquals(60_000L, pacer.penaltyMs())
    }

    /**
     * Mistaking a block for "no records" would mark a survey permanently empty — a data error
     * the user has no way to notice. Backing off unnecessarily only costs time, so the
     * detector is deliberately generous.
     */
    @Test
    fun blockDetectionErrsTowardsBackingOff() {
        assertTrue(FetchPacer.looksBlocked(429, null))
        assertTrue(FetchPacer.looksBlocked(403, null))
        assertTrue(FetchPacer.looksBlocked(503, null))
        assertTrue(FetchPacer.looksBlocked(200, "Access Denied"))
        assertTrue(FetchPacer.looksBlocked(200, "Request unsuccessful. Incapsula incident ID..."))
        assertTrue(FetchPacer.looksBlocked(200, "too many requests"))
        assertTrue(FetchPacer.looksBlocked(200, "TOO MANY REQUESTS")) // case-insensitive

        // A genuinely empty result is NOT a block, or every survey with no cases would retry
        // four times and then show as failed.
        assertFalse(FetchPacer.looksBlocked(200, "No records found for this survey number."))
        assertFalse(FetchPacer.looksBlocked(200, ""))
        assertFalse(FetchPacer.looksBlocked(200, null))
    }

    @Test
    fun resetClearsEverything() {
        repeat(3) { pacer.onBlocked() }
        pacer.reset()
        assertEquals(0L, pacer.penaltyMs())
        assertFalse(pacer.isParked())
    }
}
