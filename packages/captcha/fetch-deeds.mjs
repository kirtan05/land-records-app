// Pull the SCANNED deed files behind "View Deed" on the AnyRoR integrated page and convert them
// to PDF. Each View Deed is an ASP.NET LinkButton: a full-form POST back to InfoSurveyNoDetail.aspx
// with __EVENTTARGET set to that row's control, which streams the deed file as an attachment.
// We replay that POST with the live session cookies and sniff what comes back (TIFF / PDF / an
// HTML error page — the site has been known to answer "Document Record Not Found").
//   node packages/captcha/fetch-deeds.mjs --surveys=174/p1,174/p2,174/p3,239
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
const FORM_URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';  // not `URL`: that shadows the global URL class
const DIST = '15', TAL = '03', VIL = '027';
const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const SURVEYS = arg('surveys', '174/p1').split(',').map((s) => s.trim()).filter(Boolean);
const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const token = (s) => 'Bhalej_' + s.toUpperCase().replace(/[\/\\|\s]+/g, '_');
const log = (...a) => console.log(...a);

const py = spawn(join(ROOT, 'packages/captcha/.venv/bin/python'),
  [join(ROOT, 'packages/captcha/infer_anyror.py'), '--serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
const pyl = createInterface({ input: py.stdout });
const pending = [];
pyl.on('line', (l) => pending.shift()?.(l));
// Never wait forever on the solver: an empty payload makes it answer nothing at all, which looks
// exactly like a hung browser.
const solve = (b64) => Promise.race([
  new Promise((r) => { pending.push(r); py.stdin.write(`b64:${b64}\n`); })
    .then((l) => { const [t, c] = l.split('\t'); return { text: t, conf: +c }; }),
  new Promise((_, rej) => setTimeout(() => rej(new Error('captcha solver timed out')), 25000)),
]);

const ctx = await chromium.launchPersistentContext(join(ROOT, '.chrome-profile-anyror-auto'), {
  channel: 'chrome', headless: false, viewport: null, acceptDownloads: true,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
let lastDialog = '';
page.on('dialog', async (d) => { lastDialog = d.message(); await d.dismiss().catch(() => {}); });
const jitter = (ms) => page.waitForTimeout(ms + Math.floor(Math.random() * ms * 0.5));
const retryEval = async (fn) => { try { return await fn(); } catch { await page.waitForTimeout(1500); return fn(); } };

async function cascade(survey) {
  await page.goto(FORM_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(1800);
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '8');
  await page.waitForTimeout(2400);
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1000);
  await page.selectOption('#ContentPlaceHolder1_ddlVillage', VIL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1000);
  const val = await retryEval(() => page.evaluate(({ want, GU }) => {
    const norm = (s) => String(s).replace(/~~/g, ' ').replace(/[૦-૯]/g, (c) => GU[c] || c)
      .replace(/પૈકી/g, 'p').replace(/પ/g, 'p').toLowerCase().replace(/[\s/|\\]+/g, '/')
      .replace(/^\/+|\/+$/g, '').replace(/p\/(?=\d)/g, 'p').replace(/\/p/g, 'p');
    const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options).find((o) => norm(o.textContent) === norm(want));
    return o ? o.value : '';
  }, { want: survey, GU }));
  if (!val) throw new Error(`survey ${survey} not in dropdown`);
  await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', val);
}

async function reachDetail(survey) {
  for (let t = 1; t <= 4; t++) {
    // The captcha is normally baked in as a data URI; if it ever isn't, screenshot the <img>
    // rather than handing the solver an empty string.
    let b64 = await page.evaluate(() => {
      const m = (document.querySelector('#ContentPlaceHolder1_i_captcha_1')?.src || '').match(/^data:image\/\w+;base64,(.+)$/);
      return m ? m[1] : '';
    });
    if (!b64) {
      const el = await page.$('#ContentPlaceHolder1_i_captcha_1');
      if (!el) throw new Error('no captcha image on page');
      b64 = (await el.screenshot()).toString('base64');
      log('    (captcha read by screenshot — not a data URI this time)');
    }
    const { text, conf } = await solve(b64);
    await page.fill('#ContentPlaceHolder1_txtCaptcha_1, input[id*="captcha" i][type="text"]', text);
    await jitter(500);
    await page.click('#ContentPlaceHolder1_btnGo', { noWaitAfter: true, timeout: 20000 });
    await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 60000 }).catch(() => {});
    await page.waitForTimeout(1500);
    const ok = /InfoSurveyNoDetail/i.test(page.url());
    log(`    captcha try ${t}: ${text} (${conf.toFixed(3)}) → ${ok ? 'ACCEPTED' : 'no'}`);
    if (ok) return true;
    await jitter(2200);
    await Promise.all([
      page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {}),
      page.evaluate(() => { try { __doPostBack('ctl00$ContentPlaceHolder1$lb_refresh_1', ''); } catch (e) {} }),
    ]);
    await page.waitForTimeout(1200);
  }
  return false;
}

const sniff = (b) => {
  if (b.length > 4 && b[0] === 0x25 && b[1] === 0x50 && b[2] === 0x44 && b[3] === 0x46) return 'pdf';
  if (b.length > 4 && ((b[0] === 0x49 && b[1] === 0x49 && b[2] === 0x2a) || (b[0] === 0x4d && b[1] === 0x4d && b[2] === 0x00))) return 'tif';
  if (b.length > 3 && b[0] === 0xff && b[1] === 0xd8) return 'jpg';
  if (b.length > 8 && b[0] === 0x89 && b[1] === 0x50) return 'png';
  const head = b.slice(0, 400).toString('utf8').toLowerCase();
  if (/<html|<!doctype|<script/.test(head)) return 'html';
  return 'bin';
};

