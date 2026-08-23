package com.landrecords.app.fetch

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.landrecords.app.data.db.AppDatabase
import com.landrecords.app.data.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The durable fetch queue — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §6.
 *
 * One row per (survey × record_type), `pending → running → done/failed`,
 * `uid = hash("fq", survey_uid, record_type)`. It survives reboot and app death, and resumes.
 *
 * **Not a synced table.** It is deliberately absent from [com.landrecords.app.data.sync.SyncSchema]:
 * a queue is a statement about what *this device* still has to do, not a fact about the land.
 * Syncing it would make one machine's pending work reappear on the other, and a `running` row
 * from a phone that is currently switched off would block the laptop forever.
 *
 * `running` rows are reclaimed on start: if the process died mid-fetch, the row is still marked
 * running and nothing would ever pick it up again. [reclaimStale] is what makes "resumes" true
 * rather than aspirational.
 */
object FetchQueue {

    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"

    /** A row that has been `running` longer than this had its process killed under it. */
    private const val STALE_RUNNING_MS = 10 * 60_000L

    /** Give up after this many attempts and surface it, rather than retrying forever. */
    const val MAX_ATTEMPTS = 4

    data class Item(
        val uid: String,
        val surveyUid: String,
        val recordType: String,
        val state: String,
        val attempts: Int,
        val lastError: String?,
        /** Epoch millis before which this item must not be retried (the pacer's parking spot). */
        val notBefore: Long,
    )

    fun uidFor(surveyUid: String, recordType: String): String = Identity.uid("fq", surveyUid, recordType)

    /** Overall counts by state — the top line of the status screen ("3 running, 12 pending…"). */
    suspend fun counts(context: Context): Map<String, Int> = withContext(Dispatchers.IO) {
        val out = HashMap<String, Int>()
        db(context).query("SELECT state, COUNT(*) FROM fetch_queue GROUP BY state")
            .use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
        out
    }

