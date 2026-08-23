// AnyRoR integrated fetch with the CAPTCHA solved by the CNN instead of by a human.
// Per survey: cascade (record type 8) → read the captcha data-URI → infer_anyror.py --serve →
// fill + "Get Record Detail" → on the detail page capture INTEGRATED + DEED table + VF-6 (નોંધ)
// entry scans, exactly the set the Android app captures.
//
// It also scores itself: every solve is logged with its prediction, confidence and whether the
// site accepted it, so this doubles as a live accuracy test of anyror_cnn_real.onnx.
//
//   node packages/captcha/runners/run-anyror-auto.mjs --n=5              # 5 surveys that have iRCMS cases
//   node packages/captcha/runners/run-anyror-auto.mjs --surveys=23,28    # explicit list
//   node packages/captcha/runners/run-anyror-auto.mjs --n=3 --dry        # cascade + solve, never submit
//
// AnyRoR has a WAF that IP-blocks bursts ([[anyror-waf-ip-block]]): this runs HEADED, one gentle
// page load per survey, jittered 8-14 s between surveys, and ABORTS on the first block page.
import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { REPO } from '../../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const LAND = '8';                                        // Integrated Survey Details
// Defaults are Anand / Umreth / Bharoda. --village takes a code ('029') or Gujarati text ('ભાલેજ').
const DIST = '15', TAL = '03';
const MAX_CAPTCHA_TRIES = 3;
const LOG = join(ROOT, 'packages/captcha/runs/anyror-auto-log.json');

const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const DRY = process.argv.includes('--dry');
const VILLAGE = arg('village', '029');            // code, or Gujarati village name
const TAG = arg('tag', '');                       // output subfolder prefix (e.g. Bhalej)
const N = +arg('n', 5);

// ---- targets: surveys the iRCMS Bharoda run found cases on (those are the interesting ones) ----
const surveysArg = arg('surveys', '');
let targets;
if (surveysArg) {
  targets = surveysArg.split(',').map((s) => s.trim()).filter(Boolean);
} else {
  const st = JSON.parse(readFileSync(join(OUT, '_state.json'), 'utf8')).surveys;
  targets = Object.entries(st).filter(([, v]) => v.case_count > 0).map(([k]) => k).slice(0, N);
}
console.log(`targets (${targets.length}): ${targets.join(', ')}${DRY ? '   [DRY RUN — will not submit]' : ''}`);

