package com.landrecords.app.data.sync

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteDatabase
import com.landrecords.app.data.identity.Identity
import org.json.JSONObject

/**
 * The synced tables — §1–§4. App half; the desktop's is `src/sync-db.mjs`.
 *
 * Deliberately schema-driven rather than a Room entity + DAO per table: every statement is
 * generated from [SyncSchema], so adding a table cannot leave a DAO, an export path or a
 * content hash behind. It also keeps the storage shape identical to the desktop's, which is
 * the point of the exercise.
 *
 * These tables live alongside the existing Room entities in the same database file, created
 * by a Room migration (see AppDatabase). Room never maps them; they are reached through the
 * SupportSQLite handle.
 */
object SyncDb {

    /**
     * Columns stored as INTEGER; everything else is TEXT. `updated_at` MUST be an integer —
     * the merge compares it numerically, and SQLite would sort "1000" before "999" as text.
     */
    private val INT_COLS = setOf("updated_at", "deleted", "doc_count", "ordinal", "size")

    private fun colType(c: String) = if (c in INT_COLS) "INTEGER" else "TEXT"

    /** Every column of a table in physical order: uid, then meta, then content. */
    fun allCols(table: String): List<String> =
        listOf("uid", "content_hash", "updated_at", "origin") + SyncSchema.syncedCols(table)

