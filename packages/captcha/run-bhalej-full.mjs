// Bhalej — "everything about a survey in ONE pdf".
//
// Per survey (Anand 15 / Umreth 03 / Bhalej 027):
//   record type 8  → integrated record  → clean PDF (entry numbers keep their RED)
//                  → fire every RED entry's Select$N and pull its scanned VF-6 page(s)
//   record type 6  → for any RED entry the integrated page would not render, ask the dedicated
//                    "Old Scanned VF-6 / Entry Number (નોંધ નંબર)" form for it, one entry per submit.
//                    (This is the by-hand step dad does: enter one entry number, get one page.)
// Everything is written under output/Bhalej_<TOKEN>/; packages/captcha/combine-survey-pdf.py then
// staples integrated + every entry scan into one document.
//
//   node packages/captcha/run-bhalej-full.mjs --surveys=174/p1,174/p3,239
import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { applyCleanFormat, PDF_OPTS } from '../anyror/format.mjs';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const DIST = '15', TAL = '03', VIL = '027';
const PLACE = { district: 'Anand', taluka: 'Umreth', village: 'Bhalej' };
const MAX_CAPTCHA_TRIES = 4;

const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const SURVEYS = arg('surveys', '174/p1,174/p2,174/p3,239').split(',').map((s) => s.trim()).filter(Boolean);
const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const token = (s) => 'Bhalej_' + s.toUpperCase().replace(/[\/\\|\s]+/g, '_');
const log = (...a) => console.log(...a);

// ---- CNN captcha solver (one long-lived process) ----
const py = spawn(join(ROOT, 'packages/captcha/.venv/bin/python'),
  [join(ROOT, 'packages/captcha/infer_anyror.py'), '--serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
const pyLines = createInterface({ input: py.stdout });
const pending = [];
pyLines.on('line', (l) => pending.shift()?.(l));
const solve = (b64) => new Promise((res) => { pending.push(res); py.stdin.write(`b64:${b64}\n`); })
  .then((l) => { const [text, conf] = l.split('\t'); return { text, conf: +conf }; });

const ctx = await chromium.launchPersistentContext(join(ROOT, '.chrome-profile-anyror-auto'), {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run',
    '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
let lastDialog = '';
page.on('dialog', async (d) => { lastDialog = d.message(); await d.dismiss().catch(() => {}); });

const jitter = (ms) => page.waitForTimeout(ms + Math.floor(Math.random() * ms * 0.5));
const retryEval = async (fn) => { try { return await fn(); } catch { await page.waitForTimeout(1500); return fn(); } };
const isBlocked = () => page.evaluate(() => /blocked|fortiweb|attack|denied/i
  .test(document.title + ' ' + (document.body?.innerText || '').slice(0, 400))).catch(() => false);
const captchaB64 = () => page.evaluate(() => {
  const img = document.querySelector('#ContentPlaceHolder1_i_captcha_1') || document.querySelector('img[src*="captcha" i]');
  const m = (img?.src || '').match(/^data:image\/\w+;base64,(.+)$/);
  return m ? m[1] : '';
});
const refreshCaptcha = async () => {
  await Promise.all([
    page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {}),
    page.evaluate(() => { try { __doPostBack('ctl00$ContentPlaceHolder1$lb_refresh_1', ''); } catch (e) {} }),
  ]);
  await page.waitForTimeout(1500);
};

/** Geo cascade under a record type. type 8 → survey dropdown; type 6 → free-text entry number. */
async function cascade(type, survey) {
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(1800);
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', type);
  await page.waitForTimeout(2400);
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1000);
  await page.selectOption('#ContentPlaceHolder1_ddlVillage', VIL);
  if (type !== '8') { await page.waitForTimeout(2400); return; }
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1000);
  const { val, near, text } = await retryEval(() => page.evaluate(({ want, GU }) => {
    const norm = (s) => String(s).replace(/~~/g, ' ').replace(/[૦-૯]/g, (c) => GU[c] || c)
      .replace(/પૈકી/g, 'p').replace(/પ/g, 'p').toLowerCase().replace(/[\s/|\\]+/g, '/')
      .replace(/^\/+|\/+$/g, '').replace(/p\/(?=\d)/g, 'p').replace(/\/p/g, 'p');
    const opts = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options);
    const w = norm(want);
    const o = opts.find((o) => norm(o.textContent) === w);
    const digits = w.replace(/\D/g, '').slice(0, 3);
    return {
      val: o ? o.value : '', text: o ? (o.textContent || '').replace(/~~/g, '').trim() : '',
      near: o ? [] : opts.filter((o) => norm(o.textContent).replace(/\D/g, '').startsWith(digits)).slice(0, 12)
        .map((o) => `${o.textContent.trim()} → ${norm(o.textContent)}`),
    };
  }, { want: survey, GU }));
  if (!val) throw new Error(`survey ${survey} not in dropdown.${near.length ? `\n    near: ${near.join('\n    ')}` : ''}`);
  await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', val);
  return text;
}

