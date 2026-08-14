package com.landrecords.app.web

/**
 * JS injected into the AnyRoR "INTEGRATED SURVEY NO DETAILS" result page (InfoSurveyNoDetail.aspx)
 * to drive the Registered-Deeds capture.
 *
 * Deeds are NOT a separate AnyRoR record type: the desktop scripts (anyror/deed-step1.mjs) use the
 * SAME cascade as the Integrated record — record type "8", district/taluka/village/survey — and the
 * deeds appear as a GridView table on the resulting InfoSurveyNoDetail page:
 *
 *     <table id="ContentPlaceHolder1_gvgarviProDet" ...>   (garvi = Gujarat Sub-Registrar / SRO)
 *       columns: Office Name | Survey No | Document Year | Document No | Document Date |
 *                Party Type | Party Name | Consideration Amount | [View Deed]
 *
 * Each row's "View Deed" is an ASP.NET LinkButton, e.g.
 *     <a id="ContentPlaceHolder1_gvgarviProDet_lbDownload_0"
 *        href="javascript:__doPostBack('ctl00$ContentPlaceHolder1$gvgarviProDet$ctl02$lbDownload','')">View Deed</a>
 *
 * i.e. clicking it does a full-form POST back to InfoSurveyNoDetail.aspx with
 * __EVENTTARGET set to that control; the server then streams the scanned deed file as an
 * attachment. We replay that POST from Kotlin with the WebView's session cookies (see
 * [DeedsDownloader]) — the exact same "read the form in JS, fetch the bytes in Kotlin" pattern
 * as IrcmsInjection.orderFormJs + OrderDownloader.
 */
object DeedsInjection {

    /** GridView that ASP.NET renders for the SRO/garvi registered-documents section. */
    const val GARVI_TABLE_ID = "ContentPlaceHolder1_gvgarviProDet"

    /**
     * Reads the deed table + the whole ASP.NET form as one JSON payload:
     *   { action, fields:{...all form inputs incl __VIEWSTATE/__VIEWSTATEGENERATOR/__EVENTVALIDATION...},
     *     deeds:[{index, eventTarget, eventArgument, office, survey, docYear, docNo, docDate,
     *             partyType, partyName, amount}] }
     * Returns the string 'NONE' when this survey has no registered deeds (no garvi table / no rows).
     *
     * [DeedsDownloader.fetchDeed] takes this JSON and, per deed, overrides __EVENTTARGET with the
     * row's eventTarget to pull that deed's bytes.
     */
    /** Diagnostic: is the garvi deed table present on the live page, and what tables exist? */
    fun deedDebugJs(): String = """
    (function(){ try{
      var ids=Array.prototype.slice.call(document.querySelectorAll('table[id]')).map(function(t){return t.id.replace('ContentPlaceHolder1_','');});
      var g=document.getElementById('$GARVI_TABLE_ID');
      var body=document.body.innerText||'';
      var viewDeed=/View Deed/i.test(body);                          // the actual deed button label
      var subReg=/Sub.?registrar|સબ.?રજીસ્ટ્રાર/i.test(body);        // the deed section heading
      // Any table that contains the deed download links, whatever its id.
      var deedTbls=Array.prototype.slice.call(document.querySelectorAll('table')).filter(function(t){
        return /View Deed|lbDownload|Sub.?registrar/i.test(t.innerHTML||''); }).map(function(t){ return t.id||'noid'; });
      return 'garvi='+(!!g)+' viewDeed='+viewDeed+' subReg='+subReg+' deedTables=['+deedTbls.join(',')+'] tables=['+ids.join(',')+']';
    }catch(e){ return 'ERR:'+e.message; } })();
    """.trimIndent()

    /**
     * Isolates the "Sub registrar Deed Details" table (the deed metadata: office/survey/doc-no/
     * date/party/amount) into a clean standalone page for printing, and returns 'READY:n' (n deed
     * rows) or 'NONE'. The scanned deed FILES ("View Deed") are dead server-side ("Document Record
     * Not Found" even on desktop), so we capture the details table, not the files.
     */
    fun deedCaptureJs(): String = """
    (function(){
      try{
        var tbl=document.getElementById('$GARVI_TABLE_ID');
        if(!tbl){
          tbl=Array.prototype.slice.call(document.querySelectorAll('table'))
            .filter(function(t){ return /View Deed|Sub.?registrar|સબ.?રજીસ્ટ્રાર/i.test(t.innerHTML||''); })[0];
        }
        if(!tbl) return 'NONE';
        var dataRows=Array.prototype.slice.call(tbl.querySelectorAll('tr')).filter(function(r){
          return r.querySelector('td') && !r.querySelector('th') && (r.textContent||'').replace(/\s+/g,'').length>4; });
        var n=dataRows.length;
        if(n===0) return 'NONE';
        // Drop the dead "View Deed" links so they don't render as clickable-looking junk.
        Array.prototype.slice.call(tbl.querySelectorAll('a,input[type=submit],input[type=button]')).forEach(function(a){
          if(/View Deed/i.test((a.textContent||a.value||''))) a.parentNode && (a.outerHTML='—'); });
        var title='AnyRoR — Registered Deeds / નોંધાયેલ દસ્તાવેજ';
        document.body.innerHTML='<div style="padding:8px;font-family:Arial,sans-serif;">'+
          '<div style="font-size:15pt;font-weight:800;color:#1f4e78;border-bottom:2.5px solid #1f4e78;padding-bottom:6px;margin-bottom:8px;">'+title+'</div>'+
          tbl.outerHTML+
          '<div style="font-size:8.5pt;color:#777;margin-top:8px;">* Scanned deed images are not available from the AnyRoR server; these are the registered-deed details.</div></div>';
        var s=document.createElement('style');
        s.innerHTML='@page{size:A4 landscape;margin:6mm;} html,body{background:#fff!important;width:100%!important;margin:0!important;}'+
          'table{width:100%!important;border-collapse:collapse!important;table-layout:fixed!important;}'+
          'td,th{border:.5pt solid #445!important;padding:2px 5px!important;font-size:9.4pt!important;word-break:break-word!important;vertical-align:top!important;color:#111!important;}'+
          'th{background:#e8eef6!important;font-weight:700!important;}';
        document.head.appendChild(s);
        return 'READY:'+n;
      }catch(e){ return 'ERR:'+e.message; }
    })();
    """.trimIndent()

