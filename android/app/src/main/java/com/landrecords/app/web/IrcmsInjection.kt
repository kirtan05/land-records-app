package com.landrecords.app.web

/**
 * JS injected into the iRCMS page(s). Mirrors [AnyRorInjection] but for the AJAX iRCMS flow.
 * The cascade lives on one page (no postbacks), so the app polls [prefillJs] until 'READY'.
 */
object IrcmsInjection {

    /**
     * Selects the cascade by visible text (district/taluka/village English names, which the
     * options carry like "ANAND : આણંદ") and the survey by normalized number. Each level is
     * AJAX-populated after the parent changes, so this is polled: it returns what it just did
     * ('DIST'|'TAL'|'VIL'|'SUR'), 'READY' when all set, or 'WAIT' while a level is still loading.
     */
    fun prefillJs(district: String, taluka: String, village: String, surveyNorm: String): String = """
    (function(){
      try {
        function norm(s){ return (s||'').replace(/[૦-૯]/g,function(d){return '૦૧૨૩૪૫૬૭૮૯'.indexOf(d);})
          .replace(/પ/g,'p').replace(/[~\/\s]+/g,'').toLowerCase(); }
        function unset(sel){ if(!sel) return true; var v=(''+sel.value).trim(); return v===''||v==='0'||v==='-1'; }
        function selText(sel, want){ want=(want||'').toLowerCase();
          var opts=Array.prototype.slice.call(sel.options), best=null;
          opts.forEach(function(o){ if(best) return; var t=(o.text||'').toLowerCase();
            if(o.value && t.indexOf(want)>=0) best=o; });
          if(best){ sel.value=best.value; sel.dispatchEvent(new Event('change',{bubbles:true})); return true; } return false; }
        function selSurvey(sel, want){ want=norm(want);
          var opts=Array.prototype.slice.call(sel.options), best=null;
          opts.forEach(function(o){ if(best) return; if(o.value && norm(o.text)===want) best=o; });
          if(best){ sel.value=best.value; sel.dispatchEvent(new Event('change',{bubbles:true})); return true; } return false; }

        var d=document.getElementById('${Ircms.Ids.DISTRICT}');
        if(d && unset(d)){ return selText(d,'$district')?'DIST':'WAIT'; }
        var t=document.getElementById('${Ircms.Ids.TALUKA}');
        if(t && unset(t)){ return (t.options.length>1 && selText(t,'$taluka'))?'TAL':'WAIT'; }
        var v=document.getElementById('${Ircms.Ids.VILLAGE}');
        if(v && unset(v)){ return (v.options.length>1 && selText(v,'$village'))?'VIL':'WAIT'; }
        var s=document.getElementById('${Ircms.Ids.SURVEY}');
        if(s && unset(s)){ return (s.options.length>1 && selSurvey(s,'$surveyNorm'))?'SUR':'WAIT'; }
        return 'READY';
      } catch(e){ return 'WAIT'; }
    })();
    """.trimIndent()

    /** One-shot DOM structure probe — logged so we can see the live page's real element ids. */
    fun debugDomJs(): String = """
    (function(){
      try {
        var sels = Array.prototype.slice.call(document.querySelectorAll('select')).map(function(s){
          return {id:s.id, name:s.name, val:s.value, opts:s.options.length, first:(s.options[0]?s.options[0].text:'').slice(0,18)}; });
        var caps = Array.prototype.slice.call(document.querySelectorAll('input,img')).filter(function(e){
          return /captcha|verif/i.test((e.id||'')+' '+(e.name||'')+' '+(e.getAttribute('src')||'')); })
          .map(function(e){ return {tag:e.tagName, id:e.id, name:e.name, type:e.type}; });
        var btns = Array.prototype.slice.call(document.querySelectorAll('button,input[type=button],input[type=submit],a.btn')).map(function(b){
          return {id:b.id, val:b.value, txt:(b.textContent||'').replace(/\s+/g,' ').trim().slice(0,16)}; }).slice(0,10);
        return JSON.stringify({title:document.title, url:location.href.slice(0,60), selects:sels, captchaish:caps, buttons:btns, bodyLen:(document.body.innerText||'').length});
      } catch(e){ return 'ERR:'+e.message; }
    })();
    """.trimIndent()