// The old-VF-6 form is keyed by village + entry number — the survey number is never sent. So an
// answer for entry 2536 in Bhalej is the answer for EVERY Bhalej survey that lists 2536. Cache it:
// re-asking costs a captcha submit and ~35 s each time, and 174/p1, /p2, /p3 share one entry index.
const VF6_CACHE = join(ROOT, 'packages/captcha/vf6-cache.json');
const vf6cache = existsSync(VF6_CACHE) ? JSON.parse(readFileSync(VF6_CACHE, 'utf8')) : {};
const vf6key = (n) => `${DIST}/${TAL}/${VIL}/${n}`;
const vf6remember = (n, v) => { vf6cache[vf6key(n)] = v; writeFileSync(VF6_CACHE, JSON.stringify(vf6cache, null, 1)); };

const attempts = [];
/** Fill the captcha and press Get Record Detail. `done()` says whether we got what we asked for. */
async function submit({ captchaInput, before, done, label }) {
  for (let t = 1; t <= MAX_CAPTCHA_TRIES; t++) {
    const b64 = await captchaB64();
    if (!b64) throw new Error('no captcha image on page');
    const { text, conf } = await solve(b64);
    if (before) await before();
    lastDialog = '';
    await page.fill(captchaInput, text);
    await jitter(500);
    await page.click('#ContentPlaceHolder1_btnGo', { noWaitAfter: true, timeout: 20000 });
    await page.waitForTimeout(1200);
    const ok = await done();
    attempts.push({ label, try: t, pred: text, conf, accepted: ok, dialog: lastDialog || undefined });
    log(`    captcha try ${t}: ${text} (conf ${conf.toFixed(3)}) → ${ok ? 'ACCEPTED' : 'no'}${lastDialog ? ` [${lastDialog.split('\n')[0].slice(0, 70)}]` : ''}`);
    if (ok) return { ok: true, dialog: lastDialog };
    // A land-record ANSWER ("no such entry", "it is computerised, see the current VF-6") is not a
    // captcha failure — retrying it just burns submits. Only these known answers stop the retries;
    // any other dialog is treated as a rejected captcha and retried.
    if (lastDialog && /નોંધ|રેકર્ડ|મળેલ નથી|મામલતદાર|હકપત્રક|કમ્પ્યૂટરાઇઝડ/.test(lastDialog)) return { ok: false, dialog: lastDialog, answered: true };
    if (await isBlocked()) throw Object.assign(new Error('WAF block page'), { blocked: true });
    await jitter(2200);
    await refreshCaptcha();
  }
  return { ok: false, dialog: lastDialog };
}

/** Pull one Info6oldImage page with the live session cookies. Retries: a dropped GET here would
 *  otherwise be filed as "this entry has no scan", which is a wrong statement about a land record. */
async function saveImage(url, path) {
  for (let t = 1; t <= 3; t++) {
    const resp = await ctx.request.get(url, { timeout: 45000 }).catch((e) => ({ err: e.message }));
    if (resp && !resp.err && resp.ok()) {
      const buf = Buffer.from(await resp.body());
      if (buf.length >= 500) {
        const ext = buf[0] === 0x89 && buf[1] === 0x50 ? 'png' : 'jpg';
        writeFileSync(path + '.' + ext, buf);
        return { file: path.split('/').pop() + '.' + ext, bytes: buf.length };
      }
    }
    if (t < 3) await page.waitForTimeout(1500 * t);
  }
  return null;
}

