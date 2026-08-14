// DIAGNOSTIC: why does firing some entries' Select$N leave the PREVIOUS entry's scan on screen?
//
// Hypothesis under test: run-anyror-auto.mjs reads the entry grid ONCE on the fresh detail page,
// then fires those indices at pages that have already re-rendered. If the re-rendered grid's
// index→entry mapping differs, a later Select$N selects a DIFFERENT (possibly already-shown)
// entry — which would look exactly like "the page didn't re-render".
//
// So: dump the index→number mapping before any postback, then again after each one, and compare.
//   node tools/captcha/diag-entry-stale.mjs --village=ભાલેજ --surveys=174/p1
import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { createInterface } from 'node:readline';

const ROOT = '/home/kirtan/Desktop/projects/irmsc';
const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const VILLAGE = arg('village', 'ભાલેજ'), SURVEY = arg('surveys', '174/p1');
const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };

const py = spawn(join(ROOT, 'tools/captcha/.venv/bin/python'),
  [join(ROOT, 'tools/captcha/infer_anyror.py'), '--serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
const lines = createInterface({ input: py.stdout });
const pending = [];
lines.on('line', (l) => pending.shift()?.(l));
const retry = async (fn) => { try { return await fn(); } catch { await page.waitForTimeout(1500); return fn(); } };
const solve = (b64) => new Promise((r) => { pending.push(r); py.stdin.write(`b64:${b64}\n`); })
  .then((l) => l.split('\t')[0]);

const ctx = await chromium.launchPersistentContext(join(ROOT, '.chrome-profile-anyror-auto'), {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
page.on('dialog', (d) => d.dismiss().catch(() => {}));

// ---- reach the detail page ----
await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(1800);
await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '8');
await page.waitForTimeout(2200);
await page.selectOption('#ContentPlaceHolder1_ddlDistrict', '15');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlTaluka', '03');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
await page.waitForTimeout(1200);
const vv = await retry(() => page.evaluate((w) => (Array.from(document.querySelector('#ContentPlaceHolder1_ddlVillage').options)
  .find((o) => o.value === w || (o.textContent || '').includes(w)) || {}).value || '', VILLAGE));
await page.selectOption('#ContentPlaceHolder1_ddlVillage', vv);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
await page.waitForTimeout(1200);
const sv = await retry(() => page.evaluate(({ want, GU }) => {
  const norm = (s) => String(s).replace(/[૦-૯]/g, (c) => GU[c] || c).replace(/પૈકી/g, 'p').replace(/પ/g, 'p')
    .toLowerCase().replace(/[\s/|\\]+/g, '/').replace(/^\/+|\/+$/g, '').replace(/p\/(?=\d)/g, 'p').replace(/\/p/g, 'p');
  const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options).find((o) => norm(o.textContent) === norm(want));
  return o ? o.value : '';
}, { want: SURVEY, GU }));
await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', sv);
await page.waitForTimeout(1200);

const cap = await page.evaluate(() => (document.querySelector('#ContentPlaceHolder1_i_captcha_1')?.src || '').replace(/^data:image\/\w+;base64,/, ''));
const code = await solve(cap);
await page.fill('#ContentPlaceHolder1_txtCaptcha_1, input[id*="captcha" i][type="text"]', code);
await page.click('#ContentPlaceHolder1_btnGo', { noWaitAfter: true, timeout: 20000 });
await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 90000 }).catch(() => {});
await page.waitForTimeout(2000);
if (!/InfoSurveyNoDetail/i.test(page.url())) { console.log('captcha rejected — rerun'); await ctx.close(); process.exit(1); }
console.log(`on detail page (captcha ${code})\n`);

const mapping = () => page.evaluate(() => {
  const g = document.getElementById('ContentPlaceHolder1_gvEntryResult');
  if (!g) return { rows: [], missing: true };
  return {
    rows: Array.from(g.querySelectorAll("a[href*='Select$']")).map((a) => ({
      i: +a.getAttribute('href').match(/Select\$(\d+)/)[1],
      n: (a.textContent || '').trim(),
      red: /Red/i.test((a.getAttribute('style') || '').replace(/\s/g, '')),
    })),
    lbl: (document.getElementById('ContentPlaceHolder1_lblEntryNo')?.innerText || '').replace(/[^0-9]/g, ''),
    eno: (document.querySelector('#ContentPlaceHolder1_gvImages img')?.src || '').match(/[?&]eno=([^&]+)/i)?.[1] || '',
  };
});

