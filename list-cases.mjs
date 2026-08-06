// Parses the live #surveylist_table into structured case rows (incl. view-button data attrs).
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => /ViewSurveyList/i.test(p.url())) || ctx.pages()[0];

const cases = await page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('#surveylist_table tbody tr'));
  return rows.map((tr) => {
    const tds = Array.from(tr.cells).map((td) => td.innerText.replace(/\s+/g, ' ').trim());
    const btn = tr.querySelector('.view_status');
    const caseStr = tds[1] || '';
    const statusMatch = caseStr.match(/\(([^)]+)\)/);
    return {
      sr_no: tds[0],
      case_str: caseStr,
      status: statusMatch ? statusMatch[1] : '',
      office: tds[2],
      dtv: tds[3],
      survey: tds[4],
      parties: tds[5],
      data_id: btn?.getAttribute('data-id')?.trim(),
      offcode: btn?.getAttribute('data-offcode')?.trim(),
      survno: btn?.getAttribute('data-survno'),
    };
  });
});

writeFileSync('./cases-221p.json', JSON.stringify(cases, null, 2));
console.log('rows:', cases.length);
for (const c of cases) console.log(`${String(c.sr_no).padStart(2)} | ${(c.status || '?').padEnd(9)} | id=${c.data_id} off=${c.offcode} | ${c.case_str.slice(0, 60)}`);
const byStatus = cases.reduce((m, c) => ((m[c.status] = (m[c.status] || 0) + 1), m), {});
console.log('\nstatus counts:', JSON.stringify(byStatus));
await browser.close();
