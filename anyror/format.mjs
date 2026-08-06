// Shared AnyRoR detail-page formatter: strips empty rows/tables/sections, removes the
// watermark, hides chrome, stacks only the tall side-by-side sections, adds a title.
// Used by the live runner AND the offline local-HTML renderer (identical output).

export async function applyCleanFormat(page, survey) {
  const removed = await page.evaluate(() => {
    document.getElementById('__bot_banner')?.remove(); // never bake the progress banner into the PDF
    const blank = (s) => (s || '').replace(/[\s \-—_.]/g, '') === '';
    const isHeadingText = (t) => t && t.length < 70 && /Details|વિગત/i.test(t);
    let rows = 0, tables = 0, secs = 0;
    document.querySelectorAll('table').forEach((t) => {
      Array.from(t.rows).forEach((r) => { if (!r.querySelector('th') && blank(r.textContent)) { r.remove(); rows++; } });
    });
    document.querySelectorAll('table').forEach((t) => {
      const data = Array.from(t.rows).filter((r) => !r.querySelector('th'));
      if (!data.some((r) => !blank(r.textContent))) {
        t.style.display = 'none'; tables++;
        let p = t.previousElementSibling, hops = 0;
        while (p && hops < 4) { const x = (p.textContent || '').replace(/\s+/g, ' ').trim(); if (isHeadingText(x)) { p.style.display = 'none'; break; } p = p.previousElementSibling; hops++; }
      }
    });
    Array.from(document.querySelectorAll('div,span,p,td')).forEach((e) => {
      if (e.children.length) return;
      const t = (e.textContent || '').replace(/\s+/g, ' ').trim();
      if (/^Record Not Found\.?$/i.test(t) || t === '---' || t === '----') {
        e.style.display = 'none'; secs++;
        let p = e.previousElementSibling || (e.parentElement && e.parentElement.previousElementSibling), hops = 0;
        while (p && hops < 3) { const x = (p.textContent || '').replace(/\s+/g, ' ').trim(); if (isHeadingText(x)) { p.style.display = 'none'; break; } p = p.previousElementSibling; hops++; }
      }
    });
    // 3b) hide whole empty sections (Div-Border panels with no data table + no real body text,
    //     e.g. Panchayat Tax / Electricity Bill / Water Bill / empty court-case sections)
    document.querySelectorAll('.Div-Border-Side-New').forEach((panel) => {
      const h = panel.querySelector('.text-success');
      const hasData = Array.from(panel.querySelectorAll('table')).some((t) => Array.from(t.rows).some((r) => !r.querySelector('th') && (r.textContent || '').replace(/\s+/g, '').length > 2));
      const bodyLen = ((panel.textContent || '').replace(h ? h.textContent : '', '').replace(/[\s\-—_.]/g, '')).length;
      if (!hasData && bodyLen < 30) { panel.style.display = 'none'; secs++; }
    });
    // 4) compact 1-column lists of SHORT values (e.g. entry-number index) into a multi-column block
    let compacted = 0;
    document.querySelectorAll('table').forEach((t) => {
      if (t.style.display === 'none') return;
      const cols = Math.max(0, ...Array.from(t.rows).map((r) => r.cells.length));
      if (cols !== 1) return;
      const vals = Array.from(t.rows).filter((r) => !r.querySelector('th')).map((r) => (r.textContent || '').replace(/\s+/g, ' ').trim()).filter(Boolean);
      if (vals.length < 10) return;
      if (vals.reduce((a, s) => a + s.length, 0) / vals.length > 14) return; // long text -> keep as table
      const head = t.querySelector('th');
      if (head) { const h = document.createElement('div'); h.style.cssText = 'font-weight:700;background:#e8eef6;padding:2px 6px;border:.4pt solid #aab;font-size:8.5pt;'; h.textContent = (head.textContent || '').replace(/\s+/g, ' ').trim(); t.parentNode.insertBefore(h, t); }
      const wrap = document.createElement('div');
      wrap.style.cssText = 'column-count:10;column-gap:8px;font-size:8pt;border:.4pt solid #aab;border-top:none;padding:3px 6px;margin:0 0 6px 0;';
      wrap.innerHTML = vals.map((v) => '<div style="break-inside:avoid;">' + v + '</div>').join('');
      t.parentNode.insertBefore(wrap, t);
      t.style.display = 'none'; compacted++;
    });
    // 5) balance column widths by content length so long-text columns wrap less (shorter rows,
    //    fewer pages) while short columns (entry no / survey no) stay narrow.
    let balanced = 0;
    document.querySelectorAll('table').forEach((t) => {
      if (t.style.display === 'none') return;
      const rows = Array.from(t.rows).filter((r) => r.cells.length);
      if (rows.length < 2) return;
      if (rows.some((r) => Array.from(r.cells).some((c) => (c.colSpan || 1) > 1 || (c.rowSpan || 1) > 1))) return; // keep colspan tables as-is
      const ncol = Math.max(...rows.map((r) => r.cells.length));
      if (ncol < 2 || ncol > 14) return;
      const sum = new Array(ncol).fill(0), cnt = new Array(ncol).fill(0);
      rows.forEach((r) => Array.from(r.cells).forEach((c, i) => { if (i < ncol) { sum[i] += (c.innerText || '').replace(/\s+/g, ' ').trim().length; cnt[i]++; } }));
      const raw = sum.map((s, i) => Math.max(6, Math.min(s / Math.max(1, cnt[i]), 60)));
      const total = raw.reduce((a, b) => a + b, 0);
      const cg = document.createElement('colgroup');
      raw.forEach((x) => { const col = document.createElement('col'); col.style.width = (100 * x / total).toFixed(2) + '%'; cg.appendChild(col); });
      t.insertBefore(cg, t.firstChild); balanced++;
    });
    return { rows, tables, secs, compacted, balanced };
  });

  await page.addStyleTag({ content: `
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
    /* compact the top summary block (District/Taluka/Village/Land Details label:value pairs) */
    .control-label { margin:0 !important; padding:0 2px !important; line-height:1.22 !important; }
    .Div-Border-Side-New > .form-group { margin:0 !important; }
    .panel-heading { padding:3px 6px !important; }
    br { line-height:0.6 !important; }
    a { text-decoration:none !important; color:#000 !important; }
    img[src*="image"] { display:none; }
    .breadcrumb, #myBtn, .alert-danger, header, nav, .navbar, footer, #__bot_banner { display:none !important; }
  ` });

  const summary = await page.evaluate(() => {
    const grab = (re) => (document.body.innerText.match(re) || [, ''])[1]?.trim() || '';
    return {
      area: grab(/Total Area[^:]*:\s*([^\n]+)/i), assessment: grab(/Total Assessment[^:]*:\s*([^\n]+)/i),
      tenure: grab(/Tenure[^:]*:\s*([^\n]+)/i), landUse: grab(/Land Use[^:]*:\s*([^\n]+)/i),
      asOf: (document.body.innerText.match(/તા\.\s*([0-9/]+ [0-9:]+)\s*ની સ્થિતિએ/) || [, ''])[1] || '',
    };
  });
  await page.evaluate(({ survey, s }) => {
    // drop the native stacked summary (District/Taluka/Village + Land Details) — replaced by a compact header
    document.querySelectorAll('.Div-Border-Side-New').forEach((p) => {
      const h = (p.querySelector('.text-success')?.textContent || '').replace(/\s+/g, ' ');
      if (/District \(|Land Details \(|જીલ્લો|જમીનની વિગત/.test(h)) p.style.display = 'none';
    });
    Array.from(document.querySelectorAll('h1,h2,h3,h4,div,span,strong,b,legend')).forEach((e) => {
      if (!e.children.length && /^સરવે નંબરને લગતી સંપૂર્ણ વિગતો/.test((e.textContent || '').replace(/\s+/g, ' ').trim())) e.style.display = 'none';
    });
    document.getElementById('__anyror_title')?.remove();
    const d = document.createElement('div');
    d.id = '__anyror_title';
    d.style.cssText = 'padding:4px 8px 6px;margin:0 0 7px 0;border-bottom:2.5px solid #1f4e78;font-family:Arial,sans-serif;display:block !important;';
    const chip = (label, val) => val ? '<span style="margin-right:16px;white-space:nowrap;">' + label + ': <b>' + val + '</b></span>' : '';
    d.innerHTML =
      '<div style="font-size:15pt;font-weight:800;color:#1f4e78;line-height:1.1;">AnyRoR — Integrated Survey Record</div>' +
      '<div style="font-size:10pt;color:#222;margin-top:3px;">Village <b>Bharoda</b> &middot; Taluka <b>Umreth</b> &middot; District <b>Anand</b>' +
      ' &nbsp;|&nbsp; Survey / Block No <b>' + survey + '</b>' + (s.asOf ? ' &nbsp;|&nbsp; As of ' + s.asOf : '') + '</div>' +
      '<div style="font-size:9.5pt;color:#111;margin-top:4px;line-height:1.5;">' +
        chip('કુલ ક્ષેત્રફળ / Area', s.area) + chip('આકાર / Assessment', s.assessment) +
        chip('સત્તાપ્રકાર / Tenure', s.tenure) + chip('ઉપયોગ / Land Use', s.landUse) + '</div>';
    const anchor = document.querySelector('.panel.panel-primary') || document.querySelector('.panel-primary') || document.body.firstElementChild;
    anchor.parentNode.insertBefore(d, anchor);
  }, { survey, s: summary });

  return { removed, asOf: summary.asOf };
}

export const PDF_OPTS = {
  format: 'A4', landscape: true, printBackground: true,
  margin: { top: '6mm', bottom: '6mm', left: '6mm', right: '6mm' }, scale: 1,
};