// ---- captcha solver: one long-lived python process, ~0.6 ms per solve ----
const py = spawn(join(ROOT, 'packages/captcha/.venv/bin/python'),
  [join(ROOT, 'packages/captcha/pipeline/4-infer.py'), '--serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
const pyLines = createInterface({ input: py.stdout });
const pending = [];
pyLines.on('line', (l) => pending.shift()?.(l));
const solve = (b64) => new Promise((res) => { pending.push(res); py.stdin.write(`b64:${b64}\n`); })
  .then((l) => { const [text, conf] = l.split('\t'); return { text, conf: +conf }; });

// ---- browser: headed (WAF fingerprints headless), own profile ----
// Own profile dir: the sampling/orchestrator scripts often leave a Chrome holding
// .chrome-profile-anyror, and a persistent context can't share one.
const ctx = await chromium.launchPersistentContext(join(ROOT, arg('profile', '.chrome-profile-anyror-auto')), {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run',
    '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || (await ctx.newPage());

// A wrong captcha comes back as a JS alert; capture the text instead of letting it auto-dismiss.
let lastDialog = '';
page.on('dialog', async (d) => { lastDialog = d.message(); await d.dismiss().catch(() => {}); });

// A cascade postback can destroy the execution context between waitForFunction resolving and the
// next evaluate; one retry after a settle is enough.
const retryEval = async (fn) => { try { return await fn(); } catch { await page.waitForTimeout(1500); return fn(); } };
const jitter = (ms) => page.waitForTimeout(ms + Math.floor(Math.random() * ms * 0.5));
const isBlocked = () => page.evaluate(
  () => /blocked|fortiweb|attack|denied/i.test(document.title + ' ' + (document.body?.innerText || '').slice(0, 400))
).catch(() => false);

const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const token = (s) => (TAG ? TAG + '_' : '') + s.toUpperCase().replace(/[\/\\|\s]+/g, '_');

async function cascade(survey) {
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(1800);
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', LAND);
  await page.waitForTimeout(2200);
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1000);
  const vilVal = await retryEval(() => page.evaluate((want) => {
    const opts = Array.from(document.querySelector('#ContentPlaceHolder1_ddlVillage').options);
    const o = opts.find((o) => o.value === want)
      || opts.find((o) => (o.textContent || '').trim().includes(want));
    return o ? o.value : '';
  }, VILLAGE));
  if (!vilVal) throw new Error(`village "${VILLAGE}" not in dropdown`);
  await page.selectOption('#ContentPlaceHolder1_ddlVillage', vilVal);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1000);
  const { val, near } = await retryEval(() => page.evaluate(({ want, GU }) => {
    // Options are Gujarati: digits ૦-૯, and "પૈકી" (paiki, = sub-plot) which our keys write as "p".
    const norm = (s) => String(s).replace(/~~/g, ' ').replace(/[૦-૯]/g, (c) => GU[c] || c)
      .replace(/પૈકી/g, 'p').replace(/પ/g, 'p')
      .toLowerCase().replace(/[\s/|\\]+/g, '/').replace(/^\/+|\/+$/g, '')
      .replace(/p\/(?=\d)/g, 'p').replace(/\/p/g, 'p');
    const opts = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options);
    const w = norm(want);
    const o = opts.find((o) => norm(o.textContent) === w);
    const digits = w.replace(/\D/g, '').slice(0, 3);
    return {
      val: o ? o.value : '',
      // on a miss, show what the site actually offers for that number — Gujarati spellings drift
      near: o ? [] : opts.filter((o) => norm(o.textContent).replace(/\D/g, '').startsWith(digits))
        .slice(0, 12).map((o) => `${o.textContent.trim()} → ${norm(o.textContent)}`),
    };
  }, { want: survey, GU }));
  if (!val) throw new Error(`survey ${survey} not in dropdown.${near.length ? ` Near matches:\n    ${near.join('\n    ')}` : ''}`);
  await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', val);
  return val;
}

/** Read the captcha PNG (baked into the page as a data URI) → base64. */
const captchaB64 = () => page.evaluate(() => {
  const img = document.querySelector('#ContentPlaceHolder1_i_captcha_1') || document.querySelector('img[src*="captcha" i]');
  const m = (img?.src || '').match(/^data:image\/\w+;base64,(.+)$/);
  return m ? m[1] : '';
});

/** Ask for a fresh captcha without reloading the page (keeps the cascade + ViewState). */
async function refreshCaptcha() {
  await Promise.all([
    page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {}),
    page.evaluate(() => { try { __doPostBack('ctl00$ContentPlaceHolder1$lb_refresh_1', ''); } catch (e) {} }),
  ]);
  await page.waitForTimeout(1500);
}

const attempts = [];   // the accuracy record: one row per submitted solve

async function solveAndSubmit(survey) {
  for (let t = 1; t <= MAX_CAPTCHA_TRIES; t++) {
    const b64 = await captchaB64();
    if (!b64) throw new Error('no captcha image on page');
    const t0 = Date.now();
    const { text, conf } = await solve(b64);
    const ms = Date.now() - t0;
    console.log(`  captcha try ${t}: ${text} (conf ${conf.toFixed(3)}, ${ms} ms)`);
    if (DRY) { attempts.push({ survey, try: t, pred: text, conf, ms, accepted: null, dry: true }); return false; }

    lastDialog = '';
    await page.fill('#ContentPlaceHolder1_txtCaptcha_1, input[id*="captcha" i][type="text"]', text);
    await jitter(600);
    // noWaitAfter: the postback can take well over Playwright's 30 s click timeout, and a click
    // that "succeeded but timed out waiting for navigation" throws away a real submit.
    await page.click('#ContentPlaceHolder1_btnGo', { noWaitAfter: true, timeout: 20000 });
    await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 90000 }).catch(() => {});
    await page.waitForTimeout(1500);

    const onDetail = /InfoSurveyNoDetail/i.test(page.url());
    attempts.push({ survey, try: t, pred: text, conf, ms, accepted: onDetail, dialog: lastDialog || undefined });
    if (onDetail) { console.log(`  → accepted (${text})`); return true; }
    console.log(`  → rejected${lastDialog ? ` ("${lastDialog.slice(0, 60)}")` : ''}`);
    if (await isBlocked()) throw Object.assign(new Error('WAF block page'), { blocked: true });
    await jitter(2500);
    await refreshCaptcha();
  }
  return false;
}

// ---- capture, on the detail page ----
async function captureIntegrated(dir, tok) {
  writeFileSync(join(dir, `anyror_${tok}.html`), await page.content());
  const data = await page.evaluate(() => {
    const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const grab = (re) => (document.body.innerText.match(re) || [, ''])[1]?.trim() || '';
    return {
      total_area: grab(/Total Area[^:]*:\s*([^\n]+)/i),
      tenure: grab(/Tenure[^:]*:\s*([^\n]+)/i),
      as_of: grab(/તા\.\s*([0-9/]+ [0-9:]+)\s*ની સ્થિતિએ/),
      sections: Array.from(document.querySelectorAll('table')).map((t) => ({
        id: t.id || '', rows: Array.from(t.querySelectorAll('tr'))
          .map((tr) => Array.from(tr.cells).map((c) => clean(c.innerText))).filter((r) => r.some((c) => c)),
      })),
      raw_text: clean(document.body.innerText),
    };
  });
  writeFileSync(join(dir, `anyror_${tok}.json`), JSON.stringify(data, null, 1));
  return data;
}

