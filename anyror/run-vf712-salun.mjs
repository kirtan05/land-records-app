// OLD SCANNED VF-7/12 fetcher. Per survey: you solve ONE captcha + "Get Record Detail";
// I read the gvResult grid, download every "Ok" period PDF, weed out "does not exist"
// placeholders, and combine them chronologically into _VF712_ALL.pdf.
//   node anyror/run-vf712.mjs --launch                 # all surveys
//   node anyror/run-vf712.mjs --launch --only=221/p    # just one (validation)
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { surveyToken, normalizeSurvey } from '../src/normalize.mjs';
import { REPO } from '../src/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const OUT = REPO+'/output';
const STATE = join(OUT, '_vf712_state.json');
const LAND = '11', DIST = '16', TAL = '08', VIL = '072';
const NORM = `(s)=>{const GU={'૦':'0','૧':'1','૨':'2','૩':'3','૪':'4','૫':'5','૬':'6','૭':'7','૮':'8','૯':'9'};return String(s).replace(/[૦-૯]/g,c=>GU[c]||c).replace(/પ/g,'p').toLowerCase().replace(/[\\s/|\\\\]+/g,'/').replace(/^\\/+|\\/+$/g,'').replace(/p\\/(?=\\d)/g,'p');}`;
// Genuine old VF-7/12 scans are images (no text layer). A system "not scanned / dilapidated /
// not available" placeholder is a generated text PDF — so any short extractable text = placeholder.
const PLACEHOLDER = /શકેલ નથી|શકલ નથી|જર્જરીત|ઉપલબ્ધ નથી|does not exist|not available|record not found/i;
const isPlaceholder = (file) => { try { const raw = execFileSync('pdftotext', ['-l', '3', file, '-'], { encoding: 'utf8' }); const compact = raw.replace(/\s+/g, ''); const plain = raw.replace(/\s+/g, ' ').trim(); return PLACEHOLDER.test(raw) || (compact.length >= 10 && plain.length < 300); } catch { return false; } };

const only = (process.argv.find((a) => a.startsWith('--only=')) || '').split('=')[1] || '125';
let targets = only.split(',').map((v) => ({ normalized: normalizeSurvey(v.trim()) }));

const readState = () => (existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {});
const writeState = (s) => writeFileSync(STATE, JSON.stringify(s, null, 2));
const sani = (s) => String(s).replace(/[^0-9A-Za-z._-]+/g, '-').replace(/^-+|-+$/g, '');
const startYear = (period) => { const m = String(period).match(/(\d{4})/); return m ? +m[1] : 9999; };

const LAUNCH = process.argv.includes('--launch');
let browser, ctx, page;
if (LAUNCH) {
  ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror', {
    channel: 'chrome', headless: false, viewport: null,
    args: ['--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
  });
  page = ctx.pages()[0] || await ctx.newPage();
} else {
  browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  ctx = browser.contexts()[0];
  page = ctx.pages().find((p) => /anyror\.gujarat/i.test(p.url())) || await ctx.newPage();
}

// Intercept the PDF navigation and fetch clean bytes ourselves (bypasses Chrome's PDF viewer,
// which otherwise eats the response body). Fires for the main page and any popup tab.
const pdfCaptures = [];
await ctx.route(/PDFView1\.aspx/i, async (route) => {
  const u = route.request().url();
  try {
    const resp = await route.fetch({ timeout: 60000 });
    const body = await resp.body();
    pdfCaptures.push({ url: u, body, cd: resp.headers()['content-disposition'] || '', ct: resp.headers()['content-type'] || '', status: resp.status() });
    await route.fulfill({ response: resp, body });
  } catch (e) { pdfCaptures.push({ url: u, err: e.message }); try { await route.abort(); } catch {} }
});