    /** Rings the CAPTCHA + View button and routes the View tap back to the app. Never auto-solves. */
    fun spotlightJs(): String = """
    (function(){
      try {
        if(!document.getElementById('__lr_spot_css')){
          var st=document.createElement('style'); st.id='__lr_spot_css';
          st.innerHTML='.lr-ring{outline:3px solid #B4531B !important;outline-offset:3px;border-radius:10px !important;}';
          document.head.appendChild(st);
        }
        var cap=document.getElementById('${Ircms.Ids.CAPTCHA}');
        var btn=document.getElementById('${Ircms.Ids.VIEW_BUTTON}');
        var img=document.querySelector('img[src*="captcha" i],img[id*="captcha" i]');
        // Force LTR — a Gujarati-locale WebView can render the code box right-to-left,
        // which makes typed characters look reversed.
        // The WebView parks the caret at position 0 on this input, so each char prepends (looks
        // reversed). Force the caret to the END after every keystroke → characters append.
        if(cap && !cap.dataset.lrCap){ cap.dataset.lrCap='1'; cap.setAttribute('dir','ltr');
          var toEnd=function(){ try{ var v=cap.value; cap.setSelectionRange(v.length, v.length); }catch(e){} };
          cap.addEventListener('keyup', toEnd); cap.addEventListener('input', toEnd); cap.addEventListener('focus', toEnd); }
        if(img) img.classList.add('lr-ring');
        if(cap) cap.classList.add('lr-ring');
        if(btn) btn.classList.add('lr-ring');
        if(cap) cap.scrollIntoView({block:'center'}); else if(btn) btn.scrollIntoView({block:'center'});
        function fire(){ try{ AndroidCapture.onSubmit(); }catch(e){} }
        // Don't preventDefault — the site's own click handler runs the CAPTCHA check + AJAX search.
        if(btn && !btn.dataset.lrHook){ btn.dataset.lrHook='1'; btn.addEventListener('click', fire); }
        if(cap && !cap.dataset.lrHook){ cap.dataset.lrHook='1';
          cap.addEventListener('keydown', function(e){ if(e.key==='Enter'||e.keyCode===13) fire(); }); }
      } catch(e){}
    })();
    """.trimIndent()

    /**
     * After the View tap: 'READY:n' once the case table has n rows, 'RETRY' on a wrong CAPTCHA
     * (the site shows "Invalid CAPTCHA" and refreshes it), 'EMPTY' if there are genuinely no
     * cases, else 'WAIT'.
     */
    fun listReadyJs(): String = """
    (function(){
      try {
        var err=(document.getElementById('errorMsgSurveyList')||{}).textContent||'';
        if(/invalid/i.test(err)) return 'RETRY';
        var rows=Array.prototype.slice.call(document.querySelectorAll('#${Ircms.Ids.TABLE} tbody tr'));
        var real=rows.filter(function(r){ return r.querySelector('.view_status') || (r.cells && r.cells.length>3); });
        if(real.length>0) return 'READY:'+real.length;
        if(/no record|not found|no case/i.test(document.body.innerText||'')) return 'EMPTY';
        return 'WAIT';
      } catch(e){ return 'WAIT'; }
    })();
    """.trimIndent()

    /** Reads the cases + the viewnewcasestatus form token as JSON: {token, cases:[{casekey,...}]}. */
    fun readCasesJs(): String = """
    (function(){
      try {
        var f=document.querySelector('form[action="viewnewcasestatus"] input[name=_token]')
              || document.querySelector('input[name=_token]');
        var token=f?f.value:'';
        var rows=Array.prototype.slice.call(document.querySelectorAll('#${Ircms.Ids.TABLE} tbody tr'));
        var cases=rows.map(function(tr){
          var b=tr.querySelector('.view_status');
          var tds=Array.prototype.slice.call(tr.cells).map(function(td){ return (td.innerText||'').replace(/\s+/g,' ').trim(); });
          if(!b) return { casekey:'', caseNo: tds[1]||'' };
          // The site builds casekey = base64(utf8( data-id ':' data-offcode ':' data-survno )).
          var id=b.getAttribute('data-id')||'', off=b.getAttribute('data-offcode')||'', sur=b.getAttribute('data-survno')||'';
          var str=id+':'+off+':'+sur;
          var skey=id?btoa(unescape(encodeURIComponent(str))):'';
          return { casekey: skey, caseNo: tds[1]||'', office: tds[2]||'', survno: tds[4]||'' };
        }).filter(function(c){ return c.casekey; });
        return JSON.stringify({ token: token, cases: cases });
      } catch(e){ return JSON.stringify({ token:'', cases:[] }); }
    })();
    """.trimIndent()

