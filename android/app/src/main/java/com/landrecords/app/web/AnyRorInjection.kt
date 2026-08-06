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
          .replace(/પ/g,'p').replace(/\s+/g,'').toLowerCase(); }
        function unset(sel){ if(!sel) return true; var v=(sel.value||'').trim(); return v===''||v==='0'||v==='-1'; }
        function pick(sel, wantText){
          var want=norm(wantText); var best=null;
          Array.from(sel.options).forEach(function(o){ var t=norm(o.text);
            if(t && (t===want || t.indexOf(want)>=0 || want.indexOf(t)>=0)) if(!best) best=o; });
          if(best){ sel.value=best.value; sel.dispatchEvent(new Event('change',{bubbles:true})); return true; }
          return false;
        }
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

    /** Injects the print CSS and strips empty rows/tables/sections (format.mjs DOM pass). */
    fun cleanupJs(): String = """
    (function(){
      try {
        var s = document.createElement('style'); s.innerHTML = `$CLEANUP_CSS`; document.head.appendChild(s);
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
     * Reports the survey-detail result state so the app knows when to capture:
     * returns "READY" once the detail markup is present, else "WAIT".
     */
    fun detailReadyJs(): String = """
    (function(){
      try {
        var hasDetail = /InfoSurveyNoDetail/i.test(location.href)
          || document.querySelector('.Div-Border-Side-New, .panel-primary') != null;
        var hasSurvey = /સરવે|Survey No|સર્વે/i.test(document.body ? document.body.innerText : '');
        return (hasDetail && hasSurvey) ? 'READY' : 'WAIT';
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
        if (document.getElementById('__lr_dim')) return;
        var style = document.createElement('style'); style.id='__lr_dim';
        style.innerHTML = 'body *{opacity:.38 !important;} .lr-spot,.lr-spot *{opacity:1 !important;}'
          + '.lr-spot{outline:3px solid #B4531B;outline-offset:3px;border-radius:12px;background:#FBFCFB !important;}';
        document.head.appendChild(style);
        var btn = document.getElementById('${AnyRor.Ids.GET_DETAIL_BUTTON}');
        [btn, document.querySelector('img[src*="captcha" i]'), document.querySelector('img[src*="Captcha" i]'),
         document.querySelector('input[id*="captcha" i]'), document.querySelector('input[id*="txtCaptcha" i]')]
          .forEach(function(el){ if(el){ var p = el.closest('div,td,tr,form')||el; p.classList.add('lr-spot'); }});
        if (btn) btn.addEventListener('click', function(){ try{ AndroidCapture.onSubmit(); }catch(e){} });
      } catch(e) {}
    })();
    """.trimIndent()
}
