package com.landrecords.app.data.sync

import android.content.Context
import com.landrecords.app.data.db.AppDatabase
import com.landrecords.app.data.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors the user's colour marks into the synced `mark` table (DB review B5).
 *
 * A mark is user knowledge a re-fetch cannot recover, so — like `survey_link` — it must ride the
 * sync. Each mark is keyed on the marked row's OWN uid (a record_set / ircms_case / vf_scan /
 * entry uid), so the same mark set on two devices converges to one row rather than duplicating.
 * Room and the JSON manifests stay the display copy; this is the copy that travels.
 *
 * `mark` is USER_AUTHORED, so no scraper write path may touch it (SyncSchema §3).
 */
object MarkSync {

    private fun db(context: Context) =
        AppDatabase.get(context).openHelper.writableDatabase

    /** Set (color != null) or clear (color == null → a tombstone) the mark on [targetUid]. */
    suspend fun set(context: Context, targetUid: String, color: String?) = withContext(Dispatchers.IO) {
        val d = db(context)
        SyncDb.createTables(d)
        val uid = Identity.uid("mark", targetUid)
        if (color != null) {
            val row = mapOf<String, Any?>("uid" to uid, "target_uid" to targetUid, "color" to color)
            SyncDb.merge(d, "mark", listOf(SyncDb.stamp(d, "mark", row, "app")))
        } else {
            // Unmark is a deletion, and deletions are tombstones (§3), never physical.
            val existing = SyncDb.rowsByUid(d, "mark", listOf(uid))[uid]
            if (existing != null) {
                val tomb = MergeEngine.tombstone(
                    "mark", existing, System.currentTimeMillis(), SyncDb.maxUpdatedAt(d),
                )
                SyncDb.upsertAll(d, "mark", listOf(tomb))
            }
        }
    }
}