    /** Print cleanup for a single case-detail page: full width, hide chrome, tidy tables. */
    fun caseCleanupJs(): String = """
    (function(){
      try {
        var css=`
          @page { size: A4 landscape; margin: 6mm; }
          html,body{ background:#fff !important; width:100% !important; margin:0 !important; }
          nav,header,footer,.navbar,.footer,#auto-logout-form,.no-print,.btn,button,.breadcrumb{ display:none !important; }
          .container,.container-fluid,[class*="container"]{ width:100% !important; max-width:100% !important; margin:0 !important; padding:0 !important; }
          table,.table,.table-bordered,table[border]{ width:100% !important; border-collapse:collapse !important; border:none !important; margin:2px 0 5px 0 !important; }
          td,th{ border:.5pt solid #445 !important; padding:1.5px 4px !important; font-size:9.4pt !important; line-height:1.18 !important; word-break:break-word !important; vertical-align:top !important; color:#111 !important; }
          th{ background:#e8eef6 !important; font-weight:700 !important; }
          .card,.panel,.Div-Border-Side{ border-color:#445 !important; box-shadow:none !important; margin:2px 0 !important; padding:2px !important; }
          a{ color:#000 !important; text-decoration:none !important; }
        `;
        var s=document.createElement('style'); s.innerHTML=css; document.head.appendChild(s);
      } catch(e){}
    })();
    """.trimIndent()

    /**
     * Batch step: pick another survey in the (already-filled) cascade, clear the old results,
     * and re-click View — reusing the CAPTCHA the human already solved. Returns 'SEARCHED',
     * 'NOSURVEY' (that survey isn't in the dropdown), or an error marker.
     */
    fun selectSurveyAndSearchJs(surveyNorm: String): String = """
    (function(){
      try {
        function norm(s){ return (s||'').replace(/[૦-૯]/g,function(d){return '૦૧૨૩૪૫૬૭૮૯'.indexOf(d);})
          .replace(/પ/g,'p').replace(/[~\/\s]+/g,'').toLowerCase(); }
        var want=norm('$surveyNorm');
        var s=document.getElementById('${Ircms.Ids.SURVEY}'); if(!s) return 'NOSEL';
        var opts=Array.prototype.slice.call(s.options), best=null;
        opts.forEach(function(o){ if(best) return; if(o.value && norm(o.text)===want) best=o; });
        if(!best) return 'NOSURVEY';
        s.value=best.value; s.dispatchEvent(new Event('change',{bubbles:true}));
        var em=document.getElementById('errorMsgSurveyList'); if(em) em.innerHTML='';
        var tb=document.querySelector('#${Ircms.Ids.TABLE} tbody'); if(tb) tb.innerHTML=''; // drop stale rows
        var b=document.getElementById('${Ircms.Ids.VIEW_BUTTON}'); if(!b) return 'NOBTN';
        b.click();
        return 'SEARCHED';
      } catch(e){ return 'ERR:'+e.message; }
    })();
    """.trimIndent()

    /**
     * Batch spotlight: like [spotlightJs] but STRIPS the site's own View-button handler (which
     * rotates the CAPTCHA after every search via get_captcha) and routes the tap to the app, so
     * the app can drive every search via direct AJAX with the one solved code (reuse).
     */
    fun spotlightBatchJs(): String = """
    (function(){
      try {
        if(!document.getElementById('__lr_spot_css')){
          var st=document.createElement('style'); st.id='__lr_spot_css';
          st.innerHTML='.lr-ring{outline:3px solid #B4531B !important;outline-offset:3px;border-radius:10px !important;}';
          document.head.appendChild(st);
        }
        var cap=document.getElementById('${Ircms.Ids.CAPTCHA}');
        var btn=document.getElementById('${Ircms.Ids.VIEW_BUTTON}');
        var img=document.querySelector('img[src*="captcha" i],img[id*="captcha" i]');
        // The WebView parks the caret at position 0 on this input, so each char prepends (looks
        // reversed). Force the caret to the END after every keystroke → characters append.
        if(cap && !cap.dataset.lrCap){ cap.dataset.lrCap='1'; cap.setAttribute('dir','ltr');
          var toEnd=function(){ try{ var v=cap.value; cap.setSelectionRange(v.length, v.length); }catch(e){} };
          cap.addEventListener('keyup', toEnd); cap.addEventListener('input', toEnd); cap.addEventListener('focus', toEnd); }
        if(img) img.classList.add('lr-ring');
        if(cap) cap.classList.add('lr-ring');
        if(btn) btn.classList.add('lr-ring');
        if(cap) cap.scrollIntoView({block:'center'}); else if(btn) btn.scrollIntoView({block:'center'});
        // Remove the site's click handler (it calls get_captcha() on success → rotates the code).
        if(window.jQuery){ try{ window.jQuery('#${Ircms.Ids.VIEW_BUTTON}').off('click'); }catch(e){} }
        function fire(){ try{ AndroidCapture.onSubmit(); }catch(e){} }
        if(btn && !btn.dataset.lrHook){ btn.dataset.lrHook='1'; btn.addEventListener('click', fire); }
        if(cap && !cap.dataset.lrHook){ cap.dataset.lrHook='1';
          cap.addEventListener('keydown', function(e){ if(e.key==='Enter'||e.keyCode===13) fire(); }); }
      } catch(e){}
    })();
    """.trimIndent()

