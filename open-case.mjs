// Opens the Nth case detail (new tab via viewnewcasestatus), captures structure,
// screenshot, and TESTS pdf generation in headed mode. Leaves list page intact.
//   node open-case.mjs 1
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';

const N = parseInt(process.argv[2] || '1', 10);
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const list = ctx.pages().find((p) => /ViewSurveyList/i.test(p.url())) || ctx.pages()[0];

const [detail] = await Promise.all([
  ctx.waitForEvent('page', { timeout: 30000 }),
  list.locator('#surveylist_table tbody tr').nth(N - 1).locator('.view_status').click(),
]);
await detail.waitForLoadState('domcontentloaded').catch(() => {});
await detail.waitForTimeout(3500);
console.log('detail url:', detail.url(), '| title:', await detail.title());

const info = await detail.evaluate(() => {
  const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
  const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,th,legend,.panel-heading,.card-header,b,strong'))
    .map((e) => clean(e.textContent)).filter((t) => t && t.length < 60);
  const tables = Array.from(document.querySelectorAll('table')).map((t, i) => ({ i, id: t.id, rows: t.rows.length }));
  // anything that looks like an order/download link
  const dl = Array.from(document.querySelectorAll('a,button,input[type=button],input[type=submit]')).map((e) => ({
    tag: e.tagName, text: clean(e.textContent) || e.value, href: e.getAttribute('href'),
    onclick: clean(e.getAttribute('onclick')), cls: e.className,
  })).filter((x) => /order|download|view|pdf|\.pdf/i.test((x.text || '') + ' ' + (x.href || '') + ' ' + (x.onclick || '') + ' ' + (x.cls || '')));
  const orderSection = clean((document.body.innerText.match(/Order Details[\s\S]{0,400}/i) || [''])[0]);
  return { tableCount: tables.length, tables, headingsSample: [...new Set(headings)].slice(0, 40), downloadish: dl.slice(0, 25), orderSection };
});
console.log(JSON.stringify(info, null, 2));

writeFileSync(`./detail-${N}.html`, await detail.content());
await detail.screenshot({ path: `./detail-${N}.png`, fullPage: true });
let pdfOk = false, pdfErr = null;
try { await detail.pdf({ path: `./detail-${N}.pdf`, format: 'A4', printBackground: true }); pdfOk = true; }
catch (e) { pdfErr = e.message; }
console.log('\nsaved detail-' + N + '.html / .png | pdf headed:', pdfOk ? 'OK' : 'FAILED -> ' + pdfErr);

await detail.close();
await browser.close();
