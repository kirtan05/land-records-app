package com.landrecords.app.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Marked per-item collector for the Marked / batch-export screen. Individually colour-marked iRCMS
 * cases and VF-7/12 scans live in JSON manifests (not Room), so — unlike whole marked records — they
 * have no reactive query. This walks every survey's manifests under
 *   filesDir/source/<District>/<Taluka>/<Village>/<survey>/{ircms/cases.json, vf712/vf712.json}
 * and returns each entry that carries a non-null [CasesStore.CaseEntry.mark] /
 * [VfScansStore.ScanEntry.mark] as an exportable [Extra].
 *
 * The folder names ARE the (already '/'→'_' safe) place + survey keys, so they feed straight back
 * into [CasesStore]/[VfScansStore] — whose path builders re-apply the same safe transform and are
 * therefore idempotent on an already-safe survey name — for the PDF path and later unmark.
 */
object MarkedItems {

    enum class Kind { CASE, SCAN, ENTRY }

    /** One marked case or scan, with everything needed to name, export and later unmark it. */
    data class Extra(
        val kind: Kind,
        val mark: String,        // MarkColor id
        val district: String,
        val taluka: String,
        val village: String,     // English place key (the folder name)
        val surveyNo: String,    // the survey folder name (already '/'→'_' safe)
        val itemId: String,      // case identity / scan index — the setMark key
        val detail: String,      // caseNo (cleaned) or scan period — land data for the label
        val pdfPath: String,     // absolute path to this item's PDF
    )

    /** Every colour-marked case + scan across the whole library, in filesystem order. */
    suspend fun collect(context: Context): List<Extra> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "source")
        if (!root.isDirectory) return@withContext emptyList()
        val out = ArrayList<Extra>()
        root.listFiles()?.forEach { d ->
            if (!d.isDirectory) return@forEach
            d.listFiles()?.forEach { t ->
                if (!t.isDirectory) return@forEach
                t.listFiles()?.forEach { v ->
                    if (!v.isDirectory) return@forEach
                    v.listFiles()?.forEach { s ->
                        if (s.isDirectory) collectSurvey(context, d.name, t.name, v.name, s.name, out)
                    }
                }
            }
        }
        out
    }

    private suspend fun collectSurvey(
        context: Context, district: String, taluka: String, village: String, surveyNo: String,
        out: MutableList<Extra>,
    ) {
        // iRCMS cases (CasesStore.read collapses cross-listed duplicates by identity).
        for (c in CasesStore.read(context, district, taluka, village, surveyNo)) {
            val mark = c.mark ?: continue
            val pdf = CasesStore.filePath(context, district, taluka, village, surveyNo, c.detailFile) ?: continue
            out.add(
                Extra(
                    kind = Kind.CASE, mark = mark,
                    district = district, taluka = taluka, village = village, surveyNo = surveyNo,
                    itemId = CasesStore.identity(c.caseNo, c.parties, c.office, c.dtv),
                    detail = cleanCaseNo(c.caseNo), pdfPath = pdf,
                ),
            )
        }
        // VF-7/12 old-scanned years.
        for (sc in VfScansStore.read(context, district, taluka, village, surveyNo)) {
            val mark = sc.mark ?: continue
            val pdf = VfScansStore.filePath(context, district, taluka, village, surveyNo, sc.file) ?: continue
            out.add(
                Extra(
                    kind = Kind.SCAN, mark = mark,
                    district = district, taluka = taluka, village = village, surveyNo = surveyNo,
                    itemId = sc.index.toString(),
                    detail = sc.period.ifBlank { "—" }, pdfPath = pdf,
                ),
            )
        }
        // Integrated entry (નોંધ) scans.
        for (en in EntriesStore.read(context, district, taluka, village, surveyNo)) {
            val mark = en.mark ?: continue
            val pdf = EntriesStore.filePath(context, district, taluka, village, surveyNo, en.file) ?: continue
            out.add(
                Extra(
                    kind = Kind.ENTRY, mark = mark,
                    district = district, taluka = taluka, village = village, surveyNo = surveyNo,
                    itemId = en.number,
                    detail = en.number, pdfPath = pdf,
                ),
            )
        }
    }

    /** Trim the scraped case string to its clean head (mirrors the Cases screen's row title). */
    private fun cleanCaseNo(caseNo: String): String {
        val head = caseNo.split(Regex("-{2,}")).firstOrNull()?.trim().orEmpty()
        val stripped = head.replace(Regex("\\s*\\((PENDING|DISPOSED)\\).*$", RegexOption.IGNORE_CASE), "").trim()
        return stripped.ifBlank { caseNo.trim().ifBlank { "—" } }
    }
}
