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
