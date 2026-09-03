// Compact, print-ready AnyRoR land-record PDF: landscape, all columns, watermark removed,
// chrome hidden, titled. Removes empty rows/tables/sections and only stacks the TALL
// side-by-side sections (Ownership/Boja/Crop) so it fits in far fewer pages.
//   node make-clean-pdf.mjs <outPath> <surveyLabel>
import { chromium } from 'playwright-core';
import { REPO } from '../core/repo-root.mjs';

const OUTPATH = process.argv[2] || REPO+'/packages/anyror/examples/AnyRoR_221_P_v6.pdf';
const SURVEY = process.argv[3] || '221/p';

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const page = browser.contexts()[0].pages().find((p) => /InfoSurveyNoDetail/i.test(p.url()));
if (!page) { console.log('detail page not open'); await browser.close(); process.exit(1); }

// ---- DOM cleanup: drop empty rows, empty tables, and "Record Not Found" sections ----
const removed = await page.evaluate(() => {
  const blank = (s) => (s || '').replace(/[\s \-—_.]/g, '') === '';
  const isHeadingText = (t) => t && t.length < 70 && /Details|વિગત/i.test(t);
  let rows = 0, tables = 0, secs = 0;
  // 1) remove empty data rows
  document.querySelectorAll('table').forEach((t) => {
    Array.from(t.rows).forEach((r) => { if (!r.querySelector('th') && blank(r.textContent)) { r.remove(); rows++; } });
  });
  // 2) hide tables with no data + their section heading
  document.querySelectorAll('table').forEach((t) => {
    const data = Array.from(t.rows).filter((r) => !r.querySelector('th'));
    if (!data.some((r) => !blank(r.textContent))) {
      t.style.display = 'none'; tables++;
      let p = t.previousElementSibling, hops = 0;
      while (p && hops < 4) { const x = (p.textContent || '').replace(/\s+/g, ' ').trim(); if (isHeadingText(x)) { p.style.display = 'none'; break; } p = p.previousElementSibling; hops++; }
    }
  });
  // 3) hide "Record Not Found" / "---" leaves + their section heading
  Array.from(document.querySelectorAll('div,span,p,td')).forEach((e) => {
    if (e.children.length) return;
    const t = (e.textContent || '').replace(/\s+/g, ' ').trim();
    if (/^Record Not Found\.?$/i.test(t) || t === '---' || t === '----') {
      e.style.display = 'none'; secs++;
      let p = e.previousElementSibling || (e.parentElement && e.parentElement.previousElementSibling), hops = 0;
      while (p && hops < 3) { const x = (p.textContent || '').replace(/\s+/g, ' ').trim(); if (isHeadingText(x)) { p.style.display = 'none'; break; } p = p.previousElementSibling; hops++; }
    }
  });
  return { rows, tables, secs };
});

await page.addStyleTag({ content: `
  html, body { background:#fff !important; }
  * { background-image:none !important; }
  .imgwatermark { background:#fff !important; }
  /* stack ONLY the tall side-by-side content columns (Ownership/Boja=6, Crop=9, etc.) */
  [class*="col-md-4"],[class*="col-lg-4"],[class*="col-md-5"],[class*="col-lg-5"],
  [class*="col-md-6"],[class*="col-lg-6"],[class*="col-md-7"],[class*="col-lg-7"],
  [class*="col-md-8"],[class*="col-lg-8"],[class*="col-md-9"],[class*="col-lg-9"]{
     width:100% !important; max-width:100% !important; float:none !important; display:block !important; }
  /* compact tables: fit all columns, wrap, tight */
  table { width:100% !important; table-layout:fixed !important; border-collapse:collapse !important; margin:2px 0 5px 0 !important; }
  td, th { border:.4pt solid #aab !important; padding:1px 3px !important; font-size:8pt !important;
           line-height:1.16 !important; white-space:normal !important; word-break:break-word !important;
           overflow-wrap:anywhere !important; vertical-align:top !important; }
  th { background:#e8eef6 !important; font-weight:700 !important; }
  tr { page-break-inside:avoid !important; }
  /* tighten panels / spacing */
  .panel, .panel-body, .Div-Border-Side-New, .form-group, .form-horizontal, .bs-example, .card, .container, .row
     { padding:1px !important; margin:1px 0 !important; box-shadow:none !important; min-height:0 !important; }
  .panel-heading { padding:3px 6px !important; }
  br { line-height:0.6 !important; }
  a { text-decoration:none !important; color:#000 !important; }
  img[src*="image"] { display:none; }
  /* hide chrome: breadcrumb, top button, disclaimers, site header/nav */
  .breadcrumb, #myBtn, .alert-danger, header, nav, .navbar, footer { display:none !important; }
` });

const asOf = await page.evaluate(() => (document.body.innerText.match(/તા\.\s*([0-9/]+ [0-9:]+)\s*ની સ્થિતિએ/) || [, ''])[1] || '');
await page.evaluate(({ SURVEY, asOf }) => {
  document.getElementById('__anyror_title')?.remove();
  const d = document.createElement('div');
  d.id = '__anyror_title';
  d.style.cssText = 'padding:4px 8px 6px;margin:0 0 6px 0;border-bottom:2.5px solid #1f4e78;font-family:Arial,sans-serif;display:block !important;';
  d.innerHTML =
    '<div style="font-size:15pt;font-weight:800;color:#1f4e78;line-height:1.1;">AnyRoR — Integrated Survey Record</div>' +
    '<div style="font-size:10pt;color:#222;margin-top:2px;">Village <b>Bharoda</b> &middot; Taluka <b>Umreth</b> &middot; District <b>Anand</b>' +
    ' &nbsp;|&nbsp; Survey / Block No <b>' + SURVEY + '</b>' + (asOf ? ' &nbsp;|&nbsp; As of ' + asOf : '') + '</div>';
  const anchor = document.querySelector('.panel.panel-primary') || document.querySelector('.panel-primary') || document.body.firstElementChild;
  anchor.parentNode.insertBefore(d, anchor);
}, { SURVEY, asOf });

await page.waitForTimeout(400);
await page.pdf({
  path: OUTPATH, format: 'A4', landscape: true, printBackground: true,
  margin: { top: '6mm', bottom: '6mm', left: '6mm', right: '6mm' }, scale: 1,
});
console.log('saved', OUTPATH, '| removed empty:', JSON.stringify(removed), '| as-of:', asOf);
await browser.close();