    /**
     * Reads the Sub-registrar deed grid as DATA (no DOM rewrite), for the INTEGRATED pass.
     *
     * Deeds live on the very same type-8 detail page as the integrated record, so the integrated
     * fetch reads them here — from the PRISTINE DOM, before cleanupJs restyles it and before the
     * per-entry Select postbacks run. It must not touch the DOM: [deedCaptureJs] replaces
     * document.body, which would destroy the __doPostBack form the entry capture needs.
     *
     * Returns a JSON array of one object per grid ROW (the site prints one row per party, so a
     * single registered document appears as several rows — grouping is [DeedRows.parse]'s job):
     *   [{office, survey, docYear, docNo, docDate, partyType, partyName, amount}, …]
     * '[]' when the survey has no registered deeds.
     */
    fun deedRowsJs(): String = """
    (function(){
      try{
        var tbl=document.getElementById('$GARVI_TABLE_ID');
        if(!tbl){
          tbl=Array.prototype.slice.call(document.querySelectorAll('table'))
            .filter(function(t){ return /View Deed|lbDownload/i.test(t.innerHTML||''); })[0];
        }
        if(!tbl) return '[]';
        var txt=function(td){ return (td.innerText||td.textContent||'').replace(/\s+/g,' ').trim(); };
        var out=[];
        Array.prototype.slice.call(tbl.querySelectorAll('tr')).forEach(function(r){
          if(r.querySelector('th')) return;                 // header row
          var tds=Array.prototype.slice.call(r.cells);
          if(tds.length<8) return;                          // not a data row
          var c=tds.map(txt);
          if(!(c[2]||c[3])) return;                         // no doc year AND no doc no -> junk row
          out.push({ office:c[0], survey:c[1], docYear:c[2], docNo:c[3], docDate:c[4],
                     partyType:c[5], partyName:c[6], amount:c[7] });
        });
        return JSON.stringify(out);
      }catch(e){ return '[]'; }
    })();
    """.trimIndent()

    fun deedFormJs(): String = """
    (function(){
      try {
        var tbl=document.getElementById('$GARVI_TABLE_ID');
        if(!tbl) return 'NONE';
        var links=Array.prototype.slice.call(tbl.querySelectorAll('a[id*="lbDownload"],a[href*="__doPostBack"]'));
        if(!links.length) return 'NONE';
        var f=document.getElementById('form1') || links[0].closest('form');
        if(!f) return 'NONE';
        var action=new URL(f.getAttribute('action'), location.href).href;
        var fields={};
        f.querySelectorAll('input,select,textarea').forEach(function(el){
          if(!el.name) return;
          if((el.type==='checkbox'||el.type==='radio') && !el.checked) return;
          fields[el.name]=el.value;
        });
        var deeds=links.map(function(a,i){
          var m=(a.getAttribute('href')||'').match(/__doPostBack\('([^']*)'\s*,\s*'([^']*)'\)/);
          var tr=a.closest('tr');
          var tds=tr?Array.prototype.slice.call(tr.cells).map(function(td){return (td.innerText||'').replace(/\s+/g,' ').trim();}):[];
          return { index:i,
            eventTarget:  m?m[1]:'',
            eventArgument:m?m[2]:'',
            office:tds[0]||'', survey:tds[1]||'', docYear:tds[2]||'', docNo:tds[3]||'',
            docDate:tds[4]||'', partyType:tds[5]||'', partyName:tds[6]||'', amount:tds[7]||'' };
        }).filter(function(d){ return d.eventTarget; });
        if(!deeds.length) return 'NONE';
        return JSON.stringify({ action:action, fields:fields, deeds:deeds });
      } catch(e){ return 'NONE'; }
    })();
    """.trimIndent()

    /**
     * Fast presence check for the READY poll: 'YES' if the garvi deed table has at least one
     * "View Deed" row, 'NONE' otherwise. (deedFormJs is the authoritative read; this is only to
     * decide, right after the detail page renders, between the deed-capture path and markEmpty.)
     */
    fun hasDeedsJs(): String = """
    (function(){
      try {
        var tbl=document.getElementById('$GARVI_TABLE_ID');
        if(!tbl) return 'NONE';
        return tbl.querySelector('a[id*="lbDownload"],a[href*="__doPostBack"]') ? 'YES' : 'NONE';
      } catch(e){ return 'NONE'; }
    })();
    """.trimIndent()
}