/** Sub-registrar registered-deed table. The deed FILES are dead server-side (confirmed in the
 *  app: "Document Record Not Found"), so we capture the details, same as Android does. */
async function captureDeeds(dir) {
  const deeds = await page.evaluate(() => {
    const t = document.getElementById('ContentPlaceHolder1_gvgarviProDet');
    if (!t) return [];
    return Array.from(t.querySelectorAll('tr')).slice(1)
      .map((tr) => Array.from(tr.cells).map((c) => (c.innerText || '').replace(/\s+/g, ' ').trim()))
      .filter((r) => r.some((c) => c));
  });
  if (deeds.length) writeFileSync(join(dir, 'deeds.json'), JSON.stringify(deeds, null, 1));
  return deeds;
}

/** VF-6 / નોંધ entries. RED anchors have a scan; fire Select$N, then GET each page image with the
 *  live session cookies (ctx.request shares them). */
async function captureEntries(dir) {
  const rows = await page.evaluate(() => {
    const grid = document.getElementById('ContentPlaceHolder1_gvEntryResult');
    if (!grid) return [];
    return Array.from(grid.querySelectorAll("a[href*='Select$']")).map((a) => {
      const m = a.getAttribute('href').match(/Select\$(\d+)/);
      return m ? { index: +m[1], number: (a.textContent || '').trim(), red: /Red/i.test((a.getAttribute('style') || '').replace(/\s/g, '')) } : null;
    }).filter(Boolean);
  });
  const red = rows.filter((r) => r.red);
  if (!rows.length) return { rows, saved: 0 };
  console.log(`  entries: ${rows.length} (${red.length} with scans)`);

  const eDir = join(dir, 'entries');
  mkdirSync(eDir, { recursive: true });
  let saved = 0;
  const failed = [];
  for (const r of red) {
    await page.evaluate((i) => {
      // Playwright's evaluate runs non-strict, so __doPostBack is callable; fall back to a manual
      // form submit if the page ever stops exposing it.
      try { __doPostBack('ctl00$ContentPlaceHolder1$gvEntryResult', 'Select$' + i); }
      catch (e) {
        const f = document.forms[0];
        f.__EVENTTARGET.value = 'ctl00$ContentPlaceHolder1$gvEntryResult';
        f.__EVENTARGUMENT.value = 'Select$' + i;
        f.submit();
      }
    }, r.index);
    // Wait for the label to show THIS entry — not merely for the label to exist. After the first
    // entry the previous render is still on screen, so an existence check returns instantly and we
    // read the previous entry's scan under this entry's number (silently overwriting it).
    await page.waitForFunction((want) => {
      const l = document.getElementById('ContentPlaceHolder1_lblEntryNo');
      return !!l && (l.innerText || '').replace(/[^0-9A-Za-z*\/]/g, '').includes(want);
    }, r.number, { timeout: 45000 }).catch(() => {});
    await page.waitForTimeout(1200);
    const info = await page.evaluate(() => {
      const lbl = document.getElementById('ContentPlaceHolder1_lblEntryNo');
      const gv = document.getElementById('ContentPlaceHolder1_gvImages');
      return {
        number: lbl ? (lbl.innerText || '').replace(/[^0-9A-Za-z*\/]/g, '') : '',
        imgs: gv ? Array.from(gv.querySelectorAll('img')).map((i) => i.src).filter(Boolean) : [],
      };
    });
    // A RED anchor means "old handwritten entry" — it does NOT mean a scan exists. When the server
    // has no scan it renders nothing AND does not clear the previous entry's #lblEntryNo/#gvImages,
    // so the last entry's scan stays on screen. Proven by firing a known-bad entry first on a fresh
    // detail page: no label, zero images (packages/captcha/probes/diag-entry-stale.mjs). Re-firing never helps.
    // So trust the server's own eno=/label: if it doesn't name the entry we asked for, there is no
    // scan — skip. A missing scan is recoverable; a mislabeled land record is not.
    const enoOf = (u) => (u.match(/[?&]eno=([^&]+)/i) || [, ''])[1];
    const urlEno = info.imgs.length ? enoOf(info.imgs[0]) : '';
    const mine = urlEno === r.number || info.number === r.number;
    console.log(`    entry ${r.number}: lbl="${info.number}" eno=${urlEno || '—'} imgs=${info.imgs.length}`);
    if (!mine) {
      const why = info.imgs.length ? `no scan (page still showed ${info.number || urlEno})` : 'no scan (nothing rendered)';
      console.log(`      ⚠ ${why} — SKIPPED`);
      failed.push({ number: r.number, why });
      await jitter(1500);
      continue;
    }
    for (let p = 0; p < info.imgs.length; p++) {
      const resp = await ctx.request.get(info.imgs[p], { timeout: 45000 }).catch((e) => ({ err: e.message }));
      if (!resp || resp.err || !resp.ok()) { console.log(`      p${p + 1} FAILED ${resp?.err || resp?.status()}`); continue; }
      const buf = Buffer.from(await resp.body());
      if (buf.length < 500) { console.log(`      p${p + 1} empty (${buf.length} B)`); continue; }
      // the handler is called …oldImage but serves PNG as often as JPEG — sniff, don't assume
      const ext = buf[0] === 0x89 && buf[1] === 0x50 ? 'png' : 'jpg';
      // name from the entry we ASKED for; info.number is only a cross-check
      writeFileSync(join(eDir, `entry_${r.number.replace(/[^\w]/g, '_')}_p${p + 1}.${ext}`), buf);
      saved++;
    }
    await jitter(1200);                                     // gentle between entry postbacks
  }
  writeFileSync(join(eDir, 'entries.json'), JSON.stringify({ rows, capturedPages: saved, failed }, null, 1));
  if (failed.length) console.log(`    ${failed.length} entr${failed.length > 1 ? 'ies' : 'y'} not captured: ${failed.map((f) => f.number).join(', ')}`);
  return { rows, saved, failed };
}

