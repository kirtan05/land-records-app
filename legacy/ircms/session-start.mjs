// Opens a VISIBLE Chrome (kept alive), pre-fills the iRCMS form for a survey number,
// and shows a banner. The USER solves the CAPTCHA and clicks "View" in the window.
// A separate CDP-connected script (act.mjs) then reads the live page.
//
// Usage: node session-start.mjs 221/p
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { normalizeSurvey } from './src/normalize.mjs';
import { REPO } from '../../src/repo-root.mjs';

const URL = 'https://ircms.gujarat.gov.in/ViewSurveyList';
const PROFILE = REPO+'/.chrome-profile';
const DEBUG_PORT = 9222;
const ANAND = '15', UMRETH = '03', BHARODA = '029';

const key = normalizeSurvey(process.argv[2] || '221/p');
const cat = JSON.parse(readFileSync('./survey-catalog.json', 'utf8'));
const row = cat.rows.find((r) => r.normalized === key);
if (!row) { console.error('No catalog match for', key); process.exit(1); }
console.log('Loading survey', key, '-> dropdown value', JSON.stringify(row.value));

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome',
  headless: false,
  viewport: null,
  args: [`--remote-debugging-port=${DEBUG_PORT}`, '--window-size=1500,1000', '--window-position=0,0'],
});
const page = ctx.pages()[0] || (await ctx.newPage());

await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.selectOption('#sel_district', ANAND);
await page.waitForFunction(() => document.querySelector('#sel_taluka')?.options.length > 1, { timeout: 20000 });
await page.selectOption('#sel_taluka', UMRETH);
await page.waitForFunction(() => document.querySelector('#sel_village')?.options.length > 1, { timeout: 20000 });
await page.selectOption('#sel_village', BHARODA);
await page.waitForFunction(() => document.querySelector('#sel_survey_no')?.options.length > 1, { timeout: 25000 });
await page.selectOption('#sel_survey_no', row.value);

await page.evaluate((label) => {
  let b = document.getElementById('__bot_banner');
  if (!b) { b = document.createElement('div'); b.id = '__bot_banner'; document.body.appendChild(b); }
  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:#0b5;color:#fff;font:bold 16px sans-serif;padding:10px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.3)';
  b.textContent = '🤖 Survey ' + label + ' loaded — type the CAPTCHA and click "View". (Claude will read the results.)';
}, key);

console.log('READY: Chrome open, survey', key, 'pre-filled. User: solve CAPTCHA + click View.');
console.log('CDP on http://127.0.0.1:' + DEBUG_PORT);
await new Promise(() => {}); // stay alive until this background shell is killed
