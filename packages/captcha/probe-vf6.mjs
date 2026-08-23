// PROBE: submit ONE entry number on AnyRoR record type 6 (Old Scanned VF-6) and dump what comes back.
//   node packages/captcha/probe-vf6.mjs --entry=2536
import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const ENTRIES = arg('entry', '2536').split(',');

const py = spawn(join(ROOT, 'packages/captcha/.venv/bin/python'),
  [join(ROOT, 'packages/captcha/infer_anyror.py'), '--serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
const lines = createInterface({ input: py.stdout });
const pending = [];
lines.on('line', (l) => pending.shift()?.(l));
const solve = (b64) => new Promise((r) => { pending.push(r); py.stdin.write(`b64:${b64}\n`); }).then((l) => l.split('\t')[0]);

const ctx = await chromium.launchPersistentContext(join(ROOT, '.chrome-profile-anyror-auto'), {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
let lastDialog = '';
page.on('dialog', async (d) => { lastDialog = d.message(); await d.dismiss().catch(() => {}); });

async function cascade6() {
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(1800);
  await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '6');
  await page.waitForTimeout(2500);
  await page.selectOption('#ContentPlaceHolder1_ddlDistrict', '15');
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
  await page.selectOption('#ContentPlaceHolder1_ddlTaluka', '03');
  await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
  await page.waitForTimeout(1200);
  await page.selectOption('#ContentPlaceHolder1_ddlVillage', '027');
  await page.waitForTimeout(2500);
}

await cascade6();

for (const ENTRY of ENTRIES) {
  if (!/txt_captcha_1/.test(await page.content())) await cascade6();
  const b64 = await page.evaluate(() => (document.querySelector('#ContentPlaceHolder1_i_captcha_1')?.src || '').replace(/^data:image\/\w+;base64,/, ''));
  const code = await solve(b64);
  console.log(`\n### entry ${ENTRY} — captcha ${code}`);
  await page.fill('#ContentPlaceHolder1_txtNo', ENTRY);
  await page.fill('#ContentPlaceHolder1_txt_captcha_1', code);
  lastDialog = '';
  await page.click('#ContentPlaceHolder1_btnGo', { noWaitAfter: true, timeout: 20000 });
  await page.waitForTimeout(6000);
  const info = await page.evaluate(() => ({
    url: location.href,
    title: document.title,
    text: (document.body.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 600),
    imgs: Array.from(document.querySelectorAll('img')).map((i) => i.src).filter((s) => !/^data:/.test(s)),
    tables: Array.from(document.querySelectorAll('table')).map((t) => t.id).filter(Boolean),
    stillForm: !!document.getElementById('ContentPlaceHolder1_txtNo'),
  }));
  console.log(JSON.stringify(info, null, 1));
  if (lastDialog) console.log('DIALOG:', lastDialog);
  writeFileSync(join(ROOT, `packages/captcha/probe-vf6-${ENTRY}.html`), await page.content());
  await page.waitForTimeout(2500);
}
py.stdin.end();
await ctx.close();
