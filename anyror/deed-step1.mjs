// Valetva/41 (Kheda / Nadiad Gramya) — Integrated Survey Details, to pull registered DEEDS.
// You can't touch the browser (Tailscale), so: I cascade + screenshot the CAPTCHA, you read the
// digits and I write them to captcha-ans.txt, I submit, reach the detail page, dump the deed links,
// and KEEP THE BROWSER OPEN (CDP :9222) so the downloader can run without a second CAPTCHA.
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from '../src/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const DIR = REPO+'/output/Valetva_41';
const ANS = REPO+'/anyror/captcha-ans.txt';
const STOP = REPO+'/anyror/deed-stop.txt';
const SHOT = REPO+'/anyror/valetva_captcha.png';
mkdirSync(DIR, { recursive: true });
rmSync(ANS, { force: true }); rmSync(STOP, { force: true });

const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const deGu = (s) => String(s).replace(/[૦-૯]/g, (c) => GU[c] || c);

const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror', {
  channel: 'chrome', headless: false, viewport: null, acceptDownloads: true,
  args: ['--remote-debugging-port=9222', '--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();

async function goto() { for (let a = 1; a <= 3; a++) { try { await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 45000 }); return; } catch (e) { if (a === 3) throw new Error('BLOCKED: ' + e.message.split('\n')[0]); await page.waitForTimeout(8000); } } }
const selByText = async (sel, re) => {
  await page.waitForFunction((s) => document.querySelector(s)?.options.length > 1, sel, { timeout: 30000 });
  const val = await page.evaluate(({ s, re }) => { const rx = new RegExp(re); const o = Array.from(document.querySelector(s).options).find((o) => rx.test(o.textContent)); return o ? o.value : null; }, { s: sel, re: re.source });
  if (!val) throw new Error('option not found for ' + sel + ' ~ ' + re);
  await page.selectOption(sel, val); return val;
};

await goto();
await page.waitForTimeout(1500);
await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '8'); // INTEGRATED SURVEY NO DETAILS
await page.waitForTimeout(2500);
console.log('district=', await selByText('#ContentPlaceHolder1_ddlDistrict', /ખેડા/));
console.log('taluka=', await selByText('#ContentPlaceHolder1_ddlTaluka', /નડીઆદ.*ગ્રામ્ય|નડિઆદ.*ગ્રામ્ય|નડીયાદ.*ગ્રામ્ય/));
console.log('village=', await selByText('#ContentPlaceHolder1_ddlVillage', /વલેટવા/));
// survey 41
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
const surveyVal = await page.evaluate((deGuStr) => {
  const GU = JSON.parse(deGuStr); const de = (s) => String(s).replace(/[૦-૯]/g, (c) => GU[c] || c);
  const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options).find((o) => de(o.textContent).replace(/[^0-9]/g, '') === '41');
  return o ? o.value : null;
}, JSON.stringify(GU));
if (!surveyVal) throw new Error('survey 41 not found');
await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', surveyVal);
console.log('survey=', surveyVal);
await page.waitForTimeout(1500);

// headed: you solve the CAPTCHA in the window + click "Get Record Detail"
await page.evaluate(() => { let b = document.getElementById('__bot_banner'); if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); } b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:#0b5;color:#fff;font:bold 16px sans-serif;padding:10px;text-align:center'; b.textContent = '🤖 Valetva Survey 41 — type the CAPTCHA and click "Get Record Detail"'; }).catch(() => {});
console.log('WAITING for you to solve CAPTCHA + Get Record Detail in the window…');
const ok = await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 9 * 60 * 1000 }).then(() => true).catch(() => false);
if (!ok) {
  await page.screenshot({ path: join(DIR, 'after_submit.png') }).catch(() => {});
  console.log('SUBMIT_DID_NOT_REACH_DETAIL (wrong captcha?) — screenshot saved. Browser stays open.');
} else {
  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForTimeout(2000);
  writeFileSync(join(DIR, 'valetva_41_detail.html'), await page.content());
  await page.screenshot({ path: join(DIR, 'detail_full.png'), fullPage: true }).catch(() => {});
  const deeds = await page.evaluate(() => Array.from(document.querySelectorAll('a')).filter((a) => /view deed|deed|દસ્તાવેજ/i.test(a.textContent)).map((a) => ({ text: a.textContent.replace(/\s+/g, ' ').trim(), href: a.getAttribute('href'), onclick: a.getAttribute('onclick'), id: a.id })));
  writeFileSync(join(DIR, 'deed_links.json'), JSON.stringify(deeds, null, 2));
  console.log('DETAIL_REACHED. deed links:', deeds.length);
  console.log(JSON.stringify(deeds.slice(0, 20), null, 1));
}

// keep the browser (and the solved session) alive for the downloader on CDP :9222
console.log('BROWSER_OPEN_ON_CDP_9222 — idling up to 40 min (touch deed-stop.txt to close)');
for (let i = 0; i < 480 && !existsSync(STOP); i++) await page.waitForTimeout(5000);
await ctx.close();
console.log('CLOSED');
