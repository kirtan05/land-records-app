import { chromium } from 'playwright-core';
import { searchWithAutoCaptcha } from '../solvers/ircms.mjs';
import { extractCaseList, openDetailByKey, extractOrderForms, downloadOrderBytes } from '../../core/scrape.mjs';
import { REPO } from '../../core/repo-root.mjs';
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
await page.selectOption('#sel_survey_no', '14/૨/૩ ~~ ');
const out = await searchWithAutoCaptcha(page);
console.log('search:', JSON.stringify(out));
const cases = await extractCaseList(page);
const detail = await openDetailByKey(ctx, page, cases[0]);
await detail.waitForLoadState('domcontentloaded').catch(() => {});
await detail.waitForTimeout(2500);
const forms = await extractOrderForms(detail);
console.log('order forms:', JSON.stringify(forms, null, 1));
if (forms[0]) {
  const r = await downloadOrderBytes(ctx, forms[0]);
  console.log('status:', r.status, 'ctype:', r.ctype); console.log('BODY>>>', r.buf.slice(0, 400).toString('latin1'), '<<<END');
}
await ctx.close();
