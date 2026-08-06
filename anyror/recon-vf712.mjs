// Recon for OLD SCANNED VF-7/12: dump record-type options + the scanned survey/block dropdown
// for Bharoda (Dist 15 / Tal 03 / Vil 029). No CAPTCHA needed — cascading dropdowns are AJAX.
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync } from 'node:fs';
import { normalizeSurvey } from '../src/normalize.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const OUT = '/home/kirtan/Desktop/projects/irmsc/anyror/vf712-blocks.json';
const MATCH = '/home/kirtan/Desktop/projects/irmsc/anyror/vf712-match.json';
const DIST = '15', TAL = '03', VIL = '029';
const targets = JSON.parse(readFileSync('/home/kirtan/Desktop/projects/irmsc/survey-input.json', 'utf8')).filter((r) => r.matched);
const parentOf = (n) => normalizeSurvey(n).split('/')[0]; // leading block number

const ctx = await chromium.launchPersistentContext('/home/kirtan/Desktop/projects/irmsc/.chrome-profile-anyror', {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();

const dumpSelects = (full = false) => page.evaluate((full) => Array.from(document.querySelectorAll('select')).map((s) => ({
  id: s.id, name: s.name, n: s.options.length,
  opts: Array.from(s.options).slice(0, full ? 99999 : 60).map((o) => ({ v: o.value, t: o.textContent.trim() })),
})), full);

async function goto() {
  for (let a = 1; a <= 3; a++) {
    try { await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 45000 }); return; }
    catch (e) { if (a === 3) throw new Error('BLOCKED: ' + e.message.split('\n')[0]); await page.waitForTimeout(8000); }
  }
}

try {
  await goto();
  await page.waitForTimeout(1500);

  // 1) record-type dropdown — find the VF-7/12 option
  const recType = await page.$eval('#ContentPlaceHolder1_drpLandRecord', (s) => Array.from(s.options).map((o) => ({ v: o.value, t: o.textContent.trim() }))).catch(() => null);
  console.log('RECORD TYPE OPTIONS:'); console.log(JSON.stringify(recType, null, 1));
  const vf = recType?.find((o) => /VF.?7.?12|OLD SCANNED|સ્કેન/i.test(o.t));
  if (!vf) throw new Error('VF-7/12 option not found in drpLandRecord');
  console.log('-> selecting VF-7/12 value =', vf.v, '|', vf.t);
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', vf.v);
  await page.waitForTimeout(2500);

  console.log('SELECTS AFTER PICKING VF-7/12:'); console.log(JSON.stringify(await dumpSelects(), null, 1));

  // 2) cascade district -> taluka -> village (try standard IDs)
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlVillage', VIL);
  // block dropdown is huge (~2300 opts) and slow to populate — wait explicitly
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno')?.options.length > 1, { timeout: 45000 });
  await page.waitForTimeout(800);

  // 3) dump every select (FULL); the scanned survey/block one is whatever populated last
  const selects = await dumpSelects(true);
  const known = ['ContentPlaceHolder1_drpLandRecord', 'ContentPlaceHolder1_ddlDistrict', 'ContentPlaceHolder1_ddlTaluka', 'ContentPlaceHolder1_ddlVillage'];
  const block = selects.filter((s) => !known.includes(s.id) && s.n > 1).sort((a, b) => b.n - a.n)[0]
    || selects.find((s) => /survey|block/i.test(s.id) && s.n > 1);
  writeFileSync(OUT, JSON.stringify({ recordType: vf, blockId: block?.id, blockOpts: block?.opts, at: new Date().toISOString() }, null, 2));
  console.log('\nBLOCK DROPDOWN id =', block?.id, '| count =', block?.n);

  // 4) match our 9 survey numbers against the old-scanned list
  const old = (block?.opts || []).filter((o) => o.v !== '-1' && o.v !== '0').map((o) => ({ raw: o.t, norm: normalizeSurvey(o.t) }));
  const oldByNorm = new Map(old.map((o) => [o.norm, o]));
  const report = targets.map((t) => {
    const norm = t.normalized, parent = parentOf(norm);
    const exact = oldByNorm.get(norm) || null;
    const sameParent = old.filter((o) => parentOf(o.raw) === parent).map((o) => o.raw);
    return { survey: norm, parent, exact: exact?.raw || null, sameParentOldEntries: sameParent };
  });
  writeFileSync(MATCH, JSON.stringify({ at: new Date().toISOString(), totalOld: old.length, report }, null, 2));
  console.log('\n===== MATCH REPORT (our survey -> old-scanned entries) =====');
  for (const r of report) {
    console.log(`\n• ${r.survey}  (parent ${r.parent})`);
    console.log(`    exact old entry: ${r.exact || '— none —'}`);
    console.log(`    same-parent old entries (${r.sameParentOldEntries.length}): ${r.sameParentOldEntries.join('  ·  ') || '— none —'}`);
  }
  console.log('\nSaved ->', OUT, '\nSaved ->', MATCH);
} catch (e) {
  console.log('RECON ERROR:', e.message);
} finally {
  await page.waitForTimeout(1500);
  await ctx.close();
}
