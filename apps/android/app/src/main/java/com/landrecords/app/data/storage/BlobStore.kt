package com.landrecords.app.data.storage

import android.content.Context
import com.landrecords.app.data.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed file store — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §4.
 *
 * Every document is stored once, under `sha256(bytes)`. The same PDF fetched on the phone and
 * on the laptop is one blob with zero conflict — which removes the biggest merge hazard in the
 * system today: the app's `Integrated Record.pdf` and the desktop's
 * `Bharoda_SurveyNo_221_P_ALL.pdf` are the same bytes under two names, and nothing could tell.
 *
 * It also closes a live data-loss risk. Every store (`CasesStore`, `VfScansStore`,
 * `EntriesStore`, `DeedsStore`) currently deletes its manifest and files and rewrites them on
 * re-fetch. Under content addressing a re-fetch tombstones *links*, never bytes, so merging
 * with an older database can no longer lose a scan that only one machine ever had.
 *
 * Layout: `files/blobs/<first two hex chars>/<full sha>`. The fan-out keeps any one directory
 * from growing to tens of thousands of entries, which some filesystems handle badly.
 *
 * Nothing here deletes. Blobs are never removed by sync (§3); only [gc] removes them, and only
 * when the caller can enumerate every hash still referenced.
 */
object BlobStore {

    private fun root(context: Context) = File(context.filesDir, "blobs")

    /** The path a blob lives at, whether or not it exists yet. */
    fun fileFor(context: Context, sha: String): File =
        File(root(context), "${sha.take(2)}/$sha")

    fun exists(context: Context, sha: String): Boolean = fileFor(context, sha).isFile

    /**
     * Store [bytes] and return their sha256. Writing is skipped when the blob is already
     * present — the whole point of content addressing, and what makes a re-fetch nearly free.
     *
     * The write goes to a temp file and is then renamed, so an interrupted write (app killed
     * mid-fetch, which §6 explicitly expects) can never leave a truncated file sitting at the
     * name of a hash it does not actually match.
     */
    suspend fun put(context: Context, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val sha = Identity.blobUid(bytes)
        val target = fileFor(context, sha)
        if (target.isFile && target.length() == bytes.size.toLong()) return@withContext sha

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$sha.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        sha
    }

    /** Ingest a file that already exists on disk, streaming so a 200 MB scan never lands in RAM. */
    suspend fun putFile(context: Context, src: File): String? = withContext(Dispatchers.IO) {
        if (!src.isFile) return@withContext null
        val sha = sha256Of(src) ?: return@withContext null
        val target = fileFor(context, sha)
        if (target.isFile && target.length() == src.length()) return@withContext sha

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$sha.part")
        src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        sha
    }

    fun bytes(context: Context, sha: String): ByteArray? =
        fileFor(context, sha).takeIf { it.isFile }?.readBytes()

    fun size(context: Context, sha: String): Long = fileFor(context, sha).takeIf { it.isFile }?.length() ?: 0L

    /** Streaming sha256 of a file — never reads the whole thing into memory. */
    fun sha256Of(f: File): String? {
        if (!f.isFile) return null
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Project a blob into the visible library tree by hard-linking, falling back to a copy.
     *
     * `Documents/LandRecords/<District>/<Taluka>/<Village>/Survey <N>/…` stays exactly as it
     * is — same folders, same filenames dad already knows (§4). It is a *projection* over this
     * store, regenerable at any time, so nothing user-facing changes and nothing is duplicated
     * on disk when the link succeeds.
     */
    suspend fun project(context: Context, sha: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        val src = fileFor(context, sha)
        if (!src.isFile) return@withContext false
        dest.parentFile?.mkdirs()
        if (dest.isFile && dest.length() == src.length()) return@withContext true
        runCatching {
            if (dest.exists()) dest.delete()
            android.system.Os.link(src.absolutePath, dest.absolutePath)
            return@withContext true
        }
        // Different filesystem (the visible tree is under Documents/, the store under files/):
        // a hard link is impossible, so fall back to a copy.
        runCatching { src.copyTo(dest, overwrite = true) }.isSuccess
    }

    /**
     * Store [bytes], record the `blob` row, and write the visible copy at [visible] — the one
     * call a fetch path needs.
     *
     * This is what turns each store's "write the PDF here" into §4's model: the bytes live once
     * in the content-addressed store, the `blob` row makes them syncable, and the file under
     * `Documents/LandRecords/…` is a projection with the filename dad already knows. Returns
     * the sha so the caller can record the link in its manifest.
     */
    suspend fun ingest(context: Context, bytes: ByteArray, visible: File?, mime: String = "application/pdf"): String {
        val sha = put(context, bytes)
        withContext(Dispatchers.IO) {
            runCatching {
                val db = com.landrecords.app.data.db.AppDatabase.get(context).openHelper.writableDatabase
                com.landrecords.app.data.sync.SyncDb.merge(
                    db,
                    "blob",
                    listOf(
                        com.landrecords.app.data.sync.SyncDb.stamp(
                            db, "blob",
                            mapOf("uid" to sha, "size" to bytes.size.toLong(), "mime" to mime),
                            origin = "app",
                        ),
                    ),
                )
            }.onFailure { android.util.Log.w("LR", "BlobStore: blob row for ${sha.take(8)} failed: ${it.message}") }
        }
        if (visible != null) project(context, sha, visible)
        return sha
    }

    /** Total bytes held. Shown in settings/diagnostics so growth is never a mystery. */
    fun totalBytes(context: Context): Long =
        root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun count(context: Context): Int =
        root(context).walkTopDown().count { it.isFile && !it.name.endsWith(".part") }

    /**
     * Reachability GC. Removes only blobs whose hash appears in no row of the database, and
     * only when [reachable] was built by enumerating EVERY table that can hold a `blob_sha`.
     * Sync never deletes blobs (§3); this is the one path that does, and it is deliberately
     * caller-driven so it cannot run on a partial view of what is referenced.
     *
     * Returns the number of blobs removed.
     */
    suspend fun gc(context: Context, reachable: Set<String>): Int = withContext(Dispatchers.IO) {
        if (reachable.isEmpty()) return@withContext 0 // refuse to empty the store on an empty set
        var removed = 0
        root(context).listFiles()?.forEach { bucket ->
            bucket.listFiles()?.forEach { f ->
                // Sweep abandoned partial writes too; they are never referenced by anything.
                if (f.name.endsWith(".part") || f.name !in reachable) {
                    if (f.delete()) removed++
                }
            }
        }
        removed
    }
}
