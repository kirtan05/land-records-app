package com.landrecords.app.web

/**
 * On-device port of the proven desktop logic (anyror/format.mjs + run-anyror.mjs).
 *
 *  - [CLEANUP_CSS] reproduces the tuned Integrated-record print layout: 9.4pt cells,
 *    darker borders, chrome hidden, watermark removed, full-width single column.
 *  - [cleanupJs] strips empty rows/tables/sections before printing (the DOM cleanup).
 *  - [prefillCascadeJs] selects record type + district/taluka/village/survey by
 *    matching the visible Gujarati text, firing the ASP.NET change/postback each step.
 *  - [dimSpotlightJs] dims the page and highlights the CAPTCHA + Get Record Detail.
 *  - [detailReadyJs] reports when the survey-detail result has actually rendered.
 */
object AnyRorInjection {

    /**
     * Does ONE cascade step per page load (AnyRoR posts back after each dropdown), then
     * reports what it did so the app knows to wait for the reload or move on:
     *   'RT' | 'DIST' | 'TAL' | 'VIL' | 'SUR' selected (a postback will follow),
     *   'READY' when the whole cascade is set (spotlight the CAPTCHA), 'WAIT' if the
     *   expected dropdown isn't present yet. Matching normalizes Gujarati digits (૦-૯→0-9,
     *   પ→p) and compares on visible text — the same rule as anyror/run-anyror.mjs.
     */
    fun prefillStepJs(
        recordValue: String,
        districtGu: String,
        talukaGu: String,
        villageGu: String,
        surveyNorm: String,
        surveyDropId: String,
    ): String = """
    (function(){
      try {
        function norm(s){ return (s||'').replace(/[૦-૯]/g,function(d){return '૦૧૨૩૪૫૬૭૮૯'.indexOf(d);})
          .replace(/પ/g,'p').replace(/[~\s]+/g,'').toLowerCase(); }
        // Survey token: the leading number before any separator, e.g. "851 ~~" -> "851".
        function coreTok(s){ var m = norm(s).match(/^[0-9a-z\/અ-૱]+/); return m ? m[0] : norm(s); }
        function pick(sel, wantText, exact){
          var want=norm(wantText); var best=null;
          Array.from(sel.options).forEach(function(o){ if(best) return; var t=norm(o.text);
            var hit = exact ? (coreTok(o.text)===want) : (t===want || t.indexOf(want)>=0);
            if(t && hit) best=o; });
          if(best){ sel.value=best.value; sel.dispatchEvent(new Event('change',{bubbles:true})); return true; }
          return false;
        }
        function unset(sel){ if(!sel) return true; var v=(sel.value||'').trim(); return v===''||v==='0'||v==='-1'; }
        var rt=document.getElementById('${AnyRor.Ids.RECORD_TYPE}');
        if(rt && (rt.value||'')!=='$recordValue'){ rt.value='$recordValue'; rt.dispatchEvent(new Event('change',{bubbles:true})); return 'RT'; }
        var dist=document.getElementById('${AnyRor.Ids.DISTRICT}');
        if(dist && unset(dist) && '$districtGu'){ return pick(dist,'$districtGu')?'DIST':'WAIT'; }
        var tal=document.getElementById('${AnyRor.Ids.TALUKA}');
        if(tal && unset(tal) && '$talukaGu'){ return pick(tal,'$talukaGu')?'TAL':'WAIT'; }
        var vil=document.getElementById('${AnyRor.Ids.VILLAGE}');
        if(vil && unset(vil) && '$villageGu'){ return pick(vil,'$villageGu')?'VIL':'WAIT'; }
        var sur=document.getElementById('$surveyDropId');
        if(sur && unset(sur) && '$surveyNorm'){ return pick(sur,'$surveyNorm')?'SUR':'WAIT'; }
        return 'READY';
      } catch(e) { return 'WAIT'; }
    })();
    """.trimIndent()

