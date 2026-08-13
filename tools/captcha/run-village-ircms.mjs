// Full-village iRCMS scrape for Bharoda — NO human captcha. Loops every survey in
// survey-catalog.json: cascade → auto-solve SVG captcha → direct search → scrape cases
// (case PDFs + order PDFs + JSON/CSV via the proven src/scrape.mjs machinery) → next.
// Resumable via output/_state.json; gentle jittered pacing; WAF-aware (aborts on block).
//
//   node tools/captcha/run-village-ircms.mjs                # all 1535 surveys, resume
//   node tools/captcha/run-village-ircms.mjs --only=221/p   # one survey (smoke test)
//   node tools/captcha/run-village-ircms.mjs --from=400     # start at index 400
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { ensureOut, isDone, markSurvey } from '../../src/store.mjs';
import { processSurvey } from '../../src/scrape.mjs';
import { searchWithAutoCaptcha } from './ircms-solve.mjs';

const ANAND = '15', UMRETH = '03', BHARODA = '029';
const PAGE_URL = 'https://ircms.gujarat.gov.in/ViewSurveyList';

const only = (process.argv.find((a) => a.startsWith('--only=')) || '').split('=')[1];
const from = +(process.argv.find((a) => a.startsWith('--from='))?.split('=')[1] || 0);
const catalog = JSON.parse(readFileSync(new URL('../../survey-catalog.json', import.meta.url), 'utf8'));
const targets = only
  ? catalog.rows.filter((r) => r.normalized === only)
  : catalog.rows.slice(from);
if (!targets.length) { console.log('no targets (bad --only?)'); process.exit(1); }
console.log(`${targets.length} surveys to process (${catalog.count} in village)`);

ensureOut();
// Headed — iRCMS serves a FortiWeb block page to headless Chrome.
const ctx = await chromium.launchPersistentContext('/home/kirtan/Desktop/projects/irmsc/.chrome-profile-ircms', {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1300,900', '--window-position=60,30', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || (await ctx.newPage());

const jitter = (ms) => page.waitForTimeout(ms + Math.floor(Math.random() * ms * 0.6));
const isBlocked = () => page.evaluate(() => /blocked|fortiweb|attack/i.test(document.title + ' ' + (document.body?.innerText || '').slice(0, 500))).catch(() => false);

async function ensureGeo() {
  const ok = await page.evaluate(() =>
    document.querySelector('#sel_village')?.value === '029' && document.querySelector('#sel_survey_no')?.options.length > 1
  ).catch(() => false);
  if (ok) return;
  await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('input[name="_token"]', { state: 'attached', timeout: 30000 });
  await page.selectOption('#sel_district', ANAND);
  await page.waitForFunction(() => document.querySelector('#sel_taluka')?.options.length > 1, { timeout: 20000 });
  await page.selectOption('#sel_taluka', UMRETH);
  await page.waitForFunction(() => document.querySelector('#sel_village')?.options.length > 1, { timeout: 20000 });
  await page.selectOption('#sel_village', BHARODA);
  await page.waitForFunction(() => document.querySelector('#sel_survey_no')?.options.length > 1, { timeout: 25000 });
}

for (let i = 0; i < targets.length; i++) {
  const key = targets[i].normalized;
  const tag = `[${from + i + 1}/${catalog.count}] ${key}`;
  if (isDone(key)) { console.log(`${tag} done — skip`); continue; }
  if (await isBlocked()) { console.log('WAF BLOCK — aborting; re-run later to resume.'); break; }

  try {
    await ensureGeo();
    await page.selectOption('#sel_survey_no', targets[i].value);
    await page.evaluate(() => { const tb = document.querySelector('#surveylist_table tbody'); if (tb) tb.innerHTML = ''; });

    const outcome = await searchWithAutoCaptcha(page);
    if (outcome.kind === 'rows') {
      console.log(`${tag} ${outcome.rows} cases — scraping…`);
      await processSurvey(ctx, page, key, (m) => console.log(m));
    } else if (outcome.kind === 'norecord') {
      console.log(`${tag} no cases`);
      markSurvey(key, { status: 'done', case_count: 0, orders: 0, note: 'no_cases', finished_at: new Date().toISOString() });
    } else {
      console.log(`${tag} ERROR: ${outcome.err}`);
      markSurvey(key, { status: 'error', error: outcome.err });
    }
  } catch (e) {
    console.log(`${tag} EXCEPTION: ${e.message.split('\n')[0]}`);
    markSurvey(key, { status: 'error', error: e.message.split('\n')[0] });
    await page.waitForTimeout(4000);
  }
  await jitter(1500); // gentle — never a metronome
}
console.log('VILLAGE RUNNER DONE');
await ctx.close();
