// Saves a CLEAN PDF of the AnyRoR survey detail (watermark killed) + extracts data.
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { REPO } from '../core/repo-root.mjs';

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => /InfoSurveyNoDetail/i.test(p.url()));
if (!page) { console.log('detail page not open'); await browser.close(); process.exit(1); }

// kill watermark background-image + any tiled bg; keep table header shading
await page.addStyleTag({ content: `
  .imgwatermark{ background-image:none !important; background:#fff !important; }
  *{ background-image:none !important; }
  table{ background-image:none !important; }
` });

await page.pdf({
  path: REPO+'/anyror/AnyRoR_SurveyNo_221_P_LandRecord.pdf',
  format: 'A4', printBackground: true, margin: { top: '8mm', bottom: '8mm', left: '7mm', right: '7mm' },
});

const data = await page.evaluate(() => {
  const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
  const grab = (re) => (document.body.innerText.match(re) || [, ''])[1]?.trim() || '';
  const tables = Array.from(document.querySelectorAll('table')).map((t) => {
    let label = '', p = t.previousElementSibling;
    for (let i = 0; p && i < 5 && !label; i++, p = p.previousElementSibling) { const x = clean(p.textContent); if (x && x.length < 60) label = x; }
    return { label, rows: Array.from(t.querySelectorAll('tr')).map((tr) => Array.from(tr.cells).map((c) => clean(c.innerText))).filter((r) => r.some((c) => c)) };
  });
  return {
    as_of: grab(/સ્થિતિએ|તા\.([0-9/: ]+)/),
    total_area: grab(/Total Area[^:]*:\s*([^\n]+)/i),
    total_assessment: grab(/Total Assessment[^:]*:\s*([^\n]+)/i),
    tenure: grab(/Tenure[^:]*:\s*([^\n]+)/i),
    land_use: grab(/Land Use[^:]*:\s*([^\n]+)/i),
    section_count: tables.length,
    sections: tables,
    raw_text: clean(document.body.innerText),
  };
});
writeFileSync(REPO+'/anyror/anyror-221p.json', JSON.stringify(data, null, 2));
console.log('saved AnyRoR_SurveyNo_221_P_LandRecord.pdf');
console.log('extracted:', JSON.stringify({ as_of: data.as_of, total_area: data.total_area, total_assessment: data.total_assessment, tenure: data.tenure, land_use: data.land_use, sections: data.sections.length }, null, 2));
console.log('section labels:', data.sections.map((s) => s.label).filter(Boolean).join(' | '));
await browser.close();
