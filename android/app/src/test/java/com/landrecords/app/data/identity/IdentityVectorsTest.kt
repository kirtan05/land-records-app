package com.landrecords.app.data.identity

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App half of the cross-language identity contract.
 *
 *     cd android && ./gradlew :app:testDebugUnitTest
 *
 * The desktop half is `tools/identity/test.mjs` and reads the SAME
 * `tools/identity/vectors.json`. Neither side keeps its own copy of the expectations —
 * that is the whole point: if the two implementations drift, one of these tests goes red
 * rather than the databases silently failing to merge months later.
 */
class IdentityVectorsTest {

    private val vectors: JSONObject by lazy { JSONObject(vectorsFile().readText()) }

    /**
     * Walk up from the Gradle module dir (the unit-test working directory) to the repo root.
     * Anchoring on the file itself rather than on a fixed `../../` keeps this working if the
     * module is ever nested differently.
     */
    private fun vectorsFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/identity/vectors.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError(
            "tools/identity/vectors.json not found above ${System.getProperty("user.dir")}. " +
                "It is the shared fixture and must not be copied into the app module.",
        )
    }

    private fun group(name: String): JSONArray = vectors.getJSONArray(name)

    private inline fun each(name: String, body: (JSONObject) -> Unit) {
        val arr = group(name)
        assertTrue("$name has no vectors", arr.length() > 0)
        for (i in 0 until arr.length()) body(arr.getJSONObject(i))
    }

    /** JSONArray → List<Any?>, mapping JSONObject.NULL back to a real Kotlin null. */
    private fun cells(arr: JSONArray): List<Any?> =
        (0 until arr.length()).map { i ->
            val v = arr.get(i)
            if (v === JSONObject.NULL) null else v
        }

    @Test
    fun surveyToken_matchesVectors() = each("surveyToken") { c ->
        assertEquals(
            "surveyToken(${c.getString("in")}) — ${c.getString("why")}",
            c.getString("out"),
            Identity.surveyToken(c.getString("in")),
        )
    }

    /**
     * token(token(x)) == token(x). If the tokenizer were not idempotent, re-running the
     * migration over an already-migrated database would change every key — and the spec
     * requires the migration to be re-runnable.
     */
    @Test
    fun surveyToken_isIdempotent() = each("surveyToken") { c ->
        val once = Identity.surveyToken(c.getString("in"))
        assertEquals("token is not idempotent on ${c.getString("in")}", once, Identity.surveyToken(once))
    }

    @Test
    fun placeId_matchesVectors() = each("placeId") { c ->
        assertEquals(
            c.getString("why"),
            c.getString("out"),
            Identity.placeId(c.getString("district"), c.getString("taluka"), c.getString("village")),
        )
    }

    @Test
    fun provisionalPlaceId_matchesVectors() = each("provisionalPlaceId") { c ->
        assertEquals(
            c.getString("why"),
            c.getString("out"),
            Identity.provisionalPlaceId(c.getString("district"), c.getString("taluka"), c.getString("village")),
        )
    }

    @Test
    fun surveyUid_matchesVectors() = each("surveyUid") { c ->
        assertEquals(
            c.getString("why"),
            c.getString("out"),
            Identity.surveyUid(c.getString("placeId"), c.getString("survey")),
        )
    }

    @Test
    fun uid_matchesVectors() = each("uid") { c ->
        val parts = cells(c.getJSONArray("parts")).toTypedArray()
        assertEquals(
            "${c.getString("kind")} — ${c.getString("why")}",
            c.getString("out"),
            Identity.uid(c.getString("kind"), *parts),
        )
    }

    @Test
    fun canonicalCell_matchesVectors() = each("canonicalCell") { c ->
        val input = c.get("in").let { if (it === JSONObject.NULL) null else it }
        assertEquals(c.getString("why"), c.getString("out"), Identity.canonicalCell(input))
    }

    @Test
    fun contentHash_matchesVectors() = each("contentHash") { c ->
        assertEquals(
            c.getString("why"),
            c.getString("out"),
            Identity.contentHash(cells(c.getJSONArray("cols"))),
        )
    }

    /**
     * The named uid helpers must agree with the generic [Identity.uid] for the same kind and
     * parts. Without this, a helper could quietly reorder its arguments and only the *other*
     * machine's rows would be affected.
     */
    @Test
    fun namedHelpers_agreeWithGenericUid() {
        val su = "gj:15:03:029/221_P"
        val place = "gj:15:03:029"
        assertEquals(Identity.uid("rs", su, "INTEGRATED"), Identity.recordSetUid(su, "INTEGRATED"))
        val case = Identity.caseUid(su, "1234567")
        assertEquals(Identity.uid("ic", su, "ircms", "1234567"), case)
        assertEquals(Identity.uid("io", case, 0), Identity.orderUid(case, 0))
        assertEquals(
            Identity.uid("vs", su, "vf712", "1951-1960", "1", "", ""),
            Identity.vfScanUid(su, "1951-1960", "1", "", ""),
        )
        assertEquals(Identity.uid("en", su, "entry", "4521"), Identity.entryUid(su, "4521"))
        val deed = Identity.deedUid(place, "Anand", "2022", "438")
        assertEquals(Identity.uid("dd", place, "deed", "Anand", "2022", "438"), deed)
        assertEquals(Identity.uid("dp", deed, "seller", "X", 0), Identity.deedPartyUid(deed, "seller", "X", 0))
        assertEquals(Identity.uid("dl", deed, su), Identity.deedLinkUid(deed, su))
        assertEquals(Identity.uid("sl", su, "221_1"), Identity.surveyLinkUid(su, "221_1"))
    }

    /** The kind is inside the hash, not merely a cosmetic prefix. */
    @Test
    fun uidKindIsHashed() {
        assertNotEquals(
            Identity.uid("rs", "x", "y").removePrefix("rs_"),
            Identity.uid("ic", "x", "y").removePrefix("ic_"),
        )
    }

    /** The unit separator must actually separate: ("a","bc") and ("ab","c") are different rows. */
    @Test
    fun separatorIsLoadBearing() {
        assertNotEquals(Identity.uid("rs", "a", "bc"), Identity.uid("rs", "ab", "c"))
        assertNotEquals(Identity.contentHash(listOf("a", "bc")), Identity.contentHash(listOf("ab", "c")))
    }

    /** NULL and "" must hash differently, or clearing a column would look like no change at all. */
    @Test
    fun nullIsNotEmptyString() {
        assertNotEquals(Identity.contentHash(listOf(null)), Identity.contentHash(listOf("")))
    }

    @Test
    fun provisionalPlacesAreDistinguishable() {
        assertTrue(Identity.isProvisionalPlace(Identity.provisionalPlaceId("a", "b", "c")))
        assertTrue(!Identity.isProvisionalPlace(Identity.placeId("15", "03", "029")))
    }

    /** Floats have no agreed text form across languages, so they are refused outright. */
    @Test(expected = IllegalArgumentException::class)
    fun floatsAreRejectedInContentHash() {
        Identity.contentHash(listOf(1.5))
    }
}
