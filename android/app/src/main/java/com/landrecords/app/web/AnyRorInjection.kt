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
        function nn(s){ return (s||'')
          .replace(/[૦-૯]/g,function(d){return '૦૧૨૩૪૫૬૭૮૯'.indexOf(d);})
          .replace(/પ/g,'p').replace(/ળ/g,'લ').replace(/ી/g,'િ').replace(/ૂ/g,'ુ')
          .toLowerCase().replace(/[^a-z0-9઀-૿]+/g,''); }
        function toks(s){ return (s||'').replace(/[()\[\].,\-\/_]+/g,' ').split(/\s+/).map(nn).filter(Boolean); }
        function realOpts(sel){ return sel ? Array.from(sel.options).filter(function(o){ return o.value && o.value!=='-1' && o.value!=='0'; }) : []; }
        function optText(o){ return (o.text||'').replace(/\s+/g,' ').trim(); }
        function setOpt(sel,o){ sel.value=o.value; sel.dispatchEvent(new Event('change',{bubbles:true})); return optText(o)||'?'; }
        // Never GUESSES: exact-normalized, then UNIQUE substring (either direction), then UNIQUE
        // token-subset (want tokens all in option), then UNIQUE reverse token-subset (option tokens
        // all in want — the DB name longer than the site's, e.g. "નડિયાદ ગ્રામ્ય" vs a bare
        // "નડિયાદ" option). Returns the chosen option's visible text, or '' if nothing was unambiguous.
        function pick(sel, wantText){
          var w=nn(wantText); if(!w) return '';
          var opts=realOpts(sel);
          var eq=opts.filter(function(o){ return nn(o.text)===w; });
          if(eq.length) return setOpt(sel, eq[0]);
          var sub=opts.filter(function(o){ var t=nn(o.text); return t.indexOf(w)>=0 || w.indexOf(t)>=0; });
          if(sub.length===1) return setOpt(sel, sub[0]);
          var wt=toks(wantText);
          if(wt.length){
            var tk=opts.filter(function(o){ var t=nn(o.text); return wt.every(function(x){ return t.indexOf(x)>=0; }); });
            if(tk.length===1) return setOpt(sel, tk[0]);
            var rev=opts.filter(function(o){ var ot=toks(o.text); return ot.length && ot.every(function(x){ return wt.indexOf(x)>=0; }); });
            if(rev.length===1) return setOpt(sel, rev[0]);
            // Rural/urban qualifier anchor: our stored place-name spelling can drift from the
            // site's (e.g. "નડિયાદ" vs the site's "નડીઆદ"), but the qualifier token is stable.
            // If want carries a taluka qualifier and EXACTLY ONE option shares it, pick that —
            // this can never confuse ગ્રામ્ય (rural) with શહેર (city) / નગર, only the two options
            // being distinguished by the qualifier.
            var QUAL=['ગ્રામ્ય','શહેર','નગર','નગરપાલિકા'].map(nn);
            var wq=wt.filter(function(x){ return QUAL.indexOf(x)>=0; });
            if(wq.length){
              var qm=opts.filter(function(o){ var ot=toks(o.text); return wq.every(function(x){ return ot.indexOf(x)>=0; }); });
              if(qm.length===1) return setOpt(sel, qm[0]);
            }
          }
          return '';
        }
        // Diagnostic dump for a stalled dropdown: what we wanted + every real option the page shows,
        // so the logcat says exactly why pick() couldn't choose (wrong text / ambiguous / not there).
        function diag(sel, want){
          var os=realOpts(sel);
          var list=os.slice(0,60).map(optText);
          return 'want='+String(want||'').replace(/\s+/g,' ').trim()+'|n='+os.length+'|opts='+list.join(' · ')+(os.length>60?' …':'');
        }
        // Survey text is "<old-survey>" or "<old> ~~ <resurvey>"; match the OLD field EXACTLY (keep '/').
        function oldSurveyTok(s){ s=(s||'').split('~')[0];
          return s.replace(/[૦-૯]/g,function(d){return '૦૧૨૩૪૫૬૭૮૯'.indexOf(d);})
            .replace(/પ/g,'p').replace(/\s+/g,'').toLowerCase(); }
        function pickSurvey(sel, want){ var w=oldSurveyTok(want); var best=null;
          Array.from(sel.options).forEach(function(o){ if(best) return; if(o.value && oldSurveyTok(o.text)===w) best=o; });
          if(best){ sel.value=best.value; sel.dispatchEvent(new Event('change',{bubbles:true})); return optText(best)||'?'; } return ''; }
        function unset(sel){ if(!sel) return true; var v=(sel.value||'').trim(); return v===''||v==='0'||v==='-1'; }

        var rt=document.getElementById('${AnyRor.Ids.RECORD_TYPE}');
        var dist=document.getElementById('${AnyRor.Ids.DISTRICT}');
        // AnyRoR throws an Application Error if the record type (8/11) is posted BEFORE the geo
        // cascade, so fill district -> taluka -> village FIRST and select the record type LAST.
        // Fallback: if the district dropdown can't be used until a record type is chosen (disabled
        // or empty), set the record type first — this preserves the path that already works today.
        var distReady = dist && !dist.disabled && realOpts(dist).length>0;
        if(rt && (rt.value||'')!=='$recordValue' && !distReady){ rt.value='$recordValue'; rt.dispatchEvent(new Event('change',{bubbles:true})); return 'RT'; }
        if(dist && unset(dist) && '$districtGu'){ var hd=pick(dist,'$districtGu'); return hd?('DIST|hit='+hd):('WAIT|dist|'+diag(dist,'$districtGu')); }
        var tal=document.getElementById('${AnyRor.Ids.TALUKA}');
        if(tal && unset(tal) && '$talukaGu'){ var ht=pick(tal,'$talukaGu'); return ht?('TAL|hit='+ht):('WAIT|tal|'+diag(tal,'$talukaGu')); }
        var vil=document.getElementById('${AnyRor.Ids.VILLAGE}');
        if(vil && unset(vil) && '$villageGu'){ var hv=pick(vil,'$villageGu'); return hv?('VIL|hit='+hv):('WAIT|vil|'+diag(vil,'$villageGu')); }
        // Geo cascade is set -> NOW select the record type, which renders the survey dropdown.
        if(rt && (rt.value||'')!=='$recordValue'){ rt.value='$recordValue'; rt.dispatchEvent(new Event('change',{bubbles:true})); return 'RT'; }
        var sur=document.getElementById('$surveyDropId');
        if('$surveyNorm'){
          if(!sur) return 'WAIT|sur|absent';
          var wS=oldSurveyTok('$surveyNorm');
          var cur=sur.options[sur.selectedIndex];
          // Verify the CURRENTLY-selected survey is the one we want — don't just fill when empty.
          // AnyRoR can restore a previous fetch's selection after a reload (bfcache / viewstate), and
          // gating on unset() alone would then capture that stale survey and file it under the new
          // one (e.g. "ran 174/p૨, got 174/p૧"). Re-select whenever the current value isn't the want.
          if(!cur || !cur.value || oldSurveyTok(cur.text)!==wS){
            var hs=pickSurvey(sur,'$surveyNorm');
            if(hs) return 'SUR|hit='+hs;
            var near=Array.from(sur.options).filter(function(o){ return o.value; }).map(optText)
               .filter(function(t){ return oldSurveyTok(t).indexOf(wS)>=0; }).slice(0,20);
            return 'WAIT|sur|want=$surveyNorm|tok='+wS+'|n='+realOpts(sur).length+'|near='+near.join(' · ');
          }
        }
        return 'READY';
      } catch(e) { return 'WAIT|err|'+(e&&e.message?e.message:e); }
    })();
    """.trimIndent()

    /** Reproduces anyror/format.mjs addStyleTag — the print CSS for the Integrated record. */
    const val CLEANUP_CSS = """
        /* Tell the print engine the page is landscape A4 — without this it lays the content
           out at portrait/letter width and drops it on the left of the landscape sheet,
           leaving the big empty margin on the right. */
        @page { size: A4 landscape; margin: 6mm; }
        html, body { background:#fff !important; width:100% !important; max-width:100% !important; margin:0 !important; }
        * { background-image:none !important; }
        .imgwatermark { background:#fff !important; }
        /* Bootstrap's .container is a FIXED width (~1170px) — on the wide print surface that
           caps the record and wastes the whole right side. Force every container/column full. */
        .container, .container-fluid, [class*="container"] { width:100% !important; max-width:100% !important; margin:0 auto !important; padding:0 !important; }
        [class*="col-md-"],[class*="col-lg-"],[class*="col-sm-"],[class*="col-xs-"]{
           width:100% !important; max-width:100% !important; float:none !important; display:block !important; }
        /* The table's OWN border (from <table border="1" bordercolor=…>) is the thick blue frame.
           Kill it; the slate cell borders below define the grid. */
        table, .table, .table-bordered, table[border] { width:100% !important; table-layout:fixed !important;
           border-collapse:collapse !important; margin:2px 0 5px 0 !important; border:none !important; }
        td, th, .table-bordered td, .table-bordered th { border:.5pt solid #445 !important; padding:1.3px 3.5px !important; font-size:9.4pt !important;
                 line-height:1.16 !important; white-space:normal !important; word-break:break-word !important;
                 overflow-wrap:anywhere !important; vertical-align:top !important; color:#111 !important; }
        th { background:#e8eef6 !important; font-weight:700 !important; }
        tr { page-break-inside:avoid !important; }
        .text-success { color:#127a3d !important; }
        .panel, .panel-body, .Div-Border-Side-New, .form-group, .form-horizontal, .bs-example, .card, .container, .row
           { padding:1px !important; margin:1px 0 !important; box-shadow:none !important; min-height:0 !important; border-color:#445 !important; }
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

    /**
     * Full on-device port of anyror/format.mjs: strips empty rows/tables/sections, balances
     * column widths by content length (colgroups), compacts long 1-column index lists into
     * multi-column blocks, hides the native stacked summary, and inserts the compact blue
     * title header — so a phone capture renders byte-for-byte like the desktop PDFs.
     */
    fun cleanupJs(
        districtEn: String = "",
        talukaEn: String = "",
        villageEn: String = "",
        surveyNo: String = "",
    ): String = """
    (function(){
      try {
        document.getElementById('__bot_banner') && document.getElementById('__bot_banner').remove();
        // Drop the spotlight artifacts first (rings + dim CSS) so they never bake into the PDF.
        var sc = document.getElementById('__lr_spot_css'); if (sc) sc.remove();
        document.querySelectorAll('.lr-ring').forEach(function(e){ e.classList.remove('lr-ring'); });

        var blank = function(t){ return (t||'').replace(/[\s\-—_.]/g,'') === ''; };
        var isHeadingText = function(t){ return t && t.length < 70 && /Details|વિગત/i.test(t); };

        // 1) remove blank data rows
        document.querySelectorAll('table').forEach(function(t){
          Array.from(t.rows).forEach(function(r){ if(!r.querySelector('th') && blank(r.textContent)){ r.remove(); } });
        });
        // 2) hide tables with no real data + the heading right above them
        document.querySelectorAll('table').forEach(function(t){
          var data = Array.from(t.rows).filter(function(r){ return !r.querySelector('th'); });
          if(!data.some(function(r){ return !blank(r.textContent); })){
            t.style.display='none';
            var p=t.previousElementSibling, hops=0;
            while(p && hops<4){ var x=(p.textContent||'').replace(/\s+/g,' ').trim(); if(isHeadingText(x)){ p.style.display='none'; break; } p=p.previousElementSibling; hops++; }
          }
        });
        // 3) hide "Record Not Found" leaf nodes + their heading
        Array.from(document.querySelectorAll('div,span,p,td')).forEach(function(e){
          if(e.children.length) return;
          var t=(e.textContent||'').replace(/\s+/g,' ').trim();
          if(/^Record Not Found\.?${'$'}/i.test(t) || t==='---' || t==='----'){
            e.style.display='none';
            var p=e.previousElementSibling || (e.parentElement && e.parentElement.previousElementSibling), hops=0;
            while(p && hops<3){ var x=(p.textContent||'').replace(/\s+/g,' ').trim(); if(isHeadingText(x)){ p.style.display='none'; break; } p=p.previousElementSibling; hops++; }
          }
        });
        // 3b) hide whole empty Div-Border panels (Panchayat Tax / Electricity / Water / empty court cases)
        document.querySelectorAll('.Div-Border-Side-New').forEach(function(panel){
          var h=panel.querySelector('.text-success');
          var hasData=Array.from(panel.querySelectorAll('table')).some(function(t){ return Array.from(t.rows).some(function(r){ return !r.querySelector('th') && (r.textContent||'').replace(/\s+/g,'').length>2; }); });
          var bodyLen=((panel.textContent||'').replace(h?h.textContent:'','').replace(/[\s\-—_.]/g,'')).length;
          if(!hasData && bodyLen<30) panel.style.display='none';
        });
        // 4) compact long 1-column SHORT-value lists into a multi-column block
        document.querySelectorAll('table').forEach(function(t){
          if(t.style.display==='none') return;
          var cols=Math.max.apply(null,[0].concat(Array.from(t.rows).map(function(r){ return r.cells.length; })));
          if(cols!==1) return;
          var vals=Array.from(t.rows).filter(function(r){ return !r.querySelector('th'); }).map(function(r){ return (r.textContent||'').replace(/\s+/g,' ').trim(); }).filter(Boolean);
          if(vals.length<10) return;
          if(vals.reduce(function(a,s){ return a+s.length; },0)/vals.length>14) return;
          var head=t.querySelector('th');
          if(head){ var h=document.createElement('div'); h.style.cssText='font-weight:700;background:#e8eef6;padding:2px 6px;border:.4pt solid #aab;font-size:8.5pt;'; h.textContent=(head.textContent||'').replace(/\s+/g,' ').trim(); t.parentNode.insertBefore(h,t); }
          var wrap=document.createElement('div');
          wrap.style.cssText='column-count:10;column-gap:8px;font-size:8pt;border:.4pt solid #aab;border-top:none;padding:3px 6px;margin:0 0 6px 0;';
          wrap.innerHTML=vals.map(function(v){ return '<div style="break-inside:avoid;">'+v+'</div>'; }).join('');
          t.parentNode.insertBefore(wrap,t); t.style.display='none';
        });
        // 5) balance column widths by average content length (colgroup) so long-text columns
        //    wrap less and short columns stay narrow — fewer, cleaner pages
        document.querySelectorAll('table').forEach(function(t){
          if(t.style.display==='none') return;
          var rows=Array.from(t.rows).filter(function(r){ return r.cells.length; });
          if(rows.length<2) return;
          if(rows.some(function(r){ return Array.from(r.cells).some(function(c){ return (c.colSpan||1)>1 || (c.rowSpan||1)>1; }); })) return;
          var ncol=Math.max.apply(null,rows.map(function(r){ return r.cells.length; }));
          if(ncol<2 || ncol>14) return;
          var sum=new Array(ncol).fill(0), cnt=new Array(ncol).fill(0);
          rows.forEach(function(r){ Array.from(r.cells).forEach(function(c,i){ if(i<ncol){ sum[i]+=(c.innerText||'').replace(/\s+/g,' ').trim().length; cnt[i]++; } }); });
          var raw=sum.map(function(s,i){ return Math.max(6,Math.min(s/Math.max(1,cnt[i]),60)); });
          var total=raw.reduce(function(a,b){ return a+b; },0);
          var cg=document.createElement('colgroup');
          raw.forEach(function(x){ var col=document.createElement('col'); col.style.width=(100*x/total).toFixed(2)+'%'; cg.appendChild(col); });
          t.insertBefore(cg,t.firstChild);
        });

        // 6) inject the print CSS
        var s = document.createElement('style'); s.innerHTML = `$CLEANUP_CSS`; document.head.appendChild(s);

        // 7) read the top summary, drop the native stacked block, insert the compact header
        var grab=function(re){ var m=document.body.innerText.match(re); return m?(m[1]||'').trim():''; };
        var summary={
          area:grab(/Total Area[^:]*:\s*([^\n]+)/i), assessment:grab(/Total Assessment[^:]*:\s*([^\n]+)/i),
          tenure:grab(/Tenure[^:]*:\s*([^\n]+)/i), landUse:grab(/Land Use[^:]*:\s*([^\n]+)/i),
          asOf:(document.body.innerText.match(/તા\.\s*([0-9\/]+ [0-9:]+)\s*ની સ્થિતિએ/)||[,''])[1]||''
        };
        document.querySelectorAll('.Div-Border-Side-New').forEach(function(p){
          var h=((p.querySelector('.text-success')||{}).textContent||'').replace(/\s+/g,' ');
          if(/District \(|Land Details \(|જીલ્લો|જમીનની વિગત/.test(h)) p.style.display='none';
        });
        Array.from(document.querySelectorAll('h1,h2,h3,h4,div,span,strong,b,legend')).forEach(function(e){
          if(!e.children.length && /^સરવે નંબરને લગતી સંપૂર્ણ વિગતો/.test((e.textContent||'').replace(/\s+/g,' ').trim())) e.style.display='none';
        });
        document.getElementById('__anyror_title') && document.getElementById('__anyror_title').remove();
        var d=document.createElement('div'); d.id='__anyror_title';
        d.style.cssText='padding:4px 8px 6px;margin:0 0 7px 0;border-bottom:2.5px solid #1f4e78;font-family:Arial,sans-serif;display:block !important;';
        var chip=function(label,val){ return val?'<span style="margin-right:16px;white-space:nowrap;">'+label+': <b>'+val+'</b></span>':''; };
        d.innerHTML=
          '<div style="font-size:15pt;font-weight:800;color:#1f4e78;line-height:1.1;">AnyRoR — Integrated Survey Record</div>'+
          '<div style="font-size:10pt;color:#222;margin-top:3px;">Village <b>$villageEn</b> &middot; Taluka <b>$talukaEn</b> &middot; District <b>$districtEn</b>'+
          ' &nbsp;|&nbsp; Survey / Block No <b>$surveyNo</b>'+(summary.asOf?' &nbsp;|&nbsp; As of '+summary.asOf:'')+'</div>'+
          '<div style="font-size:9.5pt;color:#111;margin-top:4px;line-height:1.5;">'+
            chip('કુલ ક્ષેત્રફળ / Area',summary.area)+chip('આકાર / Assessment',summary.assessment)+
            chip('સત્તાપ્રકાર / Tenure',summary.tenure)+chip('ઉપયોગ / Land Use',summary.landUse)+'</div>';
        var anchor=document.querySelector('.panel.panel-primary')||document.querySelector('.panel-primary')||document.body.firstElementChild;
        anchor.parentNode.insertBefore(d,anchor);
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
        // AnyRoR's Land_Record_Validation() throws ("selectedIndex of null") and blocks the
        // button submit. Wrap it so a crash falls through to submit — the server still
        // validates the CAPTCHA. (Pressing Enter bypassed onclick, which is why it worked before.)
        if (typeof window.Land_Record_Validation === 'function' && !window.__lr_valwrap) {
          window.__lr_valwrap = 1;
          var _v = window.Land_Record_Validation;
          window.Land_Record_Validation = function(){ try { return _v.apply(this, arguments); } catch(e){ return true; } };
        }
        var btn = document.getElementById('${AnyRor.Ids.GET_DETAIL_BUTTON}');
        var capImg = document.querySelector('img[src*="captcha" i]');
        var inp = document.querySelector('input[id*="captcha" i]')
          || document.querySelector('input[id*="txtCaptcha" i]')
          || (capImg && capImg.closest('div,td,table,form') || document).querySelector('input[type=text]');
        if (capImg) capImg.classList.add('lr-ring');
        if (inp) inp.classList.add('lr-ring');
        // Insurance for desktop-UA (Deeds) fetches: keep the caret at the end so typed code isn't reversed.
        if (inp && !inp.dataset.lrCap){ inp.dataset.lrCap='1'; inp.setAttribute('dir','ltr');
          var toEnd=function(){ try{ var v=inp.value; inp.setSelectionRange(v.length, v.length); }catch(e){} };
          inp.addEventListener('keyup', toEnd); inp.addEventListener('input', toEnd); inp.addEventListener('focus', toEnd); }
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
