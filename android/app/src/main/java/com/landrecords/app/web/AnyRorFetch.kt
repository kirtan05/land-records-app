package com.landrecords.app.web

import com.landrecords.app.data.model.RecordType

/**
 * The AnyRoR fetch contract — the on-device port of the proven desktop automation in
 * `anyror/run-anyror.mjs`, `run-vf712.mjs`, `render-anyror-offline.mjs`, and `format.mjs`.
 *
 * Design-agnostic: this drives an embedded WebView; the UI that hosts it comes from the approved
 * design. The CAPTCHA is auto-solved by the CNN in `tools/captcha/anyror_cnn_real.onnx` (read the
 * data-URI PNG out of img#ContentPlaceHolder1_i_captcha_1 — never re-fetch it, that rotates it);
 * the human spotlight is the fallback after 2 rejections. Superseded 2026-08-14: this contract
 * previously said the CAPTCHA was never auto-solved, which was true only because no model existed.
 *
 * Key facts carried over from the desktop work (see docs/PROJECT_NOTES.md and the memory files):
 *  - AnyRoR is an ASP.NET WebForms page; the cascade is server round-tripped dropdowns.
 *  - Running inside a real WebView on the phone's mobile-data IP avoids the WAF block that killed
 *    the headless desktop scripts.
 *  - Integrated (type 8): after the detail page loads, inject the format.mjs cleanup CSS, then
 *    print-to-PDF. Save raw HTML for offline re-render.
 *  - VF-7/12 (type 11): JS-fetch each PDFView1.aspx?detail=… via the WebView's cookies, weed any
 *    PDF that has a text layer (placeholder), combine newest→oldest.
 *  - Deeds: fetch the Sub-registrar "View Deed" TIFF, decode → PDF.
 */
object AnyRor {
    const val URL = "https://anyror.gujarat.gov.in/LandRecordRural.aspx"
    /**
     * DEEDS-only desktop entry. The /1000 variant serves the FULL desktop detail page
     * (InfoSurveyNoDetail.aspx) whose Sub-registrar deed grid (ContentPlaceHolder1_gvgarviProDet)
     * the plain LandRecordRural.aspx (mobile) flow omits. Same cascade element ids, so
     * AnyRorInjection.prefillStepJs is unchanged (cf. anyror/cascade-explore.mjs, anyror/deed-step1.mjs).
     */
    const val URL_DESKTOP = "https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000"

    /** Cascade element ids (ASP.NET ContentPlaceHolder1_…). */
    object Ids {
        const val RECORD_TYPE = "ContentPlaceHolder1_drpLandRecord"
        const val DISTRICT = "ContentPlaceHolder1_ddlDistrict"
        const val TALUKA = "ContentPlaceHolder1_ddlTaluka"
        const val VILLAGE = "ContentPlaceHolder1_ddlVillage"
        const val SURVEY_INTEGRATED = "ContentPlaceHolder1_ddlSurveyNo"
        const val SURVEY_VF712 = "ContentPlaceHolder1_ddlOldScannedSno"
        const val GET_DETAIL_BUTTON = "ContentPlaceHolder1_btnGo"
    }

    /** AnyRoR "record type" dropdown values. */
    fun recordValue(type: RecordType): String? = type.anyrorRecordValue
}

/** Where a fetch is in its lifecycle — surfaced to whatever UI the design provides. */
enum class FetchStage { LOADING_PAGE, CASCADE_PREFILLED, AWAITING_CAPTCHA, CAPTURING, SAVING, DONE, ERROR }

/** A single fetch request assembled from a saved survey + chosen record type. */
data class FetchRequest(
    val recordType: RecordType,
    val district: String,
    val taluka: String,
    val village: String,
    val surveyNo: String,
)
