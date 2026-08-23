// Probe only: cascade + auto-solve + search for a known-cases survey; prints row count. No state writes.
import { chromium } from 'playwright-core';
import { searchWithAutoCaptcha } from './ircms-solve.mjs';
import { REPO } from '../core/repo-root.mjs';
const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-ircms', {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1300,900', '--window-position=60,30', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
await page.goto('https://ircms.gujarat.gov.in/ViewSurveyList', { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('input[name="_token"]', { state: 'attached', timeout: 30000 });
await page.selectOption('#sel_district', '15');
await page.waitForFunction(() => document.querySelector('#sel_taluka')?.options.length > 1, { timeout: 20000 });
await page.selectOption('#sel_taluka', '03');
await page.waitForFunction(() => document.querySelector('#sel_village')?.options.length > 1, { timeout: 20000 });
await page.selectOption('#sel_village', '029');
await page.waitForFunction(() => document.querySelector('#sel_survey_no')?.options.length > 1, { timeout: 25000 });
const val = await page.evaluate(() => {
  const o = Array.from(document.querySelector('#sel_survey_no').options).find((o) => o.textContent.replace(/[૦-૯]/g, (c) => '૦૧૨૩૪૫૬૭૮૯'.indexOf(c)).includes('221'));
  return o?.value;
});
await page.selectOption('#sel_survey_no', val);
const out = await searchWithAutoCaptcha(page);
console.log('OUTCOME:', JSON.stringify(out));
await ctx.close();
