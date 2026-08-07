package com.landrecords.app.web

/**
 * The iRCMS fetch contract (ircms.gujarat.gov.in) — a separate AJAX site from AnyRoR, ported
 * from the desktop probes (run.mjs, list-cases.mjs, open-case.mjs, reuse-test.mjs).
 *
 * Flow (all on ONE page, no page reloads):
 *  1. Cascade `#sel_district → #sel_taluka → #sel_village → #sel_survey_no` (AJAX-populated).
 *  2. Human solves the CAPTCHA (`#txt_captcha`) and taps View (`#btnViewSurveyList`). The
 *     button's own JS validates the CAPTCHA then AJAX-fills `#surveylist_table` in place.
 *  3. Each case row has `.view_status[data-id]`; the case detail is a POST to `viewnewcasestatus`
 *     with `{_token, casekey=data-id}`. We capture each via WebView.postUrl (shared cookies),
 *     print each to PDF, and merge.
 *
 * CAPTCHA reuse: the same solved code stays valid across searches → ONE solve covers every
 * survey (unlike AnyRoR's one-per-survey). (See reuse-test.mjs.)
 */
object Ircms {
    const val URL = "https://ircms.gujarat.gov.in/ViewSurveyList"
    const val CASE_DETAIL_URL = "https://ircms.gujarat.gov.in/viewnewcasestatus"

    object Ids {
        const val DISTRICT = "sel_district"
        const val TALUKA = "sel_taluka"
        const val VILLAGE = "sel_village"
        const val SURVEY = "sel_survey_no"
        const val CAPTCHA = "txt_captcha"
        const val VIEW_BUTTON = "btnViewSurveyList"
        const val TABLE = "surveylist_table"
    }
}