    /** Idempotent — safe to call on every open and from a migration. */
    fun createTables(db: SupportSQLiteDatabase) {
        for (t in SyncSchema.SYNC_TABLES) {
            val defs = allCols(t).joinToString(", ") { c ->
                if (c == "uid") "uid TEXT PRIMARY KEY" else "$c ${colType(c)}"
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS $t ($defs)")
            // Every table is swept by updated_at during an incremental export.
            db.execSQL("CREATE INDEX IF NOT EXISTS ${t}_updated ON $t(updated_at)")
        }
    }

    // ---- reads -----------------------------------------------------------

    private fun readRows(db: SupportSQLiteDatabase, sql: String, args: Array<Any?> = emptyArray()): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        db.query(sql, args).use { c ->
            while (c.moveToNext()) {
                val row = HashMap<String, Any?>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when {
                        c.isNull(i) -> null
                        c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        else -> c.getString(i)
                    }
                }
                out.add(row)
            }
        }
        return out
    }

    fun rowsByUid(db: SupportSQLiteDatabase, table: String, uids: List<String>): Map<String, Map<String, Any?>> {
        if (uids.isEmpty()) return emptyMap()
        val out = HashMap<String, Map<String, Any?>>()
        // Chunked to stay under SQLite's variable limit on a big import.
        for (chunk in uids.chunked(500)) {
            val q = chunk.joinToString(",") { "?" }
            for (r in readRows(db, "SELECT * FROM $table WHERE uid IN ($q)", chunk.toTypedArray())) {
                out[r["uid"] as String] = r
            }
        }
        return out
    }

    fun allRows(db: SupportSQLiteDatabase, table: String, sinceUpdatedAt: Long = 0): List<Map<String, Any?>> =
        readRows(db, "SELECT * FROM $table WHERE updated_at > ? ORDER BY uid", arrayOf<Any?>(sinceUpdatedAt))

    fun maxUpdatedAt(db: SupportSQLiteDatabase): Long {
        var m = 0L
        for (t in SyncSchema.SYNC_TABLES) {
            db.query("SELECT MAX(updated_at) FROM $t").use { c ->
                if (c.moveToFirst() && !c.isNull(0)) m = maxOf(m, c.getLong(0))
            }
        }
        return m
    }

    // ---- writes ----------------------------------------------------------

    fun upsertAll(db: SupportSQLiteDatabase, table: String, rows: List<Map<String, Any?>>): Int {
        if (rows.isEmpty()) return 0
        val cols = allCols(table)
        for (r in rows) {
            val cv = ContentValues()
            for (c in cols) {
                when (val v = r[c]) {
                    null -> cv.putNull(c)
                    is Boolean -> cv.put(c, if (v) 1L else 0L)
                    is Long -> cv.put(c, v)
                    is Int -> cv.put(c, v.toLong())
                    is Number -> cv.put(c, v.toLong())
                    else -> cv.put(c, v.toString())
                }
            }
            db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
        }
        return rows.size
    }

    /**
     * Stamp a row for writing: fill `origin`/`updated_at`/`deleted` if absent and (re)compute
     * the hash. Callers never set `content_hash` themselves — it is derived, and a hand-set
     * one would rot the moment a column changed.
     */
    fun stamp(
        db: SupportSQLiteDatabase,
        table: String,
        row: Map<String, Any?>,
        origin: String,
        now: Long = System.currentTimeMillis(),
    ): Map<String, Any?> {
        val out = HashMap(row)
        out["origin"] = out["origin"] ?: origin
        out["deleted"] = out["deleted"] ?: 0
        out["updated_at"] = out["updated_at"] ?: MergeEngine.nextUpdatedAt(now, maxUpdatedAt(db))
        out["content_hash"] = Identity.contentHash(SyncSchema.syncedCols(table).map { out[it] })
        return out
    }

    /** Merge a batch into one table and write the winners. Returns the decision counts. */
    fun merge(
        db: SupportSQLiteDatabase,
        table: String,
        incoming: List<Map<String, Any?>>,
        fromScraper: Boolean = false,
    ): MergeEngine.MergeResult {
        val local = rowsByUid(db, table, incoming.mapNotNull { it["uid"] as? String })
        val result = MergeEngine.mergeTable(table, incoming, local, fromScraper)
        upsertAll(db, table, result.writes)
        return result
    }

    // ---- export / import -------------------------------------------------

    /**
     * A bundle is newline-delimited JSON: a header line, then one row per line tagged with
     * its table. JSONL rather than a copy of the .db file because the two sides are Room and
     * node-sqlite — different page formats, same rows — and because it streams and diffs.
     */
    fun exportBundle(db: SupportSQLiteDatabase, sinceUpdatedAt: Long = 0): String = buildString {
        append(
            JSONObject()
                .put("v", 1)
                .put("since", sinceUpdatedAt)
                .put("tables", org.json.JSONArray(SyncSchema.SYNC_TABLES))
                .toString(),
        ).append('\n')
        for (t in SyncSchema.SYNC_TABLES) {
            for (r in allRows(db, t, sinceUpdatedAt)) {
                val o = JSONObject()
                for ((k, v) in r) if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
                append(JSONObject().put("t", t).put("r", o).toString()).append('\n')
            }
        }
    }

    /**
     * Import a bundle through the same engine a fetch uses, so an import behaves exactly like
     * a sync — including being a no-op when the content already matches.
     */
    fun importBundle(db: SupportSQLiteDatabase, text: String, fromScraper: Boolean = false): Map<MergeEngine.Action, Int> {
        val byTable = HashMap<String, MutableList<Map<String, Any?>>>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val o = JSONObject(line)
            val t = o.optString("t", "")
            if (t.isEmpty()) continue // the header
            val r = o.getJSONObject("r")
            val row = HashMap<String, Any?>()
            for (k in r.keys()) row[k] = r.get(k).takeUnless { it === JSONObject.NULL }
            byTable.getOrPut(t) { ArrayList() }.add(row)
        }
        val totals = HashMap<MergeEngine.Action, Int>()
        // Schema order, so parents land before children.
        db.beginTransaction()
        try {
            for (t in SyncSchema.SYNC_TABLES) {
                val rows = byTable[t] ?: continue
                for ((k, v) in merge(db, t, rows, fromScraper).counts) totals.merge(k, v, Int::plus)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return totals
    }

    /**
     * A stable fingerprint of the whole database: every row's uid + content_hash in a fixed
     * order. Two databases that merged the same facts must produce the same string, whatever
     * order they saw them in or what their clocks said.
     */
    fun fingerprint(db: SupportSQLiteDatabase): String {
        val parts = ArrayList<String>()
        for (t in SyncSchema.SYNC_TABLES) {
            db.query("SELECT uid, content_hash FROM $t ORDER BY uid").use { c ->
                while (c.moveToNext()) parts.add("$t\t${c.getString(0)}\t${c.getString(1)}")
            }
        }
        return Identity.contentHash(parts)
    }
}
