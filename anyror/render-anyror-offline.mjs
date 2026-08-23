// Render clean AnyRoR integrated PDFs from each survey's SAVED detail HTML (no re-fetch, no
// banner, proven-clean layout). CSS is pulled from the live server via an injected <base>.
//   node anyror/render-anyror-offline.mjs            # all surveys with saved HTML
//   node anyror/render-anyror-offline.mjs 221_P      # one token
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { applyCleanFormat, PDF_OPTS } from './format.mjs';
import { REPO } from '../src/repo-root.mjs';

const OUT = REPO+'/output';
const STATE = join(OUT, '_anyror_state.json');
const BASE = 'https://anyror.gujarat.gov.in/Information_pages/';
const only = process.argv[2];
const state = JSON.parse(readFileSync(STATE, 'utf8'));

const browser = await chromium.launch({ channel: 'chrome', headless: true });
for (const [key, info] of Object.entries(state)) {
  if (only && info.token !== only && key !== only) continue;
  const htmlPath = join(OUT, info.token, `anyror_${info.token}.html`);
  if (!existsSync(htmlPath)) { console.log(`  ${key.padEnd(11)} no saved HTML — needs re-fetch`); continue; }
  let html = readFileSync(htmlPath, 'utf8');
  if (!/<base /i.test(html)) html = html.replace(/<head([^>]*)>/i, `<head$1><base href="${BASE}">`);
  const localPath = join(OUT, info.token, `.anyror_render_${info.token}.html`);
  writeFileSync(localPath, html);

  const page = await browser.newPage();
  await page.goto('file://' + localPath, { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
  await page.evaluate(() => document.fonts?.ready).catch(() => {});
  await page.waitForTimeout(1200);
  await applyCleanFormat(page, key);
  await page.waitForTimeout(300);
  const pdfPath = join(OUT, info.token, `AnyRoR_SurveyNo_${info.token}_LandRecord.pdf`);
  await page.pdf({ path: pdfPath, ...PDF_OPTS });
  const pages = (await page.evaluate(() => document.querySelectorAll('table').length));
  await page.close();
  console.log(`  ${key.padEnd(11)} -> ${pdfPath.split('/').pop()}  (${pages} tables)`);
}
await browser.close();
console.log('ANYROR OFFLINE RENDER DONE');
