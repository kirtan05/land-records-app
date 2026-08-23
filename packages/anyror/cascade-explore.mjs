// Drives the AnyRoR cascade for INTEGRATED SURVEY NO DETAILS, Anand/Umreth/Bharoda/221-p.
// Learns the survey-dropdown id + option format and the postback behaviour.
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { normalizeSurvey } from '../core/normalize.mjs';
import { REPO } from '../core/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => /LandRecordRural/i.test(p.url())) || await ctx.newPage();

const dumpSelects = () => page.evaluate(() => Array.from(document.querySelectorAll('select')).map((s) => ({ id: s.id, n: s.options.length })));
const findVal = (id, src) => page.evaluate(({ id, src }) => {
  const rx = new RegExp(src, 'i');
  const o = Array.from(document.querySelector(id)?.options || []).find((o) => rx.test(o.textContent));
  return o ? o.value : null;
}, { id, src });

try {
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(2000);

  console.log('select land type = 8 (INTEGRATED)');
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '8');
  await page.waitForTimeout(3500);
  console.log('  selects:', JSON.stringify(await dumpSelects()));

  console.log('select district = 15 (Anand)');
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', '15');
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  const tal = await findVal('#ContentPlaceHolder1_ddlTaluka', 'ઉમરેઠ|umreth');
  console.log('  taluka Umreth value:', tal);

  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', tal);
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  const vil = await findVal('#ContentPlaceHolder1_ddlVillage', 'ભરોડા|bharoda');
  console.log('  village Bharoda value:', vil);

  await page.selectOption('#ContentPlaceHolder1_ddlVillage', vil);
  await page.waitForTimeout(3500);
  console.log('  selects after village:', JSON.stringify(await dumpSelects()));

  // find the survey dropdown: a select (not the 4 known) with many options
  const surveySel = await page.evaluate(() => {
    const known = ['ContentPlaceHolder1_drpLandRecord', 'ContentPlaceHolder1_ddlDistrict', 'ContentPlaceHolder1_ddlTaluka', 'ContentPlaceHolder1_ddlVillage'];
    const cand = Array.from(document.querySelectorAll('select')).filter((s) => !known.includes(s.id) && s.options.length > 1);
    const s = cand.sort((a, b) => b.options.length - a.options.length)[0];
    return s ? { id: s.id, name: s.name, count: s.options.length, options: Array.from(s.options).map((o) => ({ v: o.value, t: o.textContent.trim() })) } : null;
  });
  console.log('\nSURVEY dropdown:', surveySel?.id, '| count:', surveySel?.count);
  console.log('first 25 survey options:', JSON.stringify(surveySel?.options.slice(0, 25)));
  writeFileSync(REPO+'/anyror/survey-options.json', JSON.stringify(surveySel, null, 2));

  // match 221/p
  if (surveySel) {
    const match = surveySel.options.find((o) => normalizeSurvey(o.t) === '221/p');
    console.log('221/p match:', JSON.stringify(match));
    if (match) {
      await page.selectOption('#' + surveySel.id, match.v);
      console.log('selected 221/p');
    }
  }
  await page.screenshot({ path: REPO+'/anyror/cascade.png' });
  console.log('saved cascade.png');
} catch (e) { console.log('ERR', e.message); }
await browser.close();
