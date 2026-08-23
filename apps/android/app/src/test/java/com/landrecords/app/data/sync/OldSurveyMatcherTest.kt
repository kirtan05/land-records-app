package com.landrecords.app.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** App half of the §2 old-survey contract; the desktop half is `tools/identity/test.mjs`. */
class OldSurveyMatcherTest {

    private val spec: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/identity/vectors.json")
            if (f.isFile) return@lazy JSONObject(f.readText()).getJSONObject("oldSurveyMatch")
            dir = dir.parentFile
        }
        throw AssertionError("tools/identity/vectors.json not found — it is the shared fixture")
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    @Test
    fun threshold_matchesDesktop() {
        assertEquals(spec.getInt("fetchWithoutAsking"), OldSurveyMatcher.FETCH_WITHOUT_ASKING)
    }

    @Test
    fun rankAndPlan_matchVectors() {
        val cases = spec.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val current = c.getString("current")
            val options = strings(c.getJSONArray("options"))
            val why = c.getString("why")

            val ranked = OldSurveyMatcher.rank(current, options)
            assertEquals(why, strings(c.getJSONArray("rankedTokens")), ranked.map { it.token })

            val plan = OldSurveyMatcher.plan(current, options)
            assertEquals("$why (fetchNow)", c.getInt("fetchNow"), plan.fetchNow.size)
            assertEquals("$why (mustAsk)", c.getBoolean("mustAsk"), plan.mustAsk)
            // Nothing may be dropped: every candidate is either fetched now or deferred.
            assertEquals("$why (nothing lost)", ranked.size, plan.fetchNow.size + plan.deferred.size)
        }
    }

    @Test
    fun needsDecision_matchesVectors() {
        val cases = spec.getJSONArray("needsDecision")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ranked = strings(c.getJSONArray("ranked"))
                .map { OldSurveyMatcher.Candidate(raw = it, token = it, exact = false) }
            val existing = c.getJSONObject("existing").let { o ->
                o.keys().asSequence().associateWith { k -> o.getString(k) }
            }
            assertEquals(
                c.getString("why"),
                strings(c.getJSONArray("out")),
                OldSurveyMatcher.needsDecision(ranked, existing).map { it.token },
            )
        }
    }

    /**
     * §2 rule 2: the uid is the PAIR, so the same decision made on the laptop and on the phone
     * is one row that merges to one row — and a rejection propagates to both.
     */
    @Test
    fun linkUidIsThePairNotTheDecision() {
        val su = "gj:15:03:029/174_P1"
        val a = OldSurveyMatcher.linkRow(su, "174_1", OldSurveyMatcher.State.REJECTED, "user@laptop")
        val b = OldSurveyMatcher.linkRow(su, "174_1", OldSurveyMatcher.State.CONFIRMED, "user@phone")
        assertEquals("same pair must be one row whoever decided", a["uid"], b["uid"])

        val other = OldSurveyMatcher.linkRow(su, "174_2", OldSurveyMatcher.State.REJECTED, "user")
        assertNotEquals("a different old token is a different row", a["uid"], other["uid"])
    }

    /** The wire form must match the `state` strings the merge engine and schema expect. */
    @Test
    fun stateWireFormsMatchTheSchema() {
        assertEquals(
            SyncSchema.LINK_STATES.toSet(),
            OldSurveyMatcher.State.entries.map { it.wire }.toSet(),
        )
    }

    /**
     * A survey with no digits must not be treated as "distance 0" from everything — that
     * would rank junk options above genuinely close numbers.
     */
    @Test
    fun nonNumericTokensSortLast() {
        val ranked = OldSurveyMatcher.rank("174/p1", listOf("abc", "175", "174"))
        assertEquals(listOf("174", "175", "ABC"), ranked.map { it.token })
        assertEquals(null, OldSurveyMatcher.leadingNumber("ABC"))
        assertEquals(174, OldSurveyMatcher.leadingNumber("174_P1"))
    }
}
