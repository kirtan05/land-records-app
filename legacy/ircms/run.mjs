// Orchestrates all matched survey numbers in the live Chrome.
// For each: pre-fills the form, you solve the CAPTCHA + click View, it auto-detects
// the results and scrapes (case PDFs + order PDFs + JSON/CSV), then advances.
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { ensureOut, isDone, markSurvey } from './src/store.mjs';
import { processSurvey } from './src/scrape.mjs';

const ANAND = '15', UMRETH = '03', BHARODA = '029';
const URL = 'https://ircms.gujarat.gov.in/ViewSurveyList';
const input = JSON.parse(readFileSync('./survey-input.json', 'utf8'));
const targets = input.filter((r) => r.matched); // {normalized, value}

ensureOut();
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => /ViewSurveyList/i.test(p.url())) || ctx.pages()[0];

const setBanner = (text, color = '#0b5') =>
  page.evaluate(({ text, color }) => {
    let b = document.getElementById('__bot_banner');
    if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
    b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + color + ';color:#fff;font:bold 17px sans-serif;padding:11px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.3)';
    b.textContent = text;
  }, { text, color }).catch(() => {});

async function ensureGeo() {
  const ok = await page.evaluate(() =>
    document.querySelector('#sel_village')?.value === '029' && document.querySelector('#sel_survey_no')?.options.length > 1
  ).catch(() => false);
  if (ok) return;
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.selectOption('#sel_district', ANAND);
  await page.waitForFunction(() => document.querySelector('#sel_taluka')?.options.length > 1, { timeout: 20000 });
  await page.selectOption('#sel_taluka', UMRETH);
  await page.waitForFunction(() => document.querySelector('#sel_village')?.options.length > 1, { timeout: 20000 });
  await page.selectOption('#sel_village', BHARODA);
  await page.waitForFunction(() => document.querySelector('#sel_survey_no')?.options.length > 1, { timeout: 25000 });
}

for (let i = 0; i < targets.length; i++) {
  const key = targets[i].normalized;
  const tag = `[${i + 1}/${targets.length}] ${key}`;
  if (isDone(key)) { console.log(`${tag} already done — skip`); continue; }

  await ensureGeo();
  await page.selectOption('#sel_survey_no', targets[i].value);
  await page.evaluate(() => { const tb = document.querySelector('#surveylist_table tbody'); if (tb) tb.innerHTML = ''; });
  await setBanner(`🤖 ${tag} — type the CAPTCHA and click "View"`, '#0b5');
  console.log(`${tag} loaded — waiting for CAPTCHA + View…`);

  let outcome;
  try {
    outcome = await page.waitForFunction(() => {
      const rows = document.querySelectorAll('#surveylist_table tbody tr').length;
      if (rows > 0) return { kind: 'rows', rows };
      const err = (document.querySelector('#errorMsgSurveyList')?.innerText || '').trim();
      if (/no record|not found|no data/i.test(err)) return { kind: 'norecord', err };
      return false;
    }, { timeout: 360000, polling: 1000 }).then((h) => h.jsonValue());
  } catch { outcome = { kind: 'timeout' }; }

  if (outcome.kind === 'rows') {
    await setBanner(`⏳ Scraping ${key} (${outcome.rows} cases)… please wait, don't touch the browser`, '#c80');
    console.log(`${tag} results loaded (${outcome.rows} rows) — scraping…`);
    try { await processSurvey(ctx, page, key, (m) => console.log(m)); }
    catch (e) { console.log(`${tag} scrape error: ${e.message}`); markSurvey(key, { status: 'error', error: e.message }); }
  } else if (outcome.kind === 'norecord') {
    console.log(`${tag} no records found`); markSurvey(key, { status: 'no_cases' });
    await setBanner(`ℹ️ ${key}: no cases found — moving on`, '#888');
    await page.waitForTimeout(1500);
  } else {
    console.log(`${tag} TIMEOUT waiting for user`); markSurvey(key, { status: 'timeout' });
    await setBanner(`⚠️ Timed out on ${key}. Re-run "node run.mjs" to resume.`, '#c00');
    break;
  }
}
await setBanner('✅ All survey numbers processed — files saved in output/. You can close this window.', '#06c');
console.log('ORCHESTRATOR DONE');
await browser.close();