    /** Reproduces anyror/format.mjs addStyleTag — the print CSS for the Integrated record. */
    const val CLEANUP_CSS = """
        html, body { background:#fff !important; }
        * { background-image:none !important; }
        .imgwatermark { background:#fff !important; }
        [class*="col-md-4"],[class*="col-lg-4"],[class*="col-md-5"],[class*="col-lg-5"],
        [class*="col-md-6"],[class*="col-lg-6"],[class*="col-md-7"],[class*="col-lg-7"],
        [class*="col-md-8"],[class*="col-lg-8"],[class*="col-md-9"],[class*="col-lg-9"]{
           width:100% !important; max-width:100% !important; float:none !important; display:block !important; }
        table { width:100% !important; table-layout:fixed !important; border-collapse:collapse !important; margin:2px 0 5px 0 !important; }
        td, th { border:.5pt solid #445 !important; padding:1.3px 3.5px !important; font-size:9.4pt !important;
                 line-height:1.16 !important; white-space:normal !important; word-break:break-word !important;
                 overflow-wrap:anywhere !important; vertical-align:top !important; }
        th { background:#e8eef6 !important; font-weight:700 !important; }
        tr { page-break-inside:avoid !important; }
        .panel, .panel-body, .Div-Border-Side-New, .form-group, .form-horizontal, .bs-example, .card, .container, .row
           { padding:1px !important; margin:1px 0 !important; box-shadow:none !important; min-height:0 !important; }
        .control-label { margin:0 !important; padding:0 2px !important; line-height:1.22 !important; }
        .Div-Border-Side-New > .form-group { margin:0 !important; }
        .panel-heading { padding:3px 6px !important; }
        br { line-height:0.6 !important; }
        a { text-decoration:none !important; color:#000 !important; }
        img[src*="image"] { display:none; }
        .breadcrumb, #myBtn, .alert-danger, header, nav, .navbar, footer, #__bot_banner { display:none !important; }
    """

    /**
     * Forces a desktop-width viewport so the record reflows wide (like Chrome printing
     * landscape A4) instead of into dozens of tall phone-width pages. Paired with
     * WebViewCapture.LAYOUT_CSS_WIDTH.
     */
    fun wideViewportJs(): String = """
    (function(){
      try {
        document.querySelectorAll('meta[name=viewport]').forEach(function(m){ m.remove(); });
        var m = document.createElement('meta'); m.name='viewport'; m.content='width=1123';
        document.head.appendChild(m);
        var s = document.getElementById('__lr_wide'); if(!s){ s=document.createElement('style'); s.id='__lr_wide'; document.head.appendChild(s); }
        s.innerHTML = 'html,body{width:1123px !important;min-width:1123px !important;overflow-x:visible !important;}';
      } catch(e) {}
    })();
    """.trimIndent()

    /** Injects the print CSS and strips empty rows/tables/sections (format.mjs DOM pass). */
    fun cleanupJs(): String = """
    (function(){
      try {
        var s = document.createElement('style'); s.innerHTML = `$CLEANUP_CSS`; document.head.appendChild(s);
        // Drop only the spotlight artifacts. Do NOT hide the cascade form by container —
        // AnyRoR's real result page has no form, and walking up to .panel/.form nukes the
        // record (the whole page is one ASP.NET form / one .panel-primary).
        var sc = document.getElementById('__lr_spot_css'); if (sc) sc.remove();
        document.querySelectorAll('.lr-ring').forEach(function(e){ e.classList.remove('lr-ring'); });
        var blank = function(t){ return (t||'').replace(/[\s\-—_.]/g,'') === ''; };
        document.querySelectorAll('table').forEach(function(t){
          Array.from(t.rows).forEach(function(r){ if(!r.querySelector('th') && blank(r.textContent)){ r.remove(); } });
        });
        document.querySelectorAll('.Div-Border-Side-New').forEach(function(panel){
          var hasData = Array.from(panel.querySelectorAll('table')).some(function(t){
            return Array.from(t.rows).some(function(r){ return !r.querySelector('th') && (r.textContent||'').replace(/\s+/g,'').length > 2; });
          });
          var h = panel.querySelector('.text-success');
          var bodyLen = ((panel.textContent||'').replace(h?h.textContent:'','').replace(/[\s\-—_.]/g,'')).length;
          if (!hasData && bodyLen < 30) panel.style.display='none';
        });
        document.getElementById('__bot_banner') && document.getElementById('__bot_banner').remove();
      } catch(e) {}
    })();
    """.trimIndent()

