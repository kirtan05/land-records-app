// One-shot capture of the OLD SCANNED VF-7/12 results page + the "View PDF" mechanism.
// Run at the browser: you solve ONE captcha for the survey, click "Get Record Detail",
// then click ONE "View PDF". Everything is saved to packages/anyror/vf712-capture/ for offline study.
//   node packages/anyror/capture-vf712.mjs 221/p
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { normalizeSurvey } from '../core/normalize.mjs';
import { REPO } from '../core/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const DIR = REPO+'/anyror/vf712-capture';
const DIST = '15', TAL = '03', VIL = '029';
const target = normalizeSurvey(process.argv[2] || '221/p');
mkdirSync(DIR, { recursive: true });

const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror', {
  channel: 'chrome', headless: false, viewport: null, acceptDownloads: true,
  args: ['--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();

// record every PDF-ish response across ALL pages (incl. popups opened by View PDF)
const hits = [];
let pdfN = 0;
let popup = null; // set when "Get Record Detail" / "View PDF" opens a new tab
const watch = (p) => p.on('response', async (res) => {
  try {
    const ct = (res.headers()['content-type'] || '').toLowerCase();
    const u = res.url();
    if (ct.includes('pdf') || /\.pdf(\?|$)/i.test(u) || /scan|viewpdf|showpdf|image\.aspx|handler/i.test(u)) {
      const rec = { url: u, status: res.status(), contentType: ct, headers: res.headers() };
      try { const b = await res.body(); rec.bytes = b.length; rec.startsWithPDF = b.slice(0, 8).toString('latin1'); const f = join(DIR, `captured-${++pdfN}.pdf`); writeFileSync(f, b); rec.saved = f; } catch (e) { rec.bodyErr = e.message; }
      hits.push(rec); console.log('PDF-ish response:', JSON.stringify(rec, null, 1));
    }
  } catch {}
});
watch(page);
ctx.on('page', (p) => { popup = p; console.log('NEW TAB:', p.url()); watch(p); p.on('load', () => console.log('  tab loaded:', p.url())); });

const setBanner = (text, color = '#0b5') => page.evaluate(({ text, color }) => {
  let b = document.getElementById('__bot_banner'); if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:' + color + ';color:#fff;font:bold 16px sans-serif;padding:10px;text-align:center';
  b.textContent = text;
}, { text, color }).catch(() => {});

async function goto() { for (let a = 1; a <= 3; a++) { try { await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 45000 }); return; } catch (e) { if (a === 3) throw new Error('BLOCKED: ' + e.message.split('\n')[0]); await page.waitForTimeout(8000); } } }

