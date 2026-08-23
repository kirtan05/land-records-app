// Lightweight CAPTCHA-reuse probe.
// Uses CheckCaptchaController (fast) to test if the SAME captcha stays valid
// before AND after a heavy ViewSurveyListController search (no get_captcha between).
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { REPO } from '../../core/repo-root.mjs';

const input = JSON.parse(readFileSync(REPO+'/data/catalog/survey-input.json', 'utf8'));
const A = input.find((r) => r.normalized === '226/p1')?.value;

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const p = browser.contexts()[0].pages().find((pg) => /ViewSurveyList/i.test(pg.url()));

const out = await p.evaluate(async ({ A }) => {
  const token = document.querySelector('input[name=_token]').value;
  const captcha = document.querySelector('#txt_captcha').value.trim();
  const ajax = (url, data, timeout) => new Promise((res) => {
    $.ajax({ url, type: 'POST', method: 'POST', data, timeout,
      success: (d) => res({ ok: true, d }), error: (xhr, ts) => res({ ok: false, status: xhr.status, ts }) });
  });
  const base = 'https://ircms.gujarat.gov.in/';
  const check = () => ajax(base + 'CheckCaptchaController', { tc: captcha, _token: token }, 20000);
  const search = (surveyno) => ajax(base + 'ViewSurveyListController', { dist: '15', taluka: '03', village: '029', surveyno, captcha_code: captcha, _token: token }, 120000);
  const vdisp = (r) => (r.ok ? { valid: String(r.d).trim() !== '0', raw: String(r.d).trim().slice(0, 12) } : { error: r.status || r.ts });
  const sdisp = (r) => (r.ok ? (r.d && r.d.success === false ? { success: false, msg: r.d.message } : { success: true, count: Object.values(r.d || {}).filter((v) => v && v.sr_no).length }) : { error: r.status || r.ts });

  const c0 = await check();          // valid before?
  const s1 = await search(A);        // do a real search
  const c1 = await check();          // still valid after the search?
  return { captcha, before: vdisp(c0), search: sdisp(s1), after: vdisp(c1) };
}, { A });

console.log(JSON.stringify(out, null, 2));
console.log(`\n>>> captcha valid BEFORE search: ${out.before.valid} | AFTER search: ${out.after.valid}` +
  `  =>  ${out.before.valid && out.after.valid ? 'REUSE WORKS ✅ (one solve for all)' : 'NO REUSE ❌ (one solve per survey)'}`);
await browser.close();
