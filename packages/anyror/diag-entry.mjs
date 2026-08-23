import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync } from 'node:fs';
import { applyCleanFormat, PDF_OPTS } from './format.mjs';
import { REPO } from '../core/repo-root.mjs';

let html = readFileSync(REPO+'/anyror/detail-page.html', 'utf8');
if (!/<base /i.test(html)) html = html.replace(/<head([^>]*)>/i, '<head$1><base href="https://anyror.gujarat.gov.in/Information_pages/">');
writeFileSync(REPO+'/anyror/detail-local.html', html);

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
await page.goto('file://'+REPO+'/anyror/detail-local.html', { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
await page.waitForTimeout(1500);

const dump = (label) => page.evaluate((label) => {
  const t = document.getElementById('ContentPlaceHolder1_grdcmputerentry');
  if (!t) return { label, missing: true };
  const hdr = t.rows[0] ? Array.from(t.rows[0].cells).map((c) => ({ txt: c.innerText.replace(/\s+/g, ' ').trim().slice(0, 24), w: Math.round(c.getBoundingClientRect().width) })) : [];
  const r1 = t.rows[1] ? Array.from(t.rows[1].cells).map((c) => ({ n: c.cellIndex, w: Math.round(c.getBoundingClientRect().width), txt: c.innerText.replace(/\s+/g, ' ').trim().slice(0, 20) })) : [];
  return { label, rows: t.rows.length, cols0: t.rows[0]?.cells.length, tableLayout: getComputedStyle(t).tableLayout, tableW: Math.round(t.getBoundingClientRect().width), header: hdr, row1: r1 };
}, label);

console.log('BEFORE:', JSON.stringify(await dump('before'), null, 1));
await applyCleanFormat(page, '221/p');
await page.waitForTimeout(300);
console.log('AFTER: ', JSON.stringify(await dump('after'), null, 1));
await page.pdf({ path: REPO+'/anyror/diag_221P.pdf', ...PDF_OPTS });
console.log('rendered diag_221P.pdf');
await browser.close();