// ══════════════════════════════ per survey ══════════════════════════════
const runReport = [];
for (let i = 0; i < SURVEYS.length; i++) {
  const survey = SURVEYS[i], tok = token(survey);
  const dir = join(OUT, tok), eDir = join(dir, 'entries');
  mkdirSync(eDir, { recursive: true });
  log(`\n════════ [${i + 1}/${SURVEYS.length}] Bhalej ${survey} ════════`);
  const rep = { survey, token: tok, dir, entries: [] };
  try {
    if (await isBlocked()) throw Object.assign(new Error('WAF block page'), { blocked: true });
    const optText = await cascade('8', survey);
    log(`  dropdown option: ${optText}`);
    await jitter(1200);
    const s = await submit({
      captchaInput: '#ContentPlaceHolder1_txtCaptcha_1, input[id*="captcha" i][type="text"]',
      done: async () => { await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 60000 }).catch(() => {}); await page.waitForTimeout(1200); return /InfoSurveyNoDetail/i.test(page.url()); },
      label: `integrated ${survey}`,
    });
    if (!s.ok) { rep.error = 'captcha failed'; runReport.push(rep); continue; }
    await page.waitForTimeout(1500);

    // --- read the PRISTINE dom first (cleanup rewrites it) ---
    const rows = await page.evaluate(() => {
      const g = document.getElementById('ContentPlaceHolder1_gvEntryResult');
      if (!g) return [];
      return Array.from(g.querySelectorAll("a[href*='Select$']")).map((a) => {
        const m = a.getAttribute('href').match(/Select\$(\d+)/);
        return m ? { index: +m[1], number: (a.textContent || '').trim(), red: /color:Red/i.test((a.getAttribute('style') || '').replace(/\s/g, '')) } : null;
      }).filter(Boolean);
    });
    const meta = await page.evaluate(() => {
      const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
      const grab = (re) => (document.body.innerText.match(re) || [, ''])[1]?.trim() || '';
      return {
        survey_label: clean(document.getElementById('ContentPlaceHolder1_lblSurveyNo')?.innerText),
        village: clean(document.getElementById('ContentPlaceHolder1_lblVillage')?.innerText),
        taluka: clean(document.getElementById('ContentPlaceHolder1_lblTaluka')?.innerText),
        district: clean(document.getElementById('ContentPlaceHolder1_lblDistrict')?.innerText),
        total_area: clean(document.getElementById('ContentPlaceHolder1_lblTotArea')?.innerText) || grab(/Total Area[^:]*:\s*([^\n]+)/i),
        total_assessment: clean(document.getElementById('ContentPlaceHolder1_lblTotAss')?.innerText),
        tenure: clean(document.getElementById('ContentPlaceHolder1_lblTenure')?.innerText),
        land_use: clean(document.getElementById('ContentPlaceHolder1_lblLanduse')?.innerText),
        as_of: grab(/તા\.\s*([0-9/]+ [0-9:]+)\s*ની સ્થિતિએ/),
        computerised_entries: (() => {
          const t = document.getElementById('ContentPlaceHolder1_grdcmputerentry');
          if (!t) return [];
          return Array.from(t.rows).slice(1).map((r) => Array.from(r.cells).map((c) => clean(c.innerText))).filter((r) => r.some(Boolean));
        })(),
        deeds: (() => {
          const t = document.getElementById('ContentPlaceHolder1_gvgarviProDet');
          if (!t) return [];
          return Array.from(t.rows).slice(1).map((r) => Array.from(r.cells).map((c) => clean(c.innerText))).filter((r) => r.some(Boolean));
        })(),
      };
    });
    writeFileSync(join(dir, `anyror_${tok}.html`), await page.content());
    writeFileSync(join(dir, `anyror_${tok}.json`), JSON.stringify({ survey, token: tok, ...PLACE, ...meta, entry_index: rows }, null, 1));
    const red = rows.filter((r) => r.red);
    log(`  entries: ${rows.length} total, ${red.length} old/handwritten (red), ${meta.computerised_entries.length} computerised (text)`);
    rep.meta = { ...meta, computerised_entries: meta.computerised_entries.length, deeds: meta.deeds.length };
    rep.rows = rows;

    // --- integrated PDF (red entry numbers preserved by the patched formatter) ---
    const pdfPath = join(dir, `AnyRoR_Bhalej_${survey.replace(/\//g, '_')}_Integrated.pdf`);
    await applyCleanFormat(page, meta.survey_label || survey, PLACE);
    await page.waitForTimeout(400);
    await page.pdf({ path: pdfPath, ...PDF_OPTS });
    log(`  integrated PDF → ${pdfPath.split('/').pop()}`);
    rep.integratedPdf = pdfPath;

    // --- old entry scans, via the integrated page's own Select$N postbacks (no captcha) ---
    const missing = [];
    for (const r of red) {
      await page.evaluate((x) => {
        const f = document.forms[0];
        f.__EVENTTARGET.value = 'ctl00$ContentPlaceHolder1$gvEntryResult';
        f.__EVENTARGUMENT.value = 'Select$' + x;
        f.submit();
      }, r.index);
      await page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {});
      await page.waitForFunction((want) => {
        const l = document.getElementById('ContentPlaceHolder1_lblEntryNo');
        return !!l && (l.innerText || '').replace(/[^0-9A-Za-z*\/]/g, '').includes(want);
      }, r.number, { timeout: 30000 }).catch(() => {});
      await page.waitForTimeout(1100);
      const info = await page.evaluate(() => {
        const lbl = document.getElementById('ContentPlaceHolder1_lblEntryNo');
        const gv = document.getElementById('ContentPlaceHolder1_gvImages');
        return {
          number: lbl ? (lbl.innerText || '').replace(/[^0-9A-Za-z*\/]/g, '') : '',
          imgs: gv ? Array.from(gv.querySelectorAll('img')).map((i) => i.src).filter(Boolean) : [],
        };
      });
      const enoOf = (u) => (u.match(/[?&]eno=([^&]+)/i) || [, ''])[1];
      const mine = info.imgs.length && (enoOf(info.imgs[0]) === r.number || info.number === r.number);
      if (!mine) { log(`    entry ${r.number}: nothing rendered here — will try the old VF-6 form`); missing.push(r); await jitter(900); continue; }
      const files = [];
      for (let p = 0; p < info.imgs.length; p++) {
        const got = await saveImage(info.imgs[p], join(eDir, `entry_${r.number.replace(/[^\w]/g, '_')}_p${p + 1}`));
        if (got) files.push(got.file);
      }
      log(`    entry ${r.number}: ${files.length} scanned page(s)  [integrated]${files.length ? '' : '  ⚠ DOWNLOAD FAILED'}`);
      rep.entries.push({ number: r.number, via: 'integrated', files, ...(files.length ? {} : { downloadFailed: `${info.imgs.length} image(s) listed but none could be fetched` }) });
      await jitter(1000);
    }

    // --- fallback: the dedicated old-VF-6 form, one captcha per entry number ---
    for (const r of missing) {
      const cached = vf6cache[vf6key(r.number)];
      if (cached && cached.unavailable) {
        log(`    entry ${r.number}: NOT AVAILABLE — ${cached.unavailable} [cached]`);
        rep.entries.push({ number: r.number, via: 'vf6', files: [], unavailable: cached.unavailable, cached: true });
        continue;
      }
      log(`  old VF-6 form → entry ${r.number}`);
      await cascade('6');
      await jitter(900);
      const res = await submit({
        captchaInput: '#ContentPlaceHolder1_txt_captcha_1',
        before: async () => { await page.fill('#ContentPlaceHolder1_txtNo', r.number); },
        done: async () => { await page.waitForTimeout(4500); return page.evaluate(() => !!document.querySelector('#ContentPlaceHolder1_gvImages img, img[src*="Info6oldImage" i]')); },
        label: `vf6 ${survey} #${r.number}`,
      });
      if (res.ok) {
        const imgs = await page.evaluate(() => Array.from(document.querySelectorAll('#ContentPlaceHolder1_gvImages img, img[src*="Info6oldImage" i]')).map((i) => i.src));
        const files = [];
        for (let p = 0; p < imgs.length; p++) {
          const got = await saveImage(imgs[p], join(eDir, `entry_${r.number.replace(/[^\w]/g, '_')}_p${p + 1}`));
          if (got) files.push(got.file);
        }
        log(`    entry ${r.number}: ${files.length} scanned page(s)  [old VF-6 form]`);
        rep.entries.push({ number: r.number, via: 'vf6', files });
        vf6remember(r.number, { scan: true, pages: files.length });
      } else {
        const why = (res.dialog || '').replace(/\s+/g, ' ').trim() || 'no answer';
        log(`    entry ${r.number}: NOT AVAILABLE — ${why}`);
        rep.entries.push({ number: r.number, via: 'vf6', files: [], unavailable: why });
        if (res.answered) vf6remember(r.number, { unavailable: why });
      }
      await jitter(4000);
    }

    writeFileSync(join(eDir, 'entries.json'), JSON.stringify({ survey, rows, captured: rep.entries }, null, 1));
    log(`  ✔ ${rep.entries.filter((e) => e.files.length).length}/${red.length} old entries have scans`);
  } catch (e) {
    log(`  ERROR: ${e.message}`);
    rep.error = e.message;
    if (e.blocked) { runReport.push(rep); log('WAF BLOCK — stopping.'); break; }
  }
  runReport.push(rep);
  if (i < SURVEYS.length - 1) await jitter(9000);
}

writeFileSync(join(ROOT, 'packages/captcha/bhalej-run.json'), JSON.stringify({ at: new Date().toISOString(), attempts, runReport }, null, 1));
const acc = attempts.filter((a) => a.accepted).length;
log(`\ncaptcha: ${acc}/${attempts.length} accepted`);
log(`report → packages/captcha/bhalej-run.json`);
py.stdin.end();
await ctx.close();
