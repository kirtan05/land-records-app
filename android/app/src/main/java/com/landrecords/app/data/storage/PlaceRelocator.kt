package com.landrecords.app.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.landrecords.app.data.place.PlaceNames
import java.io.File

/**
 * Moves a whole place (District/Taluka/Village) from one spelling to another, in BOTH trees the
 * app owns:
 *
 *   app-internal : filesDir/source/<District>/<Taluka>/<Village>/…      (raw HTML + scans)
 *   visible      : Documents/LandRecords/<District>/<Taluka>/<Village>/… (the rendered PDFs)
 *
 * Used by the cross-script place migration, where the same village exists twice — once as
 * "Anand/Umreth/Bharoda" and once as "આણંદ/ઉમરેઠ/ભરોડા".
 *
 * The move is deliberately split into two phases:
 *
 *   [copyInto]   duplicates every file that is missing at the destination. It NEVER overwrites an
 *                existing destination file and NEVER deletes anything, so a failure half-way
 *                leaves both trees complete and the operation is safe to re-run.
 *   [dropSource] removes the source files (and then the emptied directories) — called only once
 *                the copy AND the database rewrite have both succeeded.
 *
 * That ordering is what makes an aborted group harmless: the worst case is a duplicated file, not
 * a lost record.
 */
object PlaceRelocator {

    /** Root of the app-internal raw-source tree written by [LibraryWriter]. */
    private fun internalPlaceDir(context: Context, d: String, t: String, v: String): File =
        File(context.filesDir, "source/$d/$t/$v")

    /** "Documents/LandRecords/<D>/<T>/<V>" — the MediaStore RELATIVE_PATH prefix of the visible tree. */
    private fun visibleRelPath(d: String, t: String, v: String): String =
        "${Environment.DIRECTORY_DOCUMENTS}/${LandRecordsStorage.ROOT_DIR_NAME}/$d/$t/$v"

    private val useMediaStore: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** How many files a copy pass actually duplicated, per tree — purely for the audit log. */
    data class Copied(val internal: Int, val visible: Int)

    // ---------------------------------------------------------------- copy phase

    /**
     * Copy every file under the [from] place that is missing under [to], in both trees. Existing
     * destination files are left untouched. Throws if any copy fails — the caller aborts the group.
     */
    fun copyInto(
        context: Context,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ): Copied {
        val internal = copyInternal(context, from, to)
        val visible = if (useMediaStore) copyVisibleViaMediaStore(context, from, to)
        else copyVisibleAsFiles(from, to)
        return Copied(internal, visible)
    }