// NB: __doPostBack cannot be called from page.evaluate — Playwright's evaluate is strict mode and
// ASP.NET's PageRequestManager._doPostBack reads `arguments.callee`, which throws there. Post the
// form directly instead (this is what the real runner's fallback path was already doing).
const fire = async (i) => {
  await page.evaluate((x) => {
    const f = document.forms[0];
    f.__EVENTTARGET.value = 'ctl00$ContentPlaceHolder1$gvEntryResult';
    f.__EVENTARGUMENT.value = 'Select$' + x;
    f.submit();
  }, i);
  await page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {});
  await page.waitForTimeout(3000);
  return mapping();
};

const base = await mapping();
const sig = (m) => m.rows.map((r) => `${r.i}:${r.n}`).join(',');
console.log(`baseline grid: ${base.rows.length} rows`);
console.log(`  ${base.rows.map((r) => `${r.i}→${r.n}${r.red ? '*' : ''}`).join(' ')}\n`);

// ---- DECISIVE TEST: fire a known-stale entry FIRST, on a page where nothing has rendered yet.
// If it renders → our sequencing is at fault. If nothing renders (no gvImages at all) → that
// entry simply has no scan server-side and the page keeps whatever was there before.
const FIRST = arg('first', '2536');
const firstRow = base.rows.find((r) => r.n === FIRST);
if (firstRow) {
  console.log(`── decisive test: fire ${FIRST} (index ${firstRow.i}) FIRST, nothing rendered yet`);
  const m = await fire(firstRow.i);
  const imgs = await page.evaluate(() => document.querySelectorAll('#ContentPlaceHolder1_gvImages img').length);
  console.log(`   → lbl=${m.lbl || '—'} eno=${m.eno || '—'} imgCount=${imgs}`);
  console.log(`   verdict: ${m.lbl === FIRST ? '✅ renders when fired first → SEQUENCING BUG'
    : imgs === 0 ? '❌ no images at all → entry has NO SCAN server-side'
    : '❓ shows something else — investigate'}\n`);
}

// Reproduce the failing sequence from the real run, checking the mapping at every step.
const red = base.rows.filter((r) => r.red);
const KNOWN_STALE = ['2536', '3125', '3127', '3674'];
let prev = base;
for (const r of red) {
  const after = await fire(r.i);
  const drifted = sig(after) !== sig(prev);
  const got = after.lbl || after.eno;
  const ok = got === r.n;
  const flag = KNOWN_STALE.includes(r.n) ? ' [known-stale]' : '';
  console.log(`fire Select$${r.i} (want ${r.n}) → lbl=${after.lbl || '—'} eno=${after.eno || '—'} ${ok ? 'OK' : 'MISMATCH'}${flag}`);
  if (drifted) {
    console.log(`   ⚠ GRID MAPPING CHANGED after this postback:`);
    console.log(`     was: ${sig(prev).slice(0, 200)}`);
    console.log(`     now: ${sig(after).slice(0, 200)}`);
  }
  if (!ok) {
    // what does THIS page think index r.i is now?
    const nowIs = after.rows.find((x) => x.i === r.i);
    console.log(`   index ${r.i} on the CURRENT page = entry ${nowIs ? nowIs.n : '(absent)'}`);
    const wantRow = after.rows.find((x) => x.n === r.n);
    console.log(`   entry ${r.n} on the CURRENT page = index ${wantRow ? wantRow.i : '(absent)'}`);
    if (wantRow && wantRow.i !== r.i) {
      const retry = await fire(wantRow.i);
      console.log(`   RETRY with current index ${wantRow.i} → lbl=${retry.lbl || '—'} eno=${retry.eno || '—'} ${(retry.lbl || retry.eno) === r.n ? '✅ FIXED' : '❌ still stale'}`);
      prev = retry;
      continue;
    }
    // same index, still stale → re-fire it once to rule out a slow/dropped postback
    const again = await fire(r.i);
    console.log(`   RE-FIRE same index → lbl=${again.lbl || '—'} eno=${again.eno || '—'} ${(again.lbl || again.eno) === r.n ? '✅ worked 2nd time' : '❌ still stale'}`);
    prev = again;
    continue;
  }
  prev = after;
}

py.stdin.end();
await ctx.close();