const report = [];
for (const survey of SURVEYS) {
  const tok = token(survey), dir = join(OUT, tok), dDir = join(dir, 'deeds');
  log(`\n════════ deeds · Bhalej ${survey} ════════`);
  const rec = { survey, token: tok, deeds: [] };
  try {
    await cascade(survey);
    await jitter(1200);
    if (!await reachDetail(survey)) { rec.error = 'captcha failed'; report.push(rec); continue; }
    await page.waitForTimeout(1200);

    const form = await page.evaluate(() => {
      const t = document.getElementById('ContentPlaceHolder1_gvgarviProDet');
      if (!t) return { deeds: [] };
      const f = document.forms[0];
      const fields = {};
      Array.from(f.querySelectorAll('input,select,textarea')).forEach((e) => {
        if (!e.name) return;
        if (e.type === 'checkbox' || e.type === 'radio') { if (e.checked) fields[e.name] = e.value; }
        else if (e.type !== 'submit' && e.type !== 'button') fields[e.name] = e.value;
      });
      const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
      const deeds = Array.from(t.querySelectorAll('tr')).slice(1).map((tr) => {
        const c = Array.from(tr.cells).map((x) => clean(x.innerText));
        const a = tr.querySelector("a[href*='__doPostBack']");
        const m = a ? a.getAttribute('href').match(/__doPostBack\('([^']+)','([^']*)'\)/) : null;
        return c.some(Boolean) ? { office: c[0], survey: c[1], year: c[2], no: c[3], date: c[4], party: c[6], amount: c[7], target: m ? m[1] : '', argument: m ? m[2] : '' } : null;
      }).filter(Boolean);
      return { action: f.getAttribute('action') || location.href, fields, deeds };
    });
    if (!form.deeds?.length) { log('  no deed rows on this survey'); report.push(rec); continue; }
    // one file per DOCUMENT — a deed with three parties is three rows but one scan
    const uniq = [];
    const seen = new Set();
    for (const d of form.deeds) { const k = `${d.year}/${d.no}`; if (d.target && !seen.has(k)) { seen.add(k); uniq.push(d); } }
    log(`  ${form.deeds.length} deed rows → ${uniq.length} distinct document(s)`);
    mkdirSync(dDir, { recursive: true });

    const action = new URL(form.action, page.url()).toString();
    for (const d of uniq) {
      const body = { ...form.fields, __EVENTTARGET: d.target, __EVENTARGUMENT: d.argument || '' };
      delete body['ctl00$ContentPlaceHolder1$btnGo'];
      const resp = await ctx.request.post(action, {
        form: body, timeout: 90000, maxRedirects: 5,
        headers: {
          Referer: page.url(),
          'Content-Type': 'application/x-www-form-urlencoded',
          'User-Agent': await page.evaluate(() => navigator.userAgent),
          Accept: '*/*', 'Accept-Encoding': 'identity',
        },
      }).catch((e) => ({ err: e.message }));
      if (!resp || resp.err) { log(`    deed ${d.no}/${d.year}: POST failed — ${resp?.err}`); rec.deeds.push({ ...d, ok: false, why: resp?.err }); continue; }
      const buf = Buffer.from(await resp.body());
      const kind = sniff(buf);
      const cd = resp.headers()['content-disposition'] || '';
      log(`    deed ${d.no}/${d.year}: ${resp.status()} ${resp.headers()['content-type'] || '?'} ${buf.length} B → ${kind}${cd ? ` [${cd}]` : ''}`);
      const base = `deed_${d.year}_${d.no}`.replace(/[^\w.]/g, '_');
      if (kind === 'html' || kind === 'bin') {
        const txt = buf.toString('utf8').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 300);
        writeFileSync(join(dDir, base + '.response.txt'), txt);
        log(`      site said: ${txt.slice(0, 160)}`);
        rec.deeds.push({ ...d, ok: false, why: txt.slice(0, 200) });
        await jitter(2500);
        continue;
      }
      const raw = join(dDir, `${base}.${kind}`);
      writeFileSync(raw, buf);
      let pdf = raw;
      if (kind === 'tif') {
        pdf = join(dDir, `${base}.pdf`);
        try { execFileSync('tiff2pdf', ['-o', pdf, raw]); }
        catch { execFileSync('convert', [raw, pdf]); }
      } else if (kind === 'jpg' || kind === 'png') {
        pdf = join(dDir, `${base}.pdf`);
        execFileSync('convert', [raw, '-page', 'A4', pdf]);
      }
      log(`      saved → ${pdf.split('/').pop()}`);
      rec.deeds.push({ ...d, ok: true, file: pdf.split('/').pop(), source: kind });
      await jitter(2500);
    }
    writeFileSync(join(dDir, 'deeds.json'), JSON.stringify(rec, null, 1));
  } catch (e) {
    log(`  ERROR: ${e.message}`);
    rec.error = e.message;
  }
  report.push(rec);
  await jitter(8000);
}
writeFileSync(join(ROOT, 'packages/captcha/deeds-run.json'), JSON.stringify({ at: new Date().toISOString(), report }, null, 1));
log('\nDEEDS DONE → packages/captcha/deeds-run.json');
py.stdin.end();
await ctx.close();