    private fun copyInternal(
        context: Context,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ): Int {
        val src = internalPlaceDir(context, from.first, from.second, from.third)
        val dst = internalPlaceDir(context, to.first, to.second, to.third)
        if (!src.exists() || src.canonicalPath == dst.canonicalPath) return 0
        var n = 0
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).path
            val out = File(dst, rel)
            if (out.exists()) {
                android.util.Log.i("LR", "PlaceRelocator: skip (exists) $rel")
                return@forEach
            }
            out.parentFile?.mkdirs()
            f.copyTo(out, overwrite = false)
            n++
            android.util.Log.i("LR", "PlaceRelocator: copied internal $rel -> ${out.absolutePath}")
        }
        return n
    }

    /** Pre-Q devices keep the visible tree as plain files under Documents/LandRecords. */
    private fun copyVisibleAsFiles(
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ): Int {
        val root = LandRecordsStorage.libraryRoot()
        val src = File(root, "${from.first}/${from.second}/${from.third}")
        val dst = File(root, "${to.first}/${to.second}/${to.third}")
        if (!src.exists() || src.canonicalPath == dst.canonicalPath) return 0
        var n = 0
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val out = File(dst, f.relativeTo(src).path)
            if (out.exists()) return@forEach
            out.parentFile?.mkdirs()
            f.copyTo(out, overwrite = false)
            n++
            android.util.Log.i("LR", "PlaceRelocator: copied visible ${f.absolutePath} -> ${out.absolutePath}")
        }
        return n
    }

    /** One MediaStore row of the visible tree: its id, folder (RELATIVE_PATH) and file name. */
    private data class Doc(val id: Long, val relDir: String, val name: String)

    private fun visibleDocs(context: Context, place: Triple<String, String, String>): List<Doc> {
        val prefix = visibleRelPath(place.first, place.second, place.third).trim('/')
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val out = mutableListOf<Doc>()
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$prefix/%"),
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val rel = (c.getString(relCol) ?: "").trim('/')
                val name = c.getString(nameCol) ?: continue
                // LIKE is only ASCII-case-insensitive; re-check the prefix ourselves.
                if (!PlaceNames.normalize(rel).startsWith(PlaceNames.normalize(prefix) + "/")) continue
                out.add(Doc(c.getLong(idCol), rel, name))
            }
        }
        return out
    }

    private fun visibleExists(context: Context, relDir: String, name: String): Boolean {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val want = PlaceNames.normalize(relDir.trim('/'))
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
            null,
        )?.use { c ->
            val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                if (PlaceNames.normalize((c.getString(relCol) ?: "").trim('/')) == want) return true
            }
        }
        return false
    }

    private fun copyVisibleViaMediaStore(
        context: Context,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ): Int {
        val fromPrefix = visibleRelPath(from.first, from.second, from.third).trim('/')
        val toPrefix = visibleRelPath(to.first, to.second, to.third).trim('/')
        if (PlaceNames.normalize(fromPrefix) == PlaceNames.normalize(toPrefix)) return 0
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var n = 0
        for (doc in visibleDocs(context, from)) {
            val tail = doc.relDir.substring(fromPrefix.length.coerceAtMost(doc.relDir.length)).trim('/')
            val destDir = if (tail.isEmpty()) toPrefix else "$toPrefix/$tail"
            if (visibleExists(context, destDir, doc.name)) {
                android.util.Log.i("LR", "PlaceRelocator: skip (exists) $destDir/${doc.name}")
                continue
            }
            val srcUri = ContentUris.withAppendedId(collection, doc.id)
            val bytes = resolver.openInputStream(srcUri)?.use { it.readBytes() }
                ?: error("cannot read ${doc.relDir}/${doc.name}")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, doc.name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$destDir/")
            }
            val uri = resolver.insert(collection, values) ?: error("cannot create $destDir/${doc.name}")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("cannot write $destDir/${doc.name}")
            n++
            android.util.Log.i("LR", "PlaceRelocator: copied visible ${doc.relDir}/${doc.name} -> $destDir/${doc.name}")
        }
        return n
    }

    // ---------------------------------------------------------------- drop phase

    /**
     * Delete the source place's files, then its emptied directories. Called ONLY after [copyInto]
     * and the database rewrite have both succeeded. A file is dropped only when a same-named file
     * really exists at the destination, so nothing can be deleted without a surviving copy.
     */
    fun dropSource(
        context: Context,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ) {
        runCatching { dropInternal(context, from, to) }
            .onFailure { android.util.Log.w("LR", "PlaceRelocator: dropInternal failed: ${it.message}") }
        runCatching {
            if (useMediaStore) dropVisibleViaMediaStore(context, from, to) else dropVisibleAsFiles(from, to)
        }.onFailure { android.util.Log.w("LR", "PlaceRelocator: dropVisible failed: ${it.message}") }
    }

    private fun dropInternal(
        context: Context,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ) {
        val src = internalPlaceDir(context, from.first, from.second, from.third)
        val dst = internalPlaceDir(context, to.first, to.second, to.third)
        if (!src.exists() || src.canonicalPath == dst.canonicalPath) return
        src.walkTopDown().filter { it.isFile }.toList().forEach { f ->
            val rel = f.relativeTo(src).path
            if (File(dst, rel).exists() && f.delete()) {
                android.util.Log.i("LR", "PlaceRelocator: dropped internal $rel")
            }
        }
        pruneEmptyDirs(src)
        // The now-childless <District>/<Taluka> shells too, but never the "source" root itself.
        pruneEmptyDirs(src.parentFile ?: return, stopAt = File(context.filesDir, "source"))
    }

    private fun dropVisibleAsFiles(
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ) {
        val root = LandRecordsStorage.libraryRoot()
        val src = File(root, "${from.first}/${from.second}/${from.third}")
        val dst = File(root, "${to.first}/${to.second}/${to.third}")
        if (!src.exists() || src.canonicalPath == dst.canonicalPath) return
        src.walkTopDown().filter { it.isFile }.toList().forEach { f ->
            val rel = f.relativeTo(src).path
            if (File(dst, rel).exists() && f.delete()) {
                android.util.Log.i("LR", "PlaceRelocator: dropped visible $rel")
            }
        }
        pruneEmptyDirs(src)
        pruneEmptyDirs(src.parentFile ?: return, stopAt = root)
    }

    private fun dropVisibleViaMediaStore(
        context: Context,
        from: Triple<String, String, String>,
        to: Triple<String, String, String>,
    ) {
        val fromPrefix = visibleRelPath(from.first, from.second, from.third).trim('/')
        val toPrefix = visibleRelPath(to.first, to.second, to.third).trim('/')
        if (PlaceNames.normalize(fromPrefix) == PlaceNames.normalize(toPrefix)) return
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (doc in visibleDocs(context, from)) {
            val tail = doc.relDir.substring(fromPrefix.length.coerceAtMost(doc.relDir.length)).trim('/')
            val destDir = if (tail.isEmpty()) toPrefix else "$toPrefix/$tail"
            if (!visibleExists(context, destDir, doc.name)) {
                android.util.Log.w("LR", "PlaceRelocator: NOT dropping ${doc.relDir}/${doc.name} — no copy at $destDir")
                continue
            }
            val deleted = context.contentResolver
                .delete(ContentUris.withAppendedId(collection, doc.id), null, null)
            if (deleted > 0) android.util.Log.i("LR", "PlaceRelocator: dropped visible ${doc.relDir}/${doc.name}")
        }
    }

    /** Remove [dir] and its empty descendants/ancestors, stopping before [stopAt]. Never recursive-deletes files. */
    private fun pruneEmptyDirs(dir: File, stopAt: File? = null) {
        if (!dir.isDirectory) return
        dir.walkBottomUp().filter { it.isDirectory }.forEach { d ->
            if (d.listFiles()?.isEmpty() == true && d.delete()) {
                android.util.Log.i("LR", "PlaceRelocator: removed empty dir ${d.absolutePath}")
            }
        }
        if (stopAt == null) return
        var cur: File? = dir
        while (cur != null && cur.canonicalPath != stopAt.canonicalPath) {
            if (cur.isDirectory && cur.listFiles()?.isEmpty() == true && cur.delete()) {
                android.util.Log.i("LR", "PlaceRelocator: removed empty dir ${cur.absolutePath}")
            } else break
            cur = cur.parentFile
        }
    }
}
