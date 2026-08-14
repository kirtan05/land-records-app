package com.landrecords.app.data.sync

import com.landrecords.app.data.identity.Identity
import kotlin.math.max

/**
 * Merge semantics — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §3.
 *
 * Mirrored byte-for-byte on the desktop in `src/merge.mjs`, and both are held to the `merge`
 * section of `tools/identity/vectors.json`.
 *
 * The property the whole design rests on: exporting either database and importing it into
 * the other is a NO-OP when the content is the same, and a clean union when it is not.
 *
 * Pure Kotlin and side-effect free: it decides, the caller does the SQL. That is what lets
 * the same logic serve Room, the migration, and the JVM tests.
 */
object MergeEngine {

    /** What merging one incoming row did. [NOOP] is the one that proves idempotence. */
    enum class Action { INSERT, UPDATE, NOOP, KEEP_LOCAL, REJECTED }

    /** A synced row as a plain column map — deliberately not typed per table. */
    typealias Row = Map<String, Any?>

    /** content_hash of a row, using the table's fixed column order. */
    fun hashRow(table: String, row: Row): String =
        Identity.contentHash(SyncSchema.syncedCols(table).map { row[it] })

    /**
     * Decide what to do with one incoming row.
     *
     * ```
     * local == null                   -> INSERT
     * hashes equal                    -> NOOP        // idempotence, immune to clock skew
     * incoming.updated_at > local's    -> UPDATE
     * incoming.updated_at < local's    -> KEEP_LOCAL
     * equal timestamps                -> greater content_hash wins   // deterministic tiebreak
     * ```
     *
     * Last-write-wins with a content-hash short-circuit. These are scraped facts with a
     * monotone information gradient — a later scrape sees the same or newer state — so
     * per-field CRDT merging buys nothing. The short-circuit is what makes re-merging the
     * same export a pure no-op, and stops clock skew from flip-flopping identical content.
     */
    fun decide(table: String, local: Row?, incoming: Row): Action {
        if (local == null) return Action.INSERT

        val lh = local["content_hash"] as? String ?: hashRow(table, local)
        val rh = incoming["content_hash"] as? String ?: hashRow(table, incoming)
        if (lh == rh) return Action.NOOP

        // survey_link is user knowledge. Auto-matching may propose, never overrule (§2 rule 3).
        if (table == "survey_link") {
            val incomingIsAuto = incoming["state"] == "candidate"
            val localState = local["state"] as? String
            val localIsCurated = !localState.isNullOrEmpty() && localState != "candidate"
            if (incomingIsAuto && localIsCurated) return Action.REJECTED
        }

        val lt = asLong(local["updated_at"])
        val rt = asLong(incoming["updated_at"])
        return when {
            rt > lt -> Action.UPDATE
            rt < lt -> Action.KEEP_LOCAL
            rh > lh -> Action.UPDATE
            else -> Action.KEEP_LOCAL
        }
    }

    private fun asLong(v: Any?): Long = when (v) {
        null -> 0L
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        else -> v.toString().toLongOrNull() ?: 0L
    }

    /** The outcome of merging one batch: what happened, and the rows the caller must write. */
    data class MergeResult(
        val counts: Map<Action, Int>,
        val writes: List<Row>,
    ) {
        fun count(a: Action): Int = counts[a] ?: 0
        /** True when the batch changed nothing at all — the idempotence assertion. */
        val isNoop: Boolean get() = writes.isEmpty()
    }

    /**
     * Merge a batch of incoming rows for one table against a lookup of local rows by uid.
     *
     * [fromScraper] marks the batch as coming from a fetch rather than an import of another
     * database. Scrapers may not write user-authored tables at all (§3), except for proposing
     * `survey_link` candidates, which [decide] handles.
     */
    fun mergeTable(
        table: String,
        incomingRows: List<Row>,
        localByUid: Map<String, Row>,
        fromScraper: Boolean = false,
    ): MergeResult {
        val cols = SyncSchema.syncedCols(table)
        val counts = HashMap<Action, Int>()
        val writes = ArrayList<Row>()

        for (raw in incomingRows) {
            val uid = raw["uid"] as? String
            require(!uid.isNullOrEmpty()) { "$table: incoming row has no uid" }

            if (fromScraper && table in SyncSchema.USER_AUTHORED && table != "survey_link") {
                counts.merge(Action.REJECTED, 1, Int::plus)
                continue
            }

            // Always recompute rather than trusting the sender: a row whose declared hash
            // disagreed with its own columns would merge as "changed" forever, or never.
            val incoming = HashMap(raw)
            incoming["content_hash"] = Identity.contentHash(cols.map { raw[it] })

            val action = decide(table, localByUid[uid], incoming)
            counts.merge(action, 1, Int::plus)
            if (action == Action.INSERT || action == Action.UPDATE) writes.add(incoming)
        }
        return MergeResult(counts, writes)
    }

    /**
     * The timestamp a writer must stamp on a row it changes: `max(now, local_max + 1)`.
     *
     * So a device whose clock is behind cannot have *all* of its writes silently lose to the
     * other machine's. Without the `+1`, two writes inside the same millisecond would tie and
     * fall through to the hash tiebreak, which is deterministic but arbitrary.
     */
    fun nextUpdatedAt(now: Long, localMax: Long): Long = max(now, localMax + 1)

    /**
     * Tombstone, never physical delete (§3). A physical delete resurrects on the next merge,
     * because the other database still holds the row and nothing records that it went away.
     * Parents tombstone children explicitly — there is no SQL cascade — and blobs are never
     * deleted by sync at all; local reachability GC handles those.
     */
    fun tombstone(table: String, row: Row, now: Long, localMax: Long): Row {
        val out = HashMap(row)
        out["deleted"] = 1
        out["updated_at"] = nextUpdatedAt(now, localMax)
        out["content_hash"] = hashRow(table, out)
        return out
    }
}
