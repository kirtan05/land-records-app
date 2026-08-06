// AnyRoR orchestrator: for each survey, auto-fill the cascade, the user solves 1 CAPTCHA +
// clicks "Get Record Detail", then save a clean land-record PDF + JSON into output/<token>/.
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { surveyToken } from '../src/normalize.mjs';
import { applyCleanFormat, PDF_OPTS } from './format.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const OUT = '/home/kirtan/Desktop/projects/irmsc/output';
const STATE = join(OUT, '_anyror_state.json');
const LAND = '8', DIST = '15', TAL = '03', VIL = '029';
const targets = JSON.parse(readFileSync('/home/kirtan/Desktop/projects/irmsc/survey-input.json', 'utf8')).filter((r) => r.matched);

const readState = () => (existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {});
const writeState = (s) => writeFileSync(STATE, JSON.stringify(s, null, 2));

const LAUNCH = process.argv.includes('--launch');
let browser, ctx, page;
if (LAUNCH) {
  // launch a FRESH Chrome (picks up current network = VPN); own profile to avoid lock clashes
  ctx = await chromium.launchPersistentContext('/home/kirtan/Desktop/projects/irmsc/.chrome-profile-anyror', {
    channel: 'chrome', headless: false, viewport: null,
    args: ['--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
  });
  page = ctx.pages()[0] || await ctx.newPage();
} else {
  browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  ctx = browser.contexts()[0];
  page = ctx.pages().find((p) => /anyror\.gujarat/i.test(p.url())) || await ctx.newPage();
}

const setBanner = (text, color = '#0b5') => page.evaluate(({ text, color }) => {
  let b = document.getElementById('__bot_banner'); if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + color + ';color:#fff;font:bold 16px sans-serif;padding:10px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.3)';
  b.textContent = text;
}, { text, color }).catch(() => {});

async function gotoForm() {
  for (let a = 1; a <= 3; a++) {
    try { await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 45000 }); return; }
    catch (e) { if (a === 3) { const err = new Error('BLOCKED: ' + e.message.split('\n')[0]); err.blocked = true; throw err; } await page.waitForTimeout(8000); }
  }
}

async function cascade(normKey) {
  await gotoForm();
  await page.waitForTimeout(1500);
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', LAND);
  await page.waitForTimeout(2500);
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlVillage', VIL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
  const val = await page.evaluate((nk) => {
    const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
    const norm = (s) => String(s).replace(/~~/g, ' ').replace(/[૦-૯]/g, (c) => GU[c] || c).replace(/પ/g, 'p').toLowerCase().replace(/[\s/|\\]+/g, '/').replace(/^\/+|\/+$/g, '').replace(/p\/(?=\d)/g, 'p');
    const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options).find((o) => norm(o.textContent) === nk);
    return o ? o.value : null;
  }, normKey);
  if (!val) throw new Error('survey not in dropdown: ' + normKey);
  await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', val);
  return val;
}

async function waitForDetail() {
  // Just wait for the detail page. The form's ViewState keeps the cascade selections across
  // a wrong-CAPTCHA postback, so we must NOT reload/re-fill (that would wipe the user's solve).
  try { await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 9 * 60 * 1000 }); return true; }
  catch { return false; }
}

async function extractData(page) {
  return page.evaluate(() => {
    const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const grab = (re) => (document.body.innerText.match(re) || [, ''])[1]?.trim() || '';
    const tables = Array.from(document.querySelectorAll('table')).map((t) => {
      let label = '', p = t.previousElementSibling;
      for (let i = 0; p && i < 5 && !label; i++, p = p.previousElementSibling) { const x = clean(p.textContent); if (x && x.length < 60) label = x; }
      return { label, rows: Array.from(t.querySelectorAll('tr')).map((tr) => Array.from(tr.cells).map((c) => clean(c.innerText))).filter((r) => r.some((c) => c)) };
    });
    return {
      total_area: grab(/Total Area[^:]*:\s*([^\n]+)/i), total_assessment: grab(/Total Assessment[^:]*:\s*([^\n]+)/i),
      tenure: grab(/Tenure[^:]*:\s*([^\n]+)/i), land_use: grab(/Land Use[^:]*:\s*([^\n]+)/i),
      as_of: grab(/તા\.\s*([0-9/]+ [0-9:]+)\s*ની સ્થિતિએ/), sections: tables, raw_text: clean(document.body.innerText),
    };
  });
}

for (let i = 0; i < targets.length; i++) {
  const key = targets[i].normalized, token = surveyToken(key), tag = `[${i + 1}/${targets.length}] ${key}`;
  const st = readState();
  if (st[key]?.done) { console.log(`${tag} already done — skip`); continue; }

  console.log(`${tag} filling cascade…`);
  try { await cascade(key); }
  catch (e) {
    if (e.blocked) { console.log(`${tag} ${e.message} — stopping (IP rate-limited). Re-run to resume.`); await setBanner('⚠️ AnyRoR blocked our IP (rate limit). Waiting to retry — nothing to do.', '#c00'); break; }
    console.log(`${tag} cascade error: ${e.message}`); continue;
  }
  await page.waitForTimeout(3000); // be gentle between surveys
  const banner = `🤖 AnyRoR ${tag} — type the CAPTCHA and click "Get Record Detail"`;
  await setBanner(banner, '#0b5');
  console.log(`${tag} waiting for CAPTCHA + Get Record Detail…`);

  const ok = await waitForDetail();
  if (!ok) { console.log(`${tag} TIMEOUT`); await setBanner(`⚠️ Timed out on ${key}. Re-run to resume.`, '#c00'); break; }

  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForTimeout(2500);
  await setBanner(`⏳ Saving ${key}… please wait`, '#c80');
  mkdirSync(join(OUT, token), { recursive: true });
  const pdfPath = join(OUT, token, `AnyRoR_SurveyNo_${token}_LandRecord.pdf`);
  const jsonPath = join(OUT, token, `anyror_${token}.json`);
  const htmlPath = join(OUT, token, `anyror_${token}.html`);
  try {
    const data = await extractData(page);                 // data for JSON / Excel summary
    writeFileSync(htmlPath, await page.content());        // raw HTML -> faithful clean render (final PDF built offline)
    writeFileSync(jsonPath, JSON.stringify({ survey: key, token, ...data }, null, 2));
    const s = readState();
    s[key] = { done: true, token, pdf: pdfPath, json: jsonPath, html: htmlPath, area: data.total_area, tenure: data.tenure, land_use: data.land_use, as_of: data.as_of, at: new Date().toISOString() };
    writeState(s);
    console.log(`${tag} ✓ saved HTML+JSON -> output/${token}/ (area ${data.total_area})`);
  } catch (e) { console.log(`${tag} save error: ${e.message}`); }
}
await setBanner('✅ AnyRoR land records done! Files in output/. You can close this window.', '#06c');
console.log('ANYROR ORCHESTRATOR DONE');
if (browser) await browser.close(); else await ctx.close();
