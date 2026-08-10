// iRCMS cases for Kheda / Nadiad / Salun Talpad (072). Launches its own headed Chrome.
//   node run-ircms-salun.mjs --only=125
import { chromium } from 'playwright-core';
import { ensureOut, markSurvey } from './src/store.mjs';
import { processSurvey } from './src/scrape.mjs';
import { normalizeSurvey } from './src/normalize.mjs';

const DIST = '16', TAL = '08', VIL = '072';
const URL = 'https://ircms.gujarat.gov.in/ViewSurveyList';
const only = (process.argv.find((a) => a.startsWith('--only=')) || '').split('=')[1] || '125';
const keys = only.split(',').map((s) => normalizeSurvey(s.trim()));

// self-contained (gets stringified and eval'd inside the page)
const norm = (s) => { const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' }; return String(s).replace(/[૦-૯]/g, (c) => GU[c] || c).replace(/પ/g, 'p').toLowerCase().replace(/~~/g, '').replace(/[\s/|\\]+/g, '/').replace(/^\/+|\/+$/g, '').replace(/p\/(?=\d)/g, 'p').trim(); };

ensureOut();
const ctx = await chromium.launchPersistentContext('/home/kirtan/Desktop/projects/irmsc/.chrome-profile-ircms', {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();

const setBanner = (text, color = '#0b5') => page.evaluate(({ text, color }) => {
  let b = document.getElementById('__bot_banner');
  if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + color + ';color:#fff;font:bold 17px sans-serif;padding:11px;text-align:center';
  b.textContent = text;
}, { text, color }).catch(() => {});

async function ensureGeo() {
  const ok = await page.evaluate((v) => document.querySelector('#sel_village')?.value === v && document.querySelector('#sel_survey_no')?.options.length > 1, VIL).catch(() => false);
  if (ok) return;
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 90000 });
  await page.selectOption('#sel_district', DIST);
  await page.waitForFunction(() => document.querySelector('#sel_taluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#sel_taluka', TAL);
  await page.waitForFunction(() => document.querySelector('#sel_village')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#sel_village', VIL);
  const vname = await page.evaluate(() => { const s = document.querySelector('#sel_village'); return s.options[s.selectedIndex]?.textContent.trim(); });
  console.log('  village: ' + vname);
  if (!/સલુ|સાલુ|સાલૂ|salun/i.test(vname)) throw new Error('WRONG VILLAGE: ' + vname);
  await page.waitForFunction(() => document.querySelector('#sel_survey_no')?.options.length > 1, { timeout: 40000 });
}

for (let i = 0; i < keys.length; i++) {
  const key = keys[i], tag = `[${i + 1}/${keys.length}] ${key}`;
  await ensureGeo();
  const val = await page.evaluate(([k, nf]) => {
    const n = eval('(' + nf + ')');
    const o = Array.from(document.querySelector('#sel_survey_no').options).find((o) => n(o.textContent) === k);
    return o ? o.value : null;
  }, [key, norm.toString()]);
  if (!val) { console.log(`${tag} survey not in iRCMS dropdown — skip`); continue; }
  await page.selectOption('#sel_survey_no', val);
  await page.evaluate(() => { const tb = document.querySelector('#surveylist_table tbody'); if (tb) tb.innerHTML = ''; });
  await setBanner(`🤖 ${tag} — type the CAPTCHA and click "View"`, '#0b5');
  console.log(`${tag} prefilled (value=${val}) — waiting for CAPTCHA + View…`);

  let outcome;
  try {
    outcome = await page.waitForFunction(() => {
      const rows = document.querySelectorAll('#surveylist_table tbody tr').length;
      if (rows > 0) return { kind: 'rows', rows };
      const err = (document.querySelector('#errorMsgSurveyList')?.innerText || '').trim();
      if (/no record|not found|no data/i.test(err)) return { kind: 'norecord', err };
      return false;
    }, { timeout: 540000, polling: 1000 }).then((h) => h.jsonValue());
  } catch { outcome = { kind: 'timeout' }; }

  if (outcome.kind === 'rows') {
    await setBanner(`⏳ Scraping ${key} (${outcome.rows} cases)… don't touch the browser`, '#c80');
    console.log(`${tag} ${outcome.rows} rows — scraping…`);
    try { await processSurvey(ctx, page, key, (m) => console.log(m)); }
    catch (e) { console.log(`${tag} scrape error: ${e.message}`); markSurvey(key, { status: 'error', error: e.message }); }
  } else if (outcome.kind === 'norecord') {
    console.log(`${tag} no cases found`); markSurvey(key, { status: 'no_cases' });
    await setBanner(`ℹ️ ${key}: no cases found`, '#888');
  } else {
    console.log(`${tag} TIMEOUT`); await setBanner(`⚠️ Timed out on ${key}. Re-run to resume.`, '#c00'); break;
  }
}
await setBanner('✅ iRCMS done — files in output/. You can close this window.', '#06c');
console.log('IRCMS RUNNER DONE');
await ctx.close();
