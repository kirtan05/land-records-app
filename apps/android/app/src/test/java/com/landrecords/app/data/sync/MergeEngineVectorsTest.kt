package com.landrecords.app.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App half of the §3 merge contract. The desktop half is `tools/identity/test.mjs` and reads
 * the SAME `tools/identity/vectors.json`.
 *
 *     cd android && ./gradlew :app:testDebugUnitTest
 */
class MergeEngineVectorsTest {

    private val vectors: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/identity/vectors.json")
            if (f.isFile) return@lazy JSONObject(f.readText())
            dir = dir.parentFile
        }
        throw AssertionError("tools/identity/vectors.json not found — it is the shared fixture")
    }

    /** JSON row → the plain column map MergeEngine works in, with JSON null → Kotlin null. */
    private fun row(o: JSONObject?): Map<String, Any?>? {
        if (o == null) return null
        return o.keys().asSequence().associateWith { k ->
            o.get(k).takeUnless { it === JSONObject.NULL }
        }
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    // ---- schema ----------------------------------------------------------

    /**
     * The content-hash column order must be IDENTICAL to the desktop's, table by table.
     * Any drift here silently changes every content_hash, which makes every row on the
     * other machine look modified and defeats the whole no-op property.
     */
    @Test
    fun syncedCols_matchDesktop() {
        val spec = vectors.getJSONObject("syncedCols")
        val tables = strings(spec.getJSONArray("tables"))
        assertEquals("table list drifted from src/sync-schema.mjs", tables, SyncSchema.SYNC_TABLES)

        val cols = spec.getJSONObject("cols")
        for (t in tables) {
            assertEquals("column order drifted for '$t'", strings(cols.getJSONArray(t)), SyncSchema.syncedCols(t))
        }
        assertEquals(strings(spec.getJSONArray("meta")), SyncSchema.SYNC_META)
        assertEquals(strings(spec.getJSONArray("userAuthored")).toSet(), SyncSchema.USER_AUTHORED)
        assertEquals(strings(spec.getJSONArray("linkStates")), SyncSchema.LINK_STATES)
    }

    /**
     * `deleted` must be last in every table. A table that omitted it would have tombstones
     * that merge as no-ops — deletions would simply never propagate.
     */
    @Test
    fun everyTableEndsWithDeleted() {
        for (t in SyncSchema.SYNC_TABLES) {
            assertEquals("$t must end with 'deleted'", "deleted", SyncSchema.syncedCols(t).last())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTableThrows() {
        SyncSchema.syncedCols("not_a_table")
    }

    // ---- decisions -------------------------------------------------------

    @Test
    fun merge_matchesVectors() {
        val arr = vectors.getJSONArray("merge")
        assertTrue(arr.length() > 0)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val table = c.getString("table")
            val local = row(c.optJSONObject("local"))
            val incoming = row(c.getJSONObject("incoming"))!!
            val actual = MergeEngine.decide(table, local, incoming)
            val why = c.getString("why")

            if (c.optBoolean("tiebreak")) {
                // No fixed answer — the rule is only that greater content hash wins, and that
                // both machines therefore pick the same side.
                val lh = MergeEngine.hashRow(table, local!!)
                val rh = MergeEngine.hashRow(table, incoming)
                val expected = if (rh > lh) MergeEngine.Action.UPDATE else MergeEngine.Action.KEEP_LOCAL
                assertEquals(why, expected, actual)
            } else {
                assertEquals(why, MergeEngine.Action.valueOf(c.getString("out")), actual)
            }
        }
    }

    @Test
    fun nextUpdatedAt_matchesVectors() {
        val arr = vectors.getJSONArray("nextUpdatedAt")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            assertEquals(
                c.getString("why"),
                c.getLong("out"),
                MergeEngine.nextUpdatedAt(c.getLong("now"), c.getLong("localMax")),
            )
        }
    }

    // ---- the properties the design rests on -------------------------------

    /** Exporting a database and re-importing it must change nothing at all. */
    @Test
    fun reImportingTheSameExportIsANoop() {
        val rows = listOf<Map<String, Any?>>(
            mapOf("uid" to "a", "place_id" to "gj:15:03:029", "token" to "221_P", "updated_at" to 100L, "deleted" to 0),
            mapOf("uid" to "b", "place_id" to "gj:15:03:029", "token" to "222_P", "updated_at" to 100L, "deleted" to 0),
        )
        val first = MergeEngine.mergeTable("survey", rows, emptyMap())
        assertEquals(2, first.writes.size)

        val local = first.writes.associateBy { it["uid"] as String }
        val second = MergeEngine.mergeTable("survey", rows, local)
        assertTrue("re-import must write nothing", second.isNoop)
        assertEquals(2, second.count(MergeEngine.Action.NOOP))

        // Same content but a much newer clock must STILL be a no-op — that is the
        // content-hash short-circuit doing its job against clock skew.
        val newer = rows.map { it + ("updated_at" to 9_000_000_000L) }
        assertTrue(MergeEngine.mergeTable("survey", newer, local).isNoop)
    }

    /** A re-fetch must not be able to destroy a choice the user made (§3). */
    @Test
    fun scrapersCannotWriteUserAuthoredTables() {
        val mark = listOf<Map<String, Any?>>(
            mapOf("uid" to "m1", "target_uid" to "x", "color" to "red", "updated_at" to 1L, "deleted" to 0),
        )
        assertTrue(MergeEngine.mergeTable("mark", mark, emptyMap(), fromScraper = true).isNoop)
        // ...but importing another database's marks is legitimate.
        assertEquals(1, MergeEngine.mergeTable("mark", mark, emptyMap()).writes.size)
    }

    /**
     * A rejection is knowledge (§2 rule 1). If a candidate could overwrite it, auto-matching
     * would re-propose the same wrong old-survey link on every single fetch, forever.
     */
    @Test
    fun aCandidateCanNeverOverwriteACuratedLink() {
        for (curated in listOf("confirmed", "rejected", "manual")) {
            val local = mapOf<String, Any?>(
                "uid" to "sl1", "current_survey_uid" to "gj:15:03:029/174_P1", "old_token" to "174_1",
                "state" to curated, "updated_at" to 100L, "deleted" to 0,
            )
            val candidate = local + mapOf("state" to "candidate", "updated_at" to 9_000_000L)
            assertEquals(
                "a candidate overwrote '$curated'",
                MergeEngine.Action.REJECTED,
                MergeEngine.decide("survey_link", local, candidate),
            )
        }
    }

    /** An incoming row's declared hash is never trusted; only our own stored hash is. */
    @Test
    fun aLyingIncomingHashIsRecomputed() {
        val localRow = mutableMapOf<String, Any?>(
            "uid" to "a", "place_id" to "p", "token" to "221_P", "area" to "OLD",
            "updated_at" to 1L, "deleted" to 0,
        )
        localRow["content_hash"] = MergeEngine.hashRow("survey", localRow)

        val liar = localRow.toMutableMap().apply {
            this["area"] = "NEW"
            this["updated_at"] = 2L
            // and still claims local's hash
        }
        val res = MergeEngine.mergeTable("survey", listOf(liar), mapOf("a" to localRow))
        assertEquals(1, res.writes.size)
        assertEquals(
            "the written row must carry the TRUE hash",
            MergeEngine.hashRow("survey", liar),
            res.writes[0]["content_hash"],
        )
    }

    /** Deleting is a tombstone with a fresh clock, never a physical removal. */
    @Test
    fun tombstoneIsContent() {
        val row = mapOf<String, Any?>(
            "uid" to "a", "place_id" to "p", "token" to "221_P", "updated_at" to 100L, "deleted" to 0,
        )
        val dead = MergeEngine.tombstone("survey", row, now = 50L, localMax = 100L)
        assertEquals(1, dead["deleted"])
        assertEquals("a slow clock must still advance past local max", 101L, dead["updated_at"])
        // `deleted` is inside the content hash, so the tombstone merges like any other change.
        assertEquals(MergeEngine.Action.UPDATE, MergeEngine.decide("survey", row, dead))
    }
}