// ---- main loop ----
const results = [];
for (let i = 0; i < targets.length; i++) {
  const survey = targets[i], tok = token(survey), tag = `[${i + 1}/${targets.length}] ${survey}`;
  console.log(`\n${tag} cascade…`);
  try {
    if (await isBlocked()) throw Object.assign(new Error('WAF block page'), { blocked: true });
    await cascade(survey);
    await jitter(1500);
    const ok = await solveAndSubmit(survey);
    if (!ok) { results.push({ survey, ok: false, why: DRY ? 'dry-run' : 'captcha failed 3×' }); continue; }

    const dir = join(OUT, tok);
    mkdirSync(dir, { recursive: true });
    await captureIntegrated(dir, tok);
    const deeds = await captureDeeds(dir);
    const entries = await captureEntries(dir);
    console.log(`  saved → output/${tok}/  (deeds ${deeds.length}, entry scans ${entries.saved})`);
    results.push({ survey, ok: true, deeds: deeds.length, entryRows: entries.rows.length, entryScans: entries.saved });
  } catch (e) {
    console.log(`${tag} ERROR: ${e.message}`);
    results.push({ survey, ok: false, why: e.message });
    if (e.blocked) { console.log('WAF BLOCK — stopping. Wait it out or switch to a fresh residential IP.'); break; }
  }
  if (i < targets.length - 1) await jitter(8000);           // be a polite visitor
}

// ---- the point of the exercise: live captcha accuracy ----
const real = attempts.filter((a) => a.accepted !== null);
const hit = real.filter((a) => a.accepted).length;
const first = real.filter((a) => a.try === 1);
const firstHit = first.filter((a) => a.accepted).length;
console.log('\n────────── live CAPTCHA accuracy ──────────');
if (real.length) {
  console.log(`solves submitted : ${real.length}`);
  console.log(`accepted         : ${hit}/${real.length} = ${(hit / real.length * 100).toFixed(1)}%`);
  console.log(`first-try        : ${firstHit}/${first.length} = ${(firstHit / first.length * 100).toFixed(1)}%`);
  const okC = real.filter((a) => a.accepted).map((a) => a.conf), noC = real.filter((a) => !a.accepted).map((a) => a.conf);
  const avg = (a) => a.length ? (a.reduce((x, y) => x + y, 0) / a.length).toFixed(3) : 'n/a';
  console.log(`mean confidence  : accepted ${avg(okC)}   rejected ${avg(noC)}`);
  console.log(`mean solve time  : ${(real.reduce((a, b) => a + b.ms, 0) / real.length).toFixed(1)} ms`);
} else console.log('no solves submitted');
console.log(`surveys captured : ${results.filter((r) => r.ok).length}/${targets.length}`);
writeFileSync(LOG, JSON.stringify({ at: new Date().toISOString(), attempts, results }, null, 1));
console.log(`log → ${LOG}`);

py.stdin.end();
await ctx.close();
