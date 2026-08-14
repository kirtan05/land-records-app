package com.landrecords.app.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The shared pacer — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §6.
 *
 * There is no backoff anywhere in the app today. AnyRoR's WAF IP-blocks bursts; mobile IPs are
 * less exposed than this laptop's, but the backoff is what makes that safe rather than lucky.
 *
 * One pacer per host, shared by every fetcher for that host, so a block observed by one fetch
 * slows all of them down. The queue **parks** rather than hammering: [awaitTurn] suspends for
 * however long the current penalty says, and the caller does not get to opt out.
 *
 * Deliberately pure Kotlin (no Android) so the escalation curve is unit-testable — the one
 * part of §6 whose behaviour is easy to get subtly wrong and impossible to eyeball live.
 */
class FetchPacer(
    /** Politeness gap between two requests to the same host, even when nothing is wrong. */
    private val baseDelayMs: Long = 1_200,
    private val firstPenaltyMs: Long = 5_000,
    /** Ceiling. Past this we are parked, not retrying — a longer sleep helps nobody. */
    private val maxPenaltyMs: Long = 10 * 60_000,
    private val maxAttempts: Int = 6,
) {
    private val mutex = Mutex()

    @Volatile private var consecutiveBlocks = 0
    @Volatile private var lastRequestAt = 0L

    /** Current penalty in ms — 0 when nothing has gone wrong. */
    fun penaltyMs(): Long = penaltyFor(consecutiveBlocks)

    /**
     * Exponential: 5s, 10s, 20s, 40s… capped. Deterministic and jitter-free by design; the
     * jitter is applied at the call site so this stays testable.
     */
    fun penaltyFor(blocks: Int): Long {
        if (blocks <= 0) return 0
        var d = firstPenaltyMs
        repeat(blocks - 1) { d = (d * 2).coerceAtMost(maxPenaltyMs) }
        return d.coerceAtMost(maxPenaltyMs)
    }

    /** True once we have backed off so many times that continuing is pointless. */
    fun isParked(): Boolean = consecutiveBlocks >= maxAttempts

    /**
     * Wait until it is polite to make the next request to this host. Serialized, so two
     * coroutines cannot both decide it is their turn.
     */
    suspend fun awaitTurn(now: () -> Long = { System.currentTimeMillis() }) {
        mutex.withLock {
            val wait = (lastRequestAt + baseDelayMs + penaltyMs()) - now()
            if (wait > 0) delay(wait)
            lastRequestAt = now()
        }
    }

    /** A request succeeded: clear the penalty. Recovery is immediate, not gradual. */
    fun onSuccess() {
        consecutiveBlocks = 0
    }

    /**
     * A request was blocked, rate-limited or refused. Escalates the penalty for EVERY fetcher
     * sharing this pacer.
     */
    fun onBlocked() {
        if (consecutiveBlocks < maxAttempts) consecutiveBlocks++
    }

    fun reset() {
        consecutiveBlocks = 0
        lastRequestAt = 0
    }

    companion object {
        /**
         * Does this response look like a block rather than an ordinary empty result?
         *
         * Kept generous on purpose: mistaking a block for "no records" would mark a survey
         * permanently empty, which is a data error the user would have no way to notice.
         * Backing off unnecessarily only costs time.
         */
        fun looksBlocked(httpStatus: Int, body: String?): Boolean {
            if (httpStatus == 429 || httpStatus == 403 || httpStatus in 500..599) return true
            val b = body ?: return false
            return Regex(
                "access denied|forbidden|blocked|too many requests|rate limit|" +
                    "request unsuccessful|incapsula|cloudflare|are you a robot",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(b)
        }
    }
}
