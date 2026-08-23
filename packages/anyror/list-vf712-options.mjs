// Cascade to Salun Talpad VF-7/12 and dump the old-scanned survey dropdown options.
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { REPO } from '../core/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const LAND = '11', DIST = '16', TAL = '08', VIL = '072';
const filter = (process.argv.find((a) => a.startsWith('--grep=')) || '').split('=')[1] || '125';

const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror', {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1500,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();
await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(1500);
await page.selectOption('#ContentPlaceHolder1_drpLandRecord', LAND);
await page.waitForTimeout(2500);
await page.selectOption('#ContentPlaceHolder1_ddlDistrict', DIST);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlTaluka', TAL);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlVillage', VIL);
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlOldScannedSno')?.options.length > 5, { timeout: 90000 });
const opts = await page.$$eval('#ContentPlaceHolder1_ddlOldScannedSno option', (os) => os.map((o) => ({ v: o.value, t: o.textContent.trim() })));
writeFileSync(REPO+'/anyror/vf712-salun-options.json', JSON.stringify(opts, null, 1));
console.log('total options:', opts.length);
const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const lat = (s) => s.replace(/[૦-૯]/g, (c) => GU[c] || c);
console.log('matching /' + filter + '/:');
for (const o of opts) if (lat(o.t).includes(filter)) console.log('   ', JSON.stringify(o.t), '->', o.v);
console.log('LIST DONE — browser stays open');
