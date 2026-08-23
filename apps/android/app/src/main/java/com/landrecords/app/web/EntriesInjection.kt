package com.landrecords.app.web

/**
 * JS for capturing the per-entry (નોંધ) scans on the AnyRoR INTEGRATED detail page
 * (InfoSurveyNoDetail.aspx). Reverse-engineered live on 2026-08-10 (Salun Talpad / 125).
 *
 * The detail page carries an "Entry Details" GridView:
 *   <table id="ContentPlaceHolder1_gvEntryResult"> — one <a> per entry, each
 *     href="javascript:__doPostBack('ctl00${'$'}ContentPlaceHolder1${'$'}gvEntryResult','Select$N')"
 *   RED anchors (style="color:Red;") are the OLD handwritten entries that have a scanned image;
 *   BLUE anchors (color:Blue) are plain text entries (already inside the integrated PDF).
 *
 * Firing an entry's Select$N postback (a same-page POST back to InfoSurveyNoDetail.aspx) re-renders
 * the page with that entry's scan:
 *   #ContentPlaceHolder1_lblEntryNo   → "નોંધ નંબર : <number>"
 *   #ContentPlaceHolder1_gvImages     → a GridView with one <img id="…gvImages_ientryimage_K"> per
 *                                       scanned page, src = /WebHandler/Info6oldImage.ashx?dtv=…&eno=…&pagecnt=…
 * The image loads with the live session cookies, so we read img.src and GET the bytes from Kotlin
 * (see [EntriesDownloader]) exactly like the VF-7/12 scans. __doPostBack posts the live __VIEWSTATE,
 * so firing Select$0, Select$1, … sequentially in the SAME frame works even after cleanupJs has
 * restyled the page (it never removes the form, __VIEWSTATE, or __doPostBack).
 */
object EntriesInjection {

    const val GRID_ID = "ContentPlaceHolder1_gvEntryResult"
    const val GRID_TARGET = "ctl00\$ContentPlaceHolder1\$gvEntryResult"
    private const val IMAGES_ID = "ContentPlaceHolder1_gvImages"
    private const val ENTRY_LABEL_ID = "ContentPlaceHolder1_lblEntryNo"

    /**
     * Reads the entry grid as JSON: {rows:[{index, number, red}]}. `index` is the Select$N argument
     * parsed from the anchor href; `red` marks the entries that have a scan to download.
     */
    fun entryListJs(): String = """
    (function(){
      try {
        var grid = document.getElementById('$GRID_ID');
        if (!grid) return JSON.stringify({rows: []});
        var out = [];
        Array.prototype.slice.call(grid.querySelectorAll("a[href*='Select${'$'}']")).forEach(function(a){
          var m = a.getAttribute('href').match(/Select\${'$'}(\d+)/);
          if (!m) return;
          var style = (a.getAttribute('style') || '').replace(/\s/g,'');
          out.push({ index: parseInt(m[1],10), number: (a.textContent||'').trim(), red: /Red/i.test(style) });
        });
        return JSON.stringify({ rows: out });
      } catch(e) { return JSON.stringify({ rows: [] }); }
    })();
    """.trimIndent()

    /**
     * Fires one entry's Select postback in the CURRENT frame. A full-page navigation follows; the
     * caller waits for onPageFinished, then reads [entryImagesJs].
     */
    fun selectEntryJs(index: Int): String = """
    (function(){
      try {
        if (typeof __doPostBack === 'function') { __doPostBack('$GRID_TARGET', 'Select${'$'}' + $index); return 'FIRED'; }
        return 'NOPB';
      } catch(e) { return 'ERR:' + e.message; }
    })();
    """.trimIndent()

    /**
     * Before rendering the integrated PDF: make the RED entry numbers stand out so they survive a
     * black-&-white print — keep them red (for colour prints) AND add bold + underline (so a mono
     * print still distinguishes them from the blue text entries). Returns 'RED:<n>'.
     */
    fun markRedEntriesForPdfJs(): String = """
    (function(){
      try {
        var grid = document.getElementById('$GRID_ID');
        if (!grid) return 'NOGRID';
        var n = 0;
        Array.prototype.slice.call(grid.querySelectorAll('a')).forEach(function(a){
          var st = (a.getAttribute('style') || '').replace(/\s/g,'');
          if (/color:Red/i.test(st)) {
            a.style.setProperty('color', '#C41E1E', 'important');
            a.style.setProperty('font-weight', '900', 'important');
            a.style.setProperty('text-decoration', 'underline', 'important');
            n++;
          }
        });
        return 'RED:' + n;
      } catch(e) { return 'ERR:' + e.message; }
    })();
    """.trimIndent()

    /**
     * After a Select postback: the selected entry's number + every scanned-page image URL.
     * {number:"<num>", imgs:["https://…Info6oldImage.ashx?…", …]}. imgs is empty if none rendered.
     */
    fun entryImagesJs(): String = """
    (function(){
      try {
        var lbl = document.getElementById('$ENTRY_LABEL_ID');
        var num = lbl ? (lbl.innerText||lbl.textContent||'').replace(/[^0-9A-Za-z*\/]/g,'').replace(/^.*?([0-9].*)$/, '$1') : '';
        var gv = document.getElementById('$IMAGES_ID');
        var imgs = [];
        if (gv) Array.prototype.slice.call(gv.querySelectorAll('img')).forEach(function(i){
          if (i.src) imgs.push(i.src);
        });
        return JSON.stringify({ number: num, imgs: imgs });
      } catch(e) { return JSON.stringify({ number: '', imgs: [] }); }
    })();
    """.trimIndent()
}