    /**
     * Directly runs the ViewSurveyListController search for [surveyNorm], reusing the code the
     * human typed — WITHOUT the button (so no get_captcha rotation). Populates #surveylist_table
     * and sets window.__lr_search to 'READY' | 'EMPTY:<msg>' | 'ERROR'. Poll with [searchStatusJs].
     */
    fun searchSurveyDirectJs(surveyNorm: String): String = """
    (function(){
      try {
        function norm(s){ return (s||'').replace(/[૦-૯]/g,function(d){return '૦૧૨૩૪૫૬૭૮૯'.indexOf(d);})
          .replace(/પ/g,'p').replace(/[~\/\s]+/g,'').toLowerCase(); }
        var want=norm('$surveyNorm');
        var s=document.getElementById('${Ircms.Ids.SURVEY}'); if(!s){ window.__lr_search='ERROR'; return 'NOSEL'; }
        var opts=Array.prototype.slice.call(s.options), best=null;
        opts.forEach(function(o){ if(best) return; if(o.value && norm(o.text)===want) best=o; });
        if(!best){ window.__lr_search='EMPTY:nosurvey'; return 'NOSURVEY'; }
        s.value=best.value;
        var dist=document.getElementById('${Ircms.Ids.DISTRICT}').value;
        var tal=document.getElementById('${Ircms.Ids.TALUKA}').value;
        var vill=document.getElementById('${Ircms.Ids.VILLAGE}').value;
        var cap=(document.getElementById('${Ircms.Ids.CAPTCHA}').value||'').trim();
        var tokEl=document.querySelector('input[name=_token]'); var tok=tokEl?tokEl.value:'';
        window.__lr_search='WAIT';
        window.jQuery.ajax({ url:'https://ircms.gujarat.gov.in/ViewSurveyListController', type:'POST', method:'POST',
          data:{ dist:dist, taluka:tal, village:vill, surveyno:best.value, captcha_code:cap, _token:tok },
          success:function(data){
            var tb=window.jQuery('#${Ircms.Ids.TABLE} tbody'); tb.find('tr').remove();
            if(!data || data.success===false){ window.__lr_search='EMPTY:'+((data&&data.message)||''); return; }
            var trHTML='';
            window.jQuery.each(data, function(k,v){ if(v && v.sr_no){
              trHTML += "<tr><td>"+v.sr_no+"</td><td>"+v.case_str+"</td><td>"+v.offname+"</td><td>"+v.dtv+"</td><td>"+v.sno_str+"</td><td>"+v.pet_res+"</td><td>"+v.view+"</td></tr>"; } });
            tb.append(trHTML);
            window.__lr_search = trHTML ? 'READY' : 'EMPTY:none';
          },
          error:function(x){ window.__lr_search='ERROR'; }
        });
        return 'SEARCHING';
      } catch(e){ window.__lr_search='ERROR'; return 'ERR:'+e.message; }
    })();
    """.trimIndent()

    /** Diagnostic: the captcha input's actual computed direction/alignment. */
    fun captchaDiagJs(): String = """
    (function(){ try{ var c=document.getElementById('${Ircms.Ids.CAPTCHA}'); if(!c) return 'no-captcha';
      var cs=getComputedStyle(c);
      return 'dir='+cs.direction+' align='+cs.textAlign+' bidi='+cs.unicodeBidi+' attr='+(c.getAttribute('dir')||'')+' type='+c.type+' cls='+(c.className||'');
    }catch(e){ return 'ERR:'+e.message; } })();
    """.trimIndent()

    /** Poll target for [searchSurveyDirectJs]: 'READY' | 'EMPTY:<msg>' | 'ERROR' | 'WAIT'. */
    fun searchStatusJs(): String =
        "(function(){ try{ return window.__lr_search || 'WAIT'; }catch(e){ return 'WAIT'; } })();"

    /**
     * Reads the disposed-case "Download Order" form (#download_file → POST download_order with
     * _token/skey/f), as JSON {action, fields}. Returns 'NONE' when the case has no order.
     * The actual PDF is fetched from Kotlin with the session cookies (see OrderDownloader).
     */
    fun orderFormJs(): String = """
    (function(){
      try {
        var f=document.getElementById('download_file');
        if(!f) return 'NONE';
        var action=new URL(f.getAttribute('action'), location.href).href;
        var fields={};
        f.querySelectorAll('input').forEach(function(i){ if(i.name) fields[i.name]=i.value; });
        if(!Object.keys(fields).length) return 'NONE';
        return JSON.stringify({ action:action, fields:fields });
      } catch(e){ return 'NONE'; }
    })();
    """.trimIndent()

    /** URL-encoded POST body for a case detail. */
    fun caseBody(token: String, casekey: String): String =
        "_token=" + enc(token) + "&casekey=" + enc(casekey)

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