const setBanner = (text, color = '#0b5') => page.evaluate(({ text, color }) => {
  let b = document.getElementById('__bot_banner'); if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + color + ';color:#fff;font:bold 16px sans-serif;padding:10px;text-align:center';
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
  const vname = await page.evaluate(() => { const s = document.querySelector('#ContentPlaceHolder1_ddlVillage'); return s.options[s.selectedIndex]?.textContent.trim(); });
  console.log('  village selected: ' + vname);
  if (!/સલુ|સાલુ|સાલૂ/.test(vname)) throw new Error('WRONG VILLAGE: ' + vname + ' (expected Salun) — aborting');
  // big slow dropdown — wait until OUR option actually appears
  await page.waitForFunction(([t, nf]) => { const norm = eval('(' + nf + ')'); const sel = document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno'); return sel && sel.options.length > 50 && Array.from(sel.options).some((o) => norm(o.textContent) === t); }, [normKey, NORM], { timeout: 60000 });
  const val = await page.evaluate(([t, nf]) => { const norm = eval('(' + nf + ')'); const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno').options).find((o) => norm(o.textContent) === t); return o ? o.value : null; }, [normKey, NORM]);
  if (!val) throw new Error('survey not in old-scanned dropdown: ' + normKey);
  await page.selectOption('#ContentPlaceHolder1_ddlOldScannedSno', val);
  return val;
}

const parseRows = () => page.$$eval('#ContentPlaceHolder1_gvResult tr', (trs) => trs.map((tr) => {
  const cells = Array.from(tr.cells).map((c) => c.innerText.trim());
  const a = tr.querySelector("a[href*='Select$']");
  const m = a ? a.getAttribute('href').match(/Select\$(\d+)/) : null;
  return m ? { selectIndex: +m[1], period: cells[1] || '', thok: cells[2] || '', block: cells[3] || '', oldSurvey: cells[4] || '', status: cells[5] || '' } : null;
}).filter(Boolean));

// Trigger one row's View PDF in a new tab; the ctx.route above captures the PDF bytes.
async function fetchRowPdf(selectIndex) {
  const before = pdfCaptures.length;
  await page.evaluate(() => { try { theForm.target = '_blank'; } catch {} });
  let popup = null;
  try {
    [popup] = await Promise.all([
      ctx.waitForEvent('page', { timeout: 60000 }),
      page.evaluate((n) => __doPostBack('ctl00$ContentPlaceHolder1$gvResult', 'Select$' + n), selectIndex),
    ]);
  } catch (e) { await page.evaluate(() => { try { theForm.target = ''; } catch {} }); return { err: 'no popup: ' + e.message }; }
  let cap = null;
  for (let i = 0; i < 60 && !cap; i++) { await page.waitForTimeout(500); cap = pdfCaptures.slice(before).find((c) => c.body || c.err); }
  await popup.close().catch(() => {});
  await page.evaluate(() => { try { theForm.target = ''; } catch {} });
  if (!cap) return { err: 'no PDFView response' };
  if (cap.err) return { err: cap.err };
  const fname = (String(cap.cd).match(/filename=([^;]+)/i) || [, ''])[1].trim().replace(/^["']|["']$/g, '');
  const idx = cap.body ? cap.body.indexOf('%PDF') : -1;
  const buf = idx >= 0 ? cap.body.slice(idx) : null;
  return { buf, fname, url: cap.url, status: cap.status, ct: cap.ct, len: cap.body ? cap.body.length : 0 };
}


async function processSurvey(t) {
  const key = t.normalized, token = surveyToken(key);
  const dir = join(OUT, token, 'oldvf712');
  rmSync(dir, { recursive: true, force: true }); mkdirSync(dir, { recursive: true });

  await cascade(key);
  await page.waitForTimeout(2500);
  await setBanner(`🤖 VF-7/12 ${key} — type the CAPTCHA and click "Get Record Detail"`, '#0b5');
  console.log(`  waiting for results page (solve captcha)…`);
  await page.waitForURL(/InfoOld712Detail/i, { timeout: 9 * 60 * 1000 });
  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForTimeout(1500);
  const refererUrl = page.url();

  const rows = await parseRows();
  console.log(`  grid: ${rows.length} rows; statuses: ${[...new Set(rows.map((r) => r.status))].join(', ')}`);
  await setBanner(`⏳ ${key}: downloading ${rows.length} scanned VF-7/12 documents… do not touch`, '#c80');

  const saved = [];
  for (const r of rows) {
    const ok = /ok/i.test(r.status);
    if (!ok) { saved.push({ ...r, kept: false, reason: 'status≠Ok' }); continue; }
    const res = await fetchRowPdf(r.selectIndex);
    if (!res.buf) { saved.push({ ...r, kept: false, reason: res.err || 'no pdf bytes' }); console.log(`    row ${r.selectIndex} ${r.period}: FAIL (${res.err || 'no bytes'}; status=${res.status} ct=${res.ct} len=${res.len})`); await page.waitForTimeout(1200); continue; }
    const fname = `VF712_${token}_${sani(r.period)}_r${String(r.selectIndex).padStart(2, '0')}.pdf`;
    const fpath = join(dir, fname);
    writeFileSync(fpath, res.buf);
    const placeholder = isPlaceholder(fpath);
    if (placeholder || res.buf.length < 1500) { rmSync(fpath, { force: true }); saved.push({ ...r, kept: false, reason: placeholder ? 'not-scanned/placeholder' : 'too small' }); console.log(`    row ${r.selectIndex} ${r.period}: weeded (${placeholder ? 'placeholder' : 'tiny'})`); }
    else { saved.push({ ...r, kept: true, file: fpath, bytes: res.buf.length, server_name: res.fname }); console.log(`    row ${r.selectIndex} ${r.period}: ✓ ${(res.buf.length / 1024).toFixed(0)}KB`); }
    await page.waitForTimeout(1500); // be gentle
  }

  const kept = saved.filter((s) => s.kept).sort((a, b) => startYear(a.period) - startYear(b.period));
  let combined = null;
  if (kept.length) {
    combined = join(dir, `VF712_${token}_ALL.pdf`);
    if (kept.length === 1) writeFileSync(combined, readFileSync(kept[0].file));
    else execFileSync('pdfunite', [...kept.map((k) => k.file), combined]);
  }
  const manifest = { survey: key, token, fetched_at: new Date().toISOString(), total_rows: rows.length, kept: kept.length, combined, periods: kept.map((k) => k.period), rows: saved };
  writeFileSync(join(OUT, token, `vf712_${token}.json`), JSON.stringify(manifest, null, 2));
  const st = readState(); st[key] = { done: true, token, dir, combined, kept: kept.length, total: rows.length, periods: kept.map((k) => k.period), at: manifest.fetched_at }; writeState(st);
  console.log(`  ✓ ${key}: kept ${kept.length}/${rows.length} -> ${combined ? 'VF712_' + token + '_ALL.pdf' : '(none)'}`);
}

for (let i = 0; i < targets.length; i++) {
  const t = targets[i], key = t.normalized, tag = `[${i + 1}/${targets.length}] ${key}`;
  if (readState()[key]?.done && !only) { console.log(`${tag} already done — skip`); continue; }
  console.log(`${tag} cascading…`);
  try { await processSurvey(t); }
  catch (e) {
    if (e.blocked) { console.log(`${tag} ${e.message} — stopping (IP rate-limited). Re-run to resume.`); await setBanner('⚠️ AnyRoR blocked our IP. Re-run later to resume.', '#c00'); break; }
    console.log(`${tag} ERROR: ${e.message}`); await setBanner(`⚠️ Error on ${key}: ${e.message}. Re-run to resume.`, '#c00');
  }
  await page.waitForTimeout(2500);
}
await setBanner('✅ VF-7/12 done! Files in output/<survey>/oldvf712/. You can close this window.', '#06c');
console.log('VF712 RUNNER DONE');
if (browser) await browser.close(); else await ctx.close();
