// Harvest N AnyRoR captcha PNGs. AnyRoR's captcha is a server-rendered PNG baked into the
// page as a data URI; a fresh one needs a postback (lb_refresh). AnyRoR has a WAF that
// IP-blocks bursts — so this runs HEADED, one postback every --delay ms (default 5s, jittered),
// and ABORTS at the first sign of a block page. ~100 samples ≈ 9 minutes.
//
// These PNGs have NO embedded answer → the user tags them with tag-anyror.py → labels.csv.
//
//   node packages/captcha/pipeline/1-fetch-anyror.mjs [--n=100] [--delay=5000]
import { chromium } from 'playwright-core';
import { writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from '../../core/repo-root.mjs';

const DIR = new URL('../samples/anyror', import.meta.url).pathname;
mkdirSync(DIR, { recursive: true });
// Append, never overwrite: continue numbering after the highest existing NNN.png so
// re-runs can't clobber already-tagged samples (labels.csv keys on the filename).
const START = readdirSync(DIR).filter((f) => /^\d+\.png$/.test(f)).map((f) => parseInt(f)).reduce((a, b) => Math.max(a, b), 0);
const N = +(process.argv.find((a) => a.startsWith('--n='))?.split('=')[1] || 100);
const DELAY = +(process.argv.find((a) => a.startsWith('--delay='))?.split('=')[1] || 5000);
const PAGE_URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const IMG = '#ContentPlaceHolder1_i_captcha_1';

const ctx = await chromium.launchPersistentContext(REPO+'/.chrome-profile-anyror', {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1200,900', '--window-position=40,40', '--no-first-run', '--no-default-browser-check', '--disable-session-crashed-bubble'],
});
const page = ctx.pages()[0] || (await ctx.newPage());

await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(2000);

const grabSrc = () => page.evaluate((sel) => document.querySelector(sel)?.src || '', IMG);
const isBlocked = () => page.evaluate(() => /blocked|fortiweb|attack/i.test(document.title + ' ' + document.body.innerText.slice(0, 400)));

let saved = 0;
for (let i = 1; i <= N; i++) {
  if (await isBlocked()) { console.log('WAF BLOCK PAGE DETECTED — aborting. Wait it out / change IP, re-run to continue.'); break; }
  const src = await grabSrc();
  const m = src.match(/^data:image\/png;base64,(.+)$/);
  if (m) {
    writeFileSync(join(DIR, `${String(START + i).padStart(3, '0')}.png`), Buffer.from(m[1], 'base64'));
    saved++;
    if (i % 10 === 0) console.log(`${i}/${N} (file ${START + i})`);
  } else {
    console.log(`${i}: no captcha img (src len ${src.length}) — page may not have loaded; waiting longer`);
    await page.waitForTimeout(4000);
  }
  if (i < N) {
    // full postback for a fresh captcha, then wait for it to come back
    await Promise.all([
      page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {}),
      page.evaluate(() => { try { __doPostBack('ctl00$ContentPlaceHolder1$lb_refresh_1', ''); } catch (e) {} }),
    ]);
    await page.waitForTimeout(DELAY + Math.floor(Math.random() * 1500)); // jitter — never a metronome
  }
}
console.log(`DONE: ${saved}/${N} AnyRoR captchas in ${DIR}`);
console.log('Next: python packages/captcha/pipeline/2-tag.py   (tag them → labels.csv)');
await ctx.close();
