package com.landrecords.app.web

/**
 * JS injected into the AnyRoR OLD-SCANNED VF-7/12 flow — the on-device port of
 * anyror/run-vf712.mjs (parseRows + fetchRowPdf) and capture-vf712.mjs.
 *
 * The cascade + CAPTCHA are IDENTICAL to the Integrated record (record type "11",
 * survey dropdown [AnyRor.Ids.SURVEY_VF712]), so [AnyRorInjection.prefillStepJs] and
 * [AnyRorInjection.dimSpotlightJs] drive them unchanged. Everything below is the part that
 * DIFFERS: the result page is a grid of year-wise scanned entries, and each entry's PDF is
 * addressed by a server-generated token that only appears AFTER you "select" the row.
 *
 * Result page (InfoOld712Detail.aspx) after "Get Record Detail":
 *   <table id="ContentPlaceHolder1_gvResult"> with one row per scanned document; columns are
 *   [ક્રમ, થોક વર્ષ (period e.g. "1993-2004"), થોક નંબર, સરવે/બ્લોક નંબર, જુનો સરવે નંબર,
 *    PDF સ્ટેટસ ("Ok"), <a>View PDF</a>]. Each View PDF link is
 *      javascript:__doPostBack('ctl00${'$'}ContentPlaceHolder1${'$'}gvResult','Select$N').
 *
 * Selecting a row (the postback) re-renders the SAME page WITH the grid still intact PLUS an
 * embed of that row's PDF:
 *   <object class="literal_pdf" type="application/pdf"
 *     data="https://anyror.gujarat.gov.in/oldvf712_docs/PDFView1.aspx?detail=<TOKEN>#toolbar=0">
 * The <TOKEN> is an opaque server-encrypted blob (contains '/', '+', '='); it is NOT in the
 * grid HTML — it is minted per Select postback. We read `object[data]`, strip the '#…'
 * fragment, and fetch those exact bytes from Kotlin with the WebView's session cookies
 * (see [Vf712Downloader]). Because __doPostBack posts the live __VIEWSTATE, firing
 * Select$0, Select$1, … sequentially in the SAME frame works even though the on-screen
 * links are cosmetic — the GridView honours the command index server-side.
 */
object Vf712Injection {

    const val GRID_ID = "ContentPlaceHolder1_gvResult"
    /** The GridView's __doPostBack event target. */
    const val GRID_TARGET = "ctl00\$ContentPlaceHolder1\$gvResult"

    /**
     * True result-readiness for VF-7/12: the gvResult grid has rendered with data rows and we
     * are NOT still on the cascade form (Get Record Detail button gone). Returns 'READY:n'
     * (n data rows), 'NOTFOUND' (server says no scanned records), or 'WAIT'.
     */
    fun resultReadyJs(): String = """
    (function(){
      try {
        var onForm = document.getElementById('${AnyRor.Ids.GET_DETAIL_BUTTON}') != null;
        if (onForm) return 'WAIT';
        var grid = document.getElementById('$GRID_ID');
        if (grid) {
          var dataRows = Array.prototype.slice.call(grid.rows).filter(function(r){ return !r.querySelector('th'); });
          if (dataRows.length > 0) return 'READY:' + dataRows.length;
        }
        var t = document.body ? document.body.innerText : '';
        if (/no record|not found|રેકોર્ડ મળ્યો નથી|માહિતી ઉપલબ્ધ નથી|ઉપલબ્ધ નથી/i.test(t)) return 'NOTFOUND';
        return 'WAIT';
      } catch(e) { return 'WAIT'; }
    })();
    """.trimIndent()

    /**
     * Reads every grid row as JSON: {rows:[{index, period, thok, block, oldSurvey, status}]}.
     * `index` is the Select$N argument parsed from the row's View-PDF href (robust to any
     * header/footer rows), exactly like anyror/run-vf712.mjs parseRows.
     */
    fun readRowsJs(): String = """
    (function(){
      try {
        var grid = document.getElementById('$GRID_ID');
        if (!grid) return JSON.stringify({rows: []});
        var out = [];
        Array.prototype.slice.call(grid.rows).forEach(function(tr){
          var a = tr.querySelector("a[href*='Select${'$'}']");
          if (!a) return;
          var m = a.getAttribute('href').match(/Select\${'$'}(\d+)/);
          if (!m) return;
          var cells = Array.prototype.slice.call(tr.cells).map(function(c){ return (c.innerText||'').replace(/\s+/g,' ').trim(); });
          out.push({ index: parseInt(m[1],10), period: cells[1]||'', thok: cells[2]||'',
                     block: cells[3]||'', oldSurvey: cells[4]||'', status: cells[5]||'' });
        });
        return JSON.stringify({ rows: out });
      } catch(e) { return JSON.stringify({ rows: [] }); }
    })();
    """.trimIndent()

    /**
     * Fires one row's View-PDF postback in the CURRENT frame (no _blank). A full-page
     * navigation follows; the caller waits for onPageFinished, then reads [readPdfUrlJs].
     */
    fun selectRowJs(index: Int): String = """
    (function(){
      try {
        if (typeof __doPostBack === 'function') { __doPostBack('$GRID_TARGET', 'Select${'$'}' + $index); return 'FIRED'; }
        return 'NOPB';
      } catch(e) { return 'ERR:' + e.message; }
    })();
    """.trimIndent()

    /**
     * After a Select postback: the absolute PDFView1.aspx URL from the embedded viewer,
     * with the '#toolbar=0' fragment stripped. '' if this row produced no embed.
     */
    fun readPdfUrlJs(): String = """
    (function(){
      try {
        var el = document.querySelector('object.literal_pdf[data], object[type="application/pdf"][data], embed[type="application/pdf"][src], iframe[src*="PDFView1"]');
        if (!el) return '';
        var u = el.getAttribute('data') || el.getAttribute('src') || '';
        if (u.indexOf('PDFView1') < 0) return '';
        var h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        return u;
      } catch(e) { return ''; }
    })();
    """.trimIndent()
}
