package com.landrecords.app.data.sync

import com.landrecords.app.data.identity.Identity

/**
 * Old survey numbers — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §2.
 *
 * VF-7/12 uses a different dropdown (`ddlOldScannedSno`) whose numbers do not map 1:1 onto
 * current survey numbers: survey `174/p1` may correspond to `174`, `174/1`, `174/2`, … We
 * auto-match candidates, download what we can, and **the user decides** which links to keep,
 * remove, or add.
 *
 * Mirrored on the desktop in `src/old-survey-match.mjs`; both are held to the
 * `oldSurveyMatch` section of `tools/identity/vectors.json`.
 *
 * This is the only data in the system that cannot be recovered by re-fetching, so three rules
 * are load-bearing and are enforced here and in [MergeEngine]:
 *
 *  1. **`rejected` is stored.** Storing only what the user keeps would make auto-matching
 *     re-propose the same wrong candidates on every fetch, forever. A rejection is knowledge.
 *  2. **The uid is the pair** (`hash("sl", current_survey_uid, old_token)`), so the same
 *     decision made on the laptop and the phone is one row that merges to one row.
 *  3. **Scrapers never curate.** Auto-matching may only insert `candidate` rows.
 */
object OldSurveyMatcher {

    /** How many candidates the user gets to see before being asked to widen the fetch (§2). */
    const val FETCH_WITHOUT_ASKING = 5

    enum class State { CANDIDATE, CONFIRMED, REJECTED, MANUAL;
        val wire: String get() = name.lowercase()
    }

    data class Candidate(
        /** The raw `ddlOldScannedSno` option text, needed verbatim by `selectOption`. */
        val raw: String,
        /** Its canonical token — what the link is keyed on. */
        val token: String,
        /** True when this is the current survey's own number, not a related one. */
        val exact: Boolean,
    )

    /**
     * A fetch plan: which candidates to pull now, and whether to stop and ask first.
     *
     * Candidates are fetched *before* the user curates, so he judges actual documents rather
     * than bare numbers. But a wide match must not silently become twenty captchas — so at
     * most [FETCH_WITHOUT_ASKING] are pulled up front, and he always has at least that many
     * real documents in front of him when he decides.
     */
    data class Plan(
        val fetchNow: List<Candidate>,
        val deferred: List<Candidate>,
    ) {
        /** True when the match was wide enough that we stop and ask before fetching the rest. */
        val mustAsk: Boolean get() = deferred.isNotEmpty()
    }

    /**
     * Rank candidates and decide how many to fetch: exact token match first, then nearest
     * numerically to the current survey's leading block number, then by token for a stable
     * order (two equally-distant numbers must not depend on DOM ordering).
     */
    fun plan(currentToken: String, options: List<String>): Plan {
        val ranked = rank(currentToken, options)
        return if (ranked.size <= FETCH_WITHOUT_ASKING) {
            Plan(ranked, emptyList())
        } else {
            Plan(ranked.take(FETCH_WITHOUT_ASKING), ranked.drop(FETCH_WITHOUT_ASKING))
        }
    }

    fun rank(currentToken: String, options: List<String>): List<Candidate> {
        val current = Identity.surveyToken(currentToken)
        val target = leadingNumber(current)
        return options
            .map { raw -> Candidate(raw, Identity.surveyToken(raw), exact = Identity.surveyToken(raw) == current) }
            .filter { it.token.isNotEmpty() }
            .distinctBy { it.token }
            .sortedWith(
                compareBy(
                    { !it.exact },
                    { distance(leadingNumber(it.token), target) },
                    { it.token },
                ),
            )
    }

    /**
     * The leading block number of a token: "174_P1" → 174. Null when it does not start with
     * digits, which sorts such options last rather than pretending they are near anything.
     */
    fun leadingNumber(token: String): Int? =
        Regex("^(\\d+)").find(token)?.groupValues?.get(1)?.toIntOrNull()

    private fun distance(a: Int?, b: Int?): Int =
        if (a == null || b == null) Int.MAX_VALUE else kotlin.math.abs(a - b)

    /**
     * Build the `survey_link` row for a decision. `source` records who decided, which is what
     * lets a later audit tell an auto-proposal from the user's own choice.
     */
    fun linkRow(currentSurveyUid: String, oldToken: String, state: State, source: String): Map<String, Any?> =
        mapOf(
            "uid" to Identity.surveyLinkUid(currentSurveyUid, oldToken),
            "current_survey_uid" to currentSurveyUid,
            "old_token" to oldToken,
            "state" to state.wire,
            "source" to source,
            "deleted" to 0,
        )

    /**
     * Which of [ranked] still need a decision: anything the user has not already curated.
     * A previously `rejected` token is deliberately NOT re-offered — that is rule 1 doing its
     * job, and the reason rejections are stored at all.
     */
    fun needsDecision(ranked: List<Candidate>, existing: Map<String, String>): List<Candidate> =
        ranked.filter { (existing[it.token] ?: "candidate") == "candidate" }
}
