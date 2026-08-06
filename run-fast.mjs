// ONE-CAPTCHA runner: reuses the single solved CAPTCHA in the live page to fetch every
// remaining survey via AJAX and scrape all cases by key. No further solves needed.
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { ensureOut, isDone, markSurvey } from './src/store.mjs';
import { fetchCaseListAjax, processCases, openDetailByKey } from './src/scrape.mjs';

const targets = JSON.parse(readFileSync('./survey-input.json', 'utf8')).filter((r) => r.matched);

ensureOut();
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => /ViewSurveyList/i.test(p.url())) || ctx.pages()[0];

const setBanner = (text, color = '#06c') => page.evaluate(({ text, color }) => {
  let b = document.getElementById('__bot_banner'); if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + color + ';color:#fff;font:bold 17px sans-serif;padding:11px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.3)';
  b.textContent = text;
}, { text, color }).catch(() => {});

const checkCaptcha = () => page.evaluate(async () => {
  const token = document.querySelector('input[name=_token]').value;
  const captcha = document.querySelector('#txt_captcha').value.trim();
  if (!captcha) return { ok: false, raw: 'empty' };
  const r = await new Promise((res) => $.ajax({ url: 'https://ircms.gujarat.gov.in/CheckCaptchaController', type: 'POST', data: { tc: captcha, _token: token }, timeout: 20000, success: (d) => res(String(d).trim()), error: () => res('ERR') }));
  return { ok: r !== '0' && r !== 'ERR', raw: r, captcha };
});

const c = await checkCaptcha();
console.log('captcha check:', JSON.stringify(c));
if (!c.ok) {
  await setBanner('⚠️ CAPTCHA no longer valid — type a fresh one (do NOT click View) and tell Claude to resume.', '#c00');
  console.log('CAPTCHA_INVALID'); await browser.close(); process.exit(2);
}

let stop = false;
for (let i = 0; i < targets.length && !stop; i++) {
  const key = targets[i].normalized, val = targets[i].value, tag = `[${i + 1}/${targets.length}] ${key}`;
  if (isDone(key)) { console.log(`${tag} already done — skip`); continue; }
  await setBanner(`⚡ Auto-processing ${key} with your single CAPTCHA… (${i + 1}/${targets.length})`, '#0a6');
  console.log(`${tag} fetching case list…`);
  const res = await fetchCaseListAjax(page, val, (m) => console.log(m));
  if (res.error === 'message') {
    if (/captcha/i.test(res.message)) {
      console.log(`${tag} CAPTCHA expired mid-run: ${res.message}`);
      await setBanner('⚠️ CAPTCHA expired — type a fresh one (no View) and tell Claude to resume.', '#c00');
      stop = true; break;
    }
    console.log(`${tag} no records (${res.message})`); markSurvey(key, { status: 'no_cases', message: res.message }); continue;
  }
  if (res.error) { console.log(`${tag} search failed (${res.error}) after retries — leaving for retry`); markSurvey(key, { status: 'search_failed' }); continue; }
  console.log(`${tag} ${res.cases.length} cases — scraping…`);
  try { await processCases(ctx, page, key, res.cases, (cc) => openDetailByKey(ctx, page, cc), (m) => console.log(m)); }
  catch (e) { console.log(`${tag} scrape error: ${e.message}`); markSurvey(key, { status: 'error', error: e.message }); }
}
if (!stop) { await setBanner('✅ All surveys done with ONE CAPTCHA! Files saved in output/. You can close this window.', '#06c'); console.log('ALL DONE'); }
await browser.close();
