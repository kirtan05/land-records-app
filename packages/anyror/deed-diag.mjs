import { chromium } from 'playwright-core';
import { REPO } from '../core/repo-root.mjs';
const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror2', {
  channel: 'chrome', headless: true, viewport: { width: 1400, height: 1000 },
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  args: ['--no-first-run', '--no-default-browser-check', '--disable-blink-features=AutomationControlled', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || await ctx.newPage();
const r = await page.goto('https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch((e) => ({ err: e.message }));
await page.waitForTimeout(3000);
console.log('status:', r?.status?.() ?? r?.err);
console.log('title:', await page.title().catch(() => '?'));
const info = await page.evaluate(() => ({
  hasDrp: !!document.querySelector('#ContentPlaceHolder1_drpLandRecord'),
  selects: Array.from(document.querySelectorAll('select')).map((s) => s.id),
  bodyStart: (document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 300),
})).catch((e) => ({ err: e.message }));
console.log(JSON.stringify(info, null, 1));
await page.screenshot({ path: REPO+'/anyror/diag_headless.png' }).catch(() => {});
await ctx.close();