await goto();
await page.waitForTimeout(1500);
await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '11'); // OLD SCANNED VF-7/12
await page.waitForTimeout(2500);
await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlVillage', VIL);
// the old-scanned dropdown is huge & slow; wait until OUR specific option actually appears
// (not just length>1 — that races with a partial/transient postback list)
const NORM_FN = `(s)=>{const GU={'૦':'0','૧':'1','૨':'2','૩':'3','૪':'4','૫':'5','૬':'6','૭':'7','૮':'8','૯':'9'};return String(s).replace(/[૦-૯]/g,c=>GU[c]||c).replace(/પ/g,'p').toLowerCase().replace(/[\\s/|\\\\]+/g,'/').replace(/^\\/+|\\/+$/g,'').replace(/p\\/(?=\\d)/g,'p');}`;
try {
  await page.waitForFunction(([t, nf]) => { const norm = eval('(' + nf + ')'); const sel = document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno'); return sel && sel.options.length > 50 && Array.from(sel.options).some((o) => norm(o.textContent) === t); }, [target, NORM_FN], { timeout: 60000 });
} catch {
  const diag = await page.evaluate(() => { const sel = document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno'); return { count: sel?.options.length || 0, sample: sel ? Array.from(sel.options).slice(0, 12).map((o) => o.textContent.trim()) : [] }; });
  console.log('survey option never appeared for', target, '— dropdown diag:', JSON.stringify(diag));
  await page.waitForTimeout(2000);
}
const val = await page.evaluate(([t, nf]) => { const norm = eval('(' + nf + ')'); const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno').options).find((o) => norm(o.textContent) === t); return o ? o.value : null; }, [target, NORM_FN]);
if (!val) { console.log('survey not found in old list:', target, '— leaving browser open 60s so you can pick it manually'); await page.waitForTimeout(60000); await ctx.close(); process.exit(1); }
await page.selectOption('#ContentPlaceHolder1_ddlOldScannedSno', val);
console.log('selected old-scanned survey:', val);
await setBanner(`🤖 VF-7/12 ${target} — 1) type CAPTCHA + "Get Record Detail"  2) click one "View PDF". Take your time.`, '#0b5');

// CONTINUOUS RECORDER: snapshot whenever any page meaningfully changes, for ~4.5 min.
// Robust to inline-postback, navigation, or new-tab behaviour — no fragile single trigger.
const snap = async (p, name) => { try { writeFileSync(join(DIR, name + '.html'), await p.content()); await p.screenshot({ path: join(DIR, name + '.png'), fullPage: true }); return true; } catch { return false; } };
const probe = (p) => p.evaluate(() => ({
  url: location.href,
  hasGo: !!document.querySelector('#ContentPlaceHolder1_btnGo'),
  tables: document.querySelectorAll('table').length,
  rows: document.querySelectorAll('table tr').length,
  pdfCtrls: Array.from(document.querySelectorAll('a,input[type=button],input[type=submit],input[type=image],button'))
    .map((c) => ({ t: (c.value || c.textContent || '').trim().slice(0, 30), id: c.id, oc: c.getAttribute('onclick'), href: c.getAttribute('href'), src: c.getAttribute('src') }))
    .filter((c) => /pdf|scan|view|જુઓ|report|doc/i.test(JSON.stringify(c))).slice(0, 12),
})).catch((e) => ({ url: p.url(), err: e.message }));

console.log('FORM READY — solve CAPTCHA + Get Record Detail, then click a View PDF. Recording ~4.5 min…');
let lastSig = '', tick = 0, savedResults = false;
for (let waited = 0; waited < 270000; waited += 3000) {
  await page.waitForTimeout(3000); tick++;
  const pages = ctx.pages();
  const states = [];
  for (const p of pages) states.push(await probe(p));
  const sig = JSON.stringify(states) + '|pdf=' + hits.length;
  if (sig !== lastSig) {
    lastSig = sig;
    console.log(`[t+${waited / 1000}s] pdfHits=${hits.length} ::`, JSON.stringify(states));
    // a page that left the form (no Get button / different url / is a popup) is the results/PDF page
    const progressed = pages.find((p) => /InfoSurvey|Scan|pdf|Report|VF/i.test(p.url())) || pages.find((p, i) => states[i] && !states[i].hasGo && !states[i].err) || popup;
    if (progressed && !savedResults) { savedResults = await snap(progressed, 'results'); await progressed.evaluate(() => 0).then(async () => { const ui = await progressed.evaluate(() => ({ links: Array.from(document.querySelectorAll('a')).map((a) => ({ text: a.textContent.trim(), href: a.getAttribute('href'), onclick: a.getAttribute('onclick'), id: a.id })), inputs: Array.from(document.querySelectorAll('input[type=button],input[type=submit],button,input[type=image]')).map((b) => ({ text: (b.value || b.textContent || '').trim(), id: b.id, name: b.name, onclick: b.getAttribute('onclick'), src: b.getAttribute('src') })), tableRows: Array.from(document.querySelectorAll('table tr')).map((tr) => Array.from(tr.cells).map((c) => c.innerText.trim())).filter((r) => r.some((c) => c)), bodyText: (document.body.innerText || '').slice(0, 6000) })); writeFileSync(join(DIR, 'results-ui.json'), JSON.stringify(ui, null, 2)); console.log('   saved results.html/png + results-ui.json from', progressed.url()); }).catch(() => {}); }
    await snap(pages[pages.length - 1], 'latest');
  }
  if (hits.length > 0 && savedResults) { console.log('got PDF + results — wrapping up in 6s'); await page.waitForTimeout(6000); break; }
}
writeFileSync(join(DIR, 'pdf-hits.json'), JSON.stringify(hits, null, 2));
console.log('\n=== DONE ===  pdf responses captured:', hits.length, '| results saved:', savedResults, '| ticks:', tick);
console.log('artifacts in', DIR);
await setBanner('✅ Captured. You can close this window.', '#06c');
await page.waitForTimeout(1500);
await ctx.close();