    /**
     * Every queue row, worst-state-first so failures and running items are at the top where the
     * user looks. `survey_uid` is turned into a readable "village · survey" label by the caller.
     */
    suspend fun all(context: Context): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>()
        db(context).query(
            """
            SELECT uid, survey_uid, record_type, state, attempts, last_error, not_before
            FROM fetch_queue
            ORDER BY CASE state WHEN 'failed' THEN 0 WHEN 'running' THEN 1
                                WHEN 'pending' THEN 2 ELSE 3 END, survey_uid, record_type
            """.trimIndent(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        uid = c.getString(0), surveyUid = c.getString(1), recordType = c.getString(2),
                        state = c.getString(3), attempts = c.getInt(4),
                        lastError = if (c.isNull(5)) null else c.getString(5),
                        notBefore = c.getLong(6),
                    ),
                )
            }
        }
        out
    }

    fun createTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fetch_queue (
              uid TEXT PRIMARY KEY,
              survey_uid TEXT NOT NULL,
              record_type TEXT NOT NULL,
              state TEXT NOT NULL,
              attempts INTEGER NOT NULL DEFAULT 0,
              last_error TEXT,
              not_before INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS fetch_queue_state ON fetch_queue(state, not_before)")
    }

    private suspend fun db(context: Context): SupportSQLiteDatabase = withContext(Dispatchers.IO) {
        AppDatabase.get(context).openHelper.writableDatabase.also { createTable(it) }
    }

    /**
     * Enqueue every record type for a survey. Idempotent: an item already queued, running or
     * done is left alone, so "add a property" can be called twice without duplicating work or
     * resetting progress.
     */
    suspend fun enqueue(context: Context, surveyUid: String, recordTypes: List<String>): Int =
        withContext(Dispatchers.IO) {
            val d = db(context)
            var added = 0
            d.beginTransaction()
            try {
                for (t in recordTypes) {
                    val uid = uidFor(surveyUid, t)
                    d.execSQL(
                        """
                        INSERT INTO fetch_queue (uid, survey_uid, record_type, state, attempts, not_before, updated_at)
                        VALUES (?, ?, ?, '$PENDING', 0, 0, ?)
                        ON CONFLICT(uid) DO NOTHING
                        """.trimIndent(),
                        arrayOf<Any?>(uid, surveyUid, t, System.currentTimeMillis()),
                    )
                    added++
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            added
        }

    /**
     * FORCE every record type for a survey back to `pending`, even ones already `done`.
     *
     * Unlike [enqueue] (which leaves existing rows alone), this re-fetches from scratch — the
     * "re-fetch everything" path, needed to backfill data an earlier build captured incompletely
     * (VF-6 entries and deeds were missed until the headless entry capture landed). Idempotent:
     * running it twice just re-sets the same rows to pending.
     */
    suspend fun requeue(context: Context, surveyUid: String, recordTypes: List<String>): Int =
        withContext(Dispatchers.IO) {
            val d = db(context)
            val now = System.currentTimeMillis()
            d.beginTransaction()
            try {
                for (t in recordTypes) {
                    d.execSQL(
                        """
                        INSERT INTO fetch_queue (uid, survey_uid, record_type, state, attempts, not_before, updated_at)
                        VALUES (?, ?, ?, '$PENDING', 0, 0, ?)
                        ON CONFLICT(uid) DO UPDATE SET state='$PENDING', attempts=0, not_before=0, last_error=NULL, updated_at=?
                        """.trimIndent(),
                        arrayOf<Any?>(uidFor(surveyUid, t), surveyUid, t, now, now),
                    )
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            recordTypes.size
        }

    /**
     * Reclaim rows left `running` by a process that died. Called on service start — without
     * it, an app killed mid-fetch would leave that survey stuck forever with no visible cause.
     */
    suspend fun reclaimStale(context: Context): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - STALE_RUNNING_MS
        val d = db(context)
        var n = 0
        d.query("SELECT COUNT(*) FROM fetch_queue WHERE state = ? AND updated_at < ?", arrayOf<Any?>(RUNNING, cutoff))
            .use { if (it.moveToFirst()) n = it.getInt(0) }
        if (n > 0) {
            d.execSQL(
                "UPDATE fetch_queue SET state = ?, updated_at = ? WHERE state = ? AND updated_at < ?",
                arrayOf<Any?>(PENDING, System.currentTimeMillis(), RUNNING, cutoff),
            )
            android.util.Log.i("LR", "FetchQueue: reclaimed $n stale running item(s)")
        }
        n
    }

    /** The next item to run for [host]-ish work, or null when there is nothing due right now. */
    suspend fun claimNext(context: Context, recordTypes: Collection<String>): Item? =
        withContext(Dispatchers.IO) {
            if (recordTypes.isEmpty()) return@withContext null
            val d = db(context)
            val now = System.currentTimeMillis()
            val placeholders = recordTypes.joinToString(",") { "?" }
            var item: Item? = null
            d.beginTransaction()
            try {
                d.query(
                    """
                    SELECT uid, survey_uid, record_type, state, attempts, last_error, not_before
                    FROM fetch_queue
                    WHERE state = ? AND not_before <= ? AND record_type IN ($placeholders)
                    ORDER BY not_before,
                             CASE record_type WHEN 'INTEGRATED' THEN 0 WHEN 'IRCMS' THEN 1
                                              WHEN 'VF712' THEN 2 ELSE 3 END,
                             uid
                    LIMIT 1
                    """.trimIndent(),
                    (listOf<Any?>(PENDING, now) + recordTypes).toTypedArray(),
                ).use { c ->
                    if (c.moveToFirst()) {
                        item = Item(
                            uid = c.getString(0), surveyUid = c.getString(1), recordType = c.getString(2),
                            state = c.getString(3), attempts = c.getInt(4),
                            lastError = if (c.isNull(5)) null else c.getString(5),
                            notBefore = c.getLong(6),
                        )
                    }
                }
                item?.let {
                    d.execSQL(
                        "UPDATE fetch_queue SET state = ?, updated_at = ? WHERE uid = ?",
                        arrayOf<Any?>(RUNNING, now, it.uid),
                    )
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            item
        }

    /**
     * Atomically claim a SPECIFIC sibling ([recordType]) for [surveyUid] if it is pending and due,
     * flipping it to running. Returns null when there is nothing to claim (already running/done, or
     * not yet due). Lets one AnyRoR session fetch INTEGRATED + VF-7/12 for a survey together.
     */
    suspend fun claimSibling(context: Context, surveyUid: String, recordType: String): Item? =
        withContext(Dispatchers.IO) {
            val d = db(context)
            val now = System.currentTimeMillis()
            var item: Item? = null
            d.beginTransaction()
            try {
                d.query(
                    """
                    SELECT uid, survey_uid, record_type, state, attempts, last_error, not_before
                    FROM fetch_queue
                    WHERE survey_uid = ? AND record_type = ? AND state = ? AND not_before <= ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf<Any?>(surveyUid, recordType, PENDING, now),
                ).use { c ->
                    if (c.moveToFirst()) {
                        item = Item(
                            uid = c.getString(0), surveyUid = c.getString(1), recordType = c.getString(2),
                            state = c.getString(3), attempts = c.getInt(4),
                            lastError = if (c.isNull(5)) null else c.getString(5),
                            notBefore = c.getLong(6),
                        )
                    }
                }
                item?.let {
                    d.execSQL(
                        "UPDATE fetch_queue SET state = ?, updated_at = ? WHERE uid = ?",
                        arrayOf<Any?>(RUNNING, now, it.uid),
                    )
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            item
        }

    suspend fun markDone(context: Context, uid: String) = withContext(Dispatchers.IO) {
        db(context).execSQL(
            "UPDATE fetch_queue SET state = ?, last_error = NULL, updated_at = ? WHERE uid = ?",
            arrayOf<Any?>(DONE, System.currentTimeMillis(), uid),
        )
    }

    /**
     * Record a failure. Below [MAX_ATTEMPTS] the item goes back to `pending` with a
     * `not_before` in the future — that is the queue **parking** rather than hammering.
     * At the limit it becomes `failed` and is surfaced to the user, because silent-on-failure
     * was explicitly rejected: a blocked fetch would otherwise look like a broken app.
     */
    suspend fun markFailed(context: Context, item: Item, error: String, retryAfterMs: Long) =
        withContext(Dispatchers.IO) {
            val attempts = item.attempts + 1
            val giveUp = attempts >= MAX_ATTEMPTS
            db(context).execSQL(
                "UPDATE fetch_queue SET state = ?, attempts = ?, last_error = ?, not_before = ?, updated_at = ? WHERE uid = ?",
                arrayOf<Any?>(
                    if (giveUp) FAILED else PENDING,
                    attempts,
                    error.take(400),
                    if (giveUp) 0L else System.currentTimeMillis() + retryAfterMs,
                    System.currentTimeMillis(),
                    item.uid,
                ),
            )
        }

    /** Counts by state — drives the per-survey status line ("12 of 34", "failed — retry"). */
    suspend fun progressFor(context: Context, surveyUid: String): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, Int>()
            db(context).query(
                "SELECT state, COUNT(*) FROM fetch_queue WHERE survey_uid = ? GROUP BY state",
                arrayOf<Any?>(surveyUid),
            ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
            out
        }

    /**
     * The soonest `not_before` among pending items of [recordTypes], or null when none are
     * pending. Lets the drain loop SLEEP through a cooldown instead of stopping the service —
     * without this, a queue where every remaining item is in its retry backoff drains to
     * "nothing claimable right now", the service stops, and the work strands until the app is
     * reopened.
     */
    suspend fun nextDueAt(context: Context, recordTypes: Collection<String>): Long? =
        withContext(Dispatchers.IO) {
            if (recordTypes.isEmpty()) return@withContext null
            val placeholders = recordTypes.joinToString(",") { "?" }
            var due: Long? = null
            db(context).query(
                "SELECT MIN(not_before) FROM fetch_queue WHERE state = ? AND record_type IN ($placeholders)",
                (listOf<Any?>(PENDING) + recordTypes).toTypedArray(),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) due = c.getLong(0) }
            due
        }

    suspend fun pendingCount(context: Context): Int = withContext(Dispatchers.IO) {
        var n = 0
        db(context).query(
            "SELECT COUNT(*) FROM fetch_queue WHERE state IN (?, ?)",
            arrayOf<Any?>(PENDING, RUNNING),
        ).use { if (it.moveToFirst()) n = it.getInt(0) }
        n
    }

    /** Put every failed item for a survey back in the queue — the "retry" the status line offers. */
    suspend fun retryFailed(context: Context, surveyUid: String?) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (surveyUid == null) {
            db(context).execSQL(
                "UPDATE fetch_queue SET state = ?, attempts = 0, not_before = 0, updated_at = ? WHERE state = ?",
                arrayOf<Any?>(PENDING, now, FAILED),
            )
        } else {
            db(context).execSQL(
                "UPDATE fetch_queue SET state = ?, attempts = 0, not_before = 0, updated_at = ? WHERE state = ? AND survey_uid = ?",
                arrayOf<Any?>(PENDING, now, FAILED, surveyUid),
            )
        }
    }
}