    /**
     * Reports whether the actual 7/12 record has loaded (not just the cascade form, which
     * also uses .Div-Border-Side-New panels + the word "સર્વે"). Keys off record-only content
     * like ખાતેદાર / કબ્જેદાર / "ગામ નમૂનો ૭/૧૨". Returns 'READY', 'NOTFOUND', or 'WAIT'.
     */
    fun detailReadyJs(): String = """
    (function(){
      try {
        var t = document.body ? document.body.innerText : '';
        // Use RECORD-ONLY markers that never appear on the cascade form: the result table
        // header "ખાતા નંબર" and the page heading "…સંપૂર્ણ વિગતો". (The form's record-type
        // option says "…સંપૂર્ણ માહિતી" — deliberately not matched.) If the Get Record Detail
        // button is still present, we're still on the form (wrong CAPTCHA) → keep waiting.
        var onForm = document.getElementById('${AnyRor.Ids.GET_DETAIL_BUTTON}') != null;
        var hasRecord = /ખાતા\s*નંબર|સંપૂર્ણ\s*વિગત/.test(t);
        if (hasRecord && !onForm) return 'READY';
        if (!onForm && /Record Not Found|No Record Found|રેકોર્ડ મળ્યો નથી|માહિતી ઉપલબ્ધ નથી/i.test(t)) return 'NOTFOUND';
        return 'WAIT';
      } catch(e) { return 'WAIT'; }
    })();
    """.trimIndent()

    /**
     * Dims the whole page and spotlights the CAPTCHA image/input + Get Record Detail
     * button, and routes that button's click back to the app. Never auto-solves.
     */
    fun dimSpotlightJs(): String = """
    (function(){
      try {
        if (!document.getElementById('__lr_spot_css')) {
          var style = document.createElement('style'); style.id='__lr_spot_css';
          // Ring only — NO dimming scrim (a scrim greys out the submit button; and opacity
          // on descendants compounds through nesting and whites the page out).
          style.innerHTML = '.lr-ring{outline:3px solid #B4531B !important;outline-offset:3px;border-radius:10px !important;}';
          document.head.appendChild(style);
        }
        var btn = document.getElementById('${AnyRor.Ids.GET_DETAIL_BUTTON}');
        var capImg = document.querySelector('img[src*="captcha" i]');
        var inp = document.querySelector('input[id*="captcha" i]')
          || document.querySelector('input[id*="txtCaptcha" i]')
          || (capImg && capImg.closest('div,td,table,form') || document).querySelector('input[type=text]');
        if (capImg) capImg.classList.add('lr-ring');
        if (inp) inp.classList.add('lr-ring');
        if (btn) btn.classList.add('lr-ring');
        if (inp) inp.scrollIntoView({block:'center'}); else if (btn) btn.scrollIntoView({block:'center'});
        function fire(){ try{ AndroidCapture.onSubmit(); }catch(e){} }
        // Cover every way the user might submit: button click, Enter in the code box, form submit.
        if (btn && !btn.dataset.lrHook) { btn.dataset.lrHook='1'; btn.addEventListener('click', fire); }
        if (inp && !inp.dataset.lrHook) { inp.dataset.lrHook='1';
          inp.addEventListener('keydown', function(e){ if(e.key==='Enter'||e.keyCode===13) fire(); }); }
        var f = (btn && btn.form) || (inp && inp.form);
        if (f && !f.dataset.lrHook) { f.dataset.lrHook='1'; f.addEventListener('submit', fire); }
      } catch(e) {}
    })();
    """.trimIndent()
}
