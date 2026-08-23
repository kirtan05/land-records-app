// Ad-hoc headed AnyRoR Integrated fetch. One gentle load (no retry bursts). Auto-finds the taluka
// that contains the village, pre-fills the cascade, you solve the CAPTCHA in the window, then it saves
// raw HTML + a clean PDF and keeps the browser open on CDP :9222 for follow-ups (VF-7/12).
//   env DISPLAY=... node packages/anyror/fetch-integrated-adhoc.mjs
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { applyCleanFormat, PDF_OPTS } from './format.mjs';
import { REPO } from '../core/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const OUT = REPO+'/output';
const STATE = join(OUT, '_anyror_state.json');
const STOP = REPO+'/anyror/deed-stop.txt';

// ---- target ----
const TYPE = '8';                      // Integrated Survey Details
const DISTRICT_RE = /ખેડા/;            // Kheda
const VILLAGE_RE = /સાલૂન|salun/i;     // Salun (Talpad)
const SURVEY = '125';
const KEY = 'salun/125';
const TOKEN = 'Salun_125';
const DIR = join(OUT, TOKEN);
mkdirSync(DIR, { recursive: true });

const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const deGu = (s) => String(s).replace(/[૦-૯]/g, (c) => GU[c] || c);
const readState = () => (existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {});

const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror', {
  channel: 'chrome', headless: false, viewport: null, acceptDownloads: true,
  args: ['--remote-debugging-port=9222', '--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();
const banner = (t, c = '#0b5') => page.evaluate(({ t, c }) => { let b = document.getElementById('__bot_banner'); if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); } b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + c + ';color:#fff;font:bold 16px sans-serif;padding:10px;text-align:center'; b.textContent = t; }, { t, c }).catch(() => {});

// ONE load, no burst
await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 45000 });
await page.waitForTimeout(1500);
await page.selectOption('#ContentPlaceHolder1_drpLandRecord', TYPE);
await page.waitForTimeout(2500);

// district
const distVal = await page.evaluate((re) => { const rx = new RegExp(re); const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlDistrict').options).find((o) => rx.test(o.textContent)); return o ? o.value : null; }, DISTRICT_RE.source);
if (!distVal) throw new Error('district (Kheda) not found');
await page.selectOption('#ContentPlaceHolder1_ddlDistrict', distVal);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
console.log('district set. scanning talukas for the village…');

// scan talukas to find the one containing the village
const talukas = await page.$$eval('#ContentPlaceHolder1_ddlTaluka option', (os) => os.map((o) => ({ v: o.value, t: o.textContent.trim() })).filter((o) => o.v && o.v !== '0' && o.v !== '-1'));
let found = null;
for (const tk of talukas) {
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', tk.v);
  try { await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 20000 }); } catch { continue; }
  const vil = await page.evaluate((re) => { const rx = new RegExp(re, 'i'); const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlVillage').options).find((o) => rx.test(o.textContent)); return o ? { v: o.value, t: o.textContent.trim() } : null; }, VILLAGE_RE.source);
  if (vil) { found = { taluka: tk, village: vil }; break; }
  await page.waitForTimeout(400); // gentle
}
if (!found) throw new Error('village Salun not found in any Kheda taluka');
console.log('FOUND taluka =', found.taluka.t, '| village =', found.village.t);
await page.selectOption('#ContentPlaceHolder1_ddlVillage', found.village.v);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });

// survey 125
const surveyVal = await page.evaluate((deGuStr) => { const GU = JSON.parse(deGuStr); const de = (s) => String(s).replace(/[૦-૯]/g, (c) => GU[c] || c); const opts = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options); const o = opts.find((o) => de(o.textContent).replace(/[^0-9/p]/gi, '') === '125'); return o ? o.value : null; }, JSON.stringify(GU));
if (!surveyVal) { const all = await page.$$eval('#ContentPlaceHolder1_ddlSurveyNo option', (os) => os.map((o) => o.textContent.trim()).filter(Boolean).slice(0, 40)); console.log('survey 125 not found. options:', JSON.stringify(all)); throw new Error('survey 125 not in dropdown'); }
await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', surveyVal);
console.log('cascade ready: Kheda /', found.taluka.t, '/', found.village.t, '/ survey 125');
await banner('🤖 Salun 125 — type the CAPTCHA and click "Get Record Detail"', '#0b5');
console.log('WAITING for you to solve CAPTCHA + Get Record Detail…');

const ok = await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 9 * 60 * 1000 }).then(() => true).catch(() => false);
if (!ok) { console.log('did not reach detail (wrong captcha?). Browser stays open.'); }
else {
  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForTimeout(2000);
  await banner('⏳ Saving Salun 125…', '#c80');
  const htmlPath = join(DIR, `anyror_${TOKEN}.html`);
  writeFileSync(htmlPath, await page.content());
  const data = await page.evaluate(() => { const grab = (re) => (document.body.innerText.match(re) || [, ''])[1]?.trim() || ''; return { total_area: grab(/Total Area[^:]*:\s*([^\n]+)/i), total_assessment: grab(/Total Assessment[^:]*:\s*([^\n]+)/i), tenure: grab(/Tenure[^:]*:\s*([^\n]+)/i), land_use: grab(/Land Use[^:]*:\s*([^\n]+)/i), as_of: (document.body.innerText.match(/તા\.\s*([0-9/]+ [0-9:]+)\s*ની સ્થિતિએ/) || [, ''])[1] || '' }; });
  writeFileSync(join(DIR, `anyror_${TOKEN}.json`), JSON.stringify({ survey: KEY, token: TOKEN, taluka: found.taluka.t, village: found.village.t, ...data }, null, 2));
  const pdfPath = join(DIR, `AnyRoR_SurveyNo_${TOKEN}_LandRecord.pdf`);
  try { await applyCleanFormat(page, KEY); await page.waitForTimeout(300); await page.pdf({ path: pdfPath, ...PDF_OPTS }); } catch (e) { console.log('pdf err:', e.message); }
  const s = readState(); s[KEY] = { done: true, token: TOKEN, pdf: pdfPath, json: join(DIR, `anyror_${TOKEN}.json`), html: htmlPath, area: data.total_area, tenure: data.tenure, land_use: data.land_use, as_of: data.as_of, taluka: found.taluka.t, village: found.village.t, at: new Date().toISOString() }; writeFileSync(STATE, JSON.stringify(s, null, 2));
  await banner('✅ Integrated record saved. (VF-7/12 + cases next)', '#06c');
  console.log('SAVED integrated ->', pdfPath, '| area', data.total_area);
}
// keep session alive for the follow-ups (VF-7/12) — touch deed-stop.txt to close
console.log('BROWSER_OPEN_ON_CDP_9222 (touch packages/anyror/deed-stop.txt to close)');
for (let i = 0; i < 300 && !existsSync(STOP); i++) await page.waitForTimeout(5000);
await ctx.close();
