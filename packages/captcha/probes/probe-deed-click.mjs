// PROBE: what actually happens when you CLICK "View Deed" in a real browser?
// The blind form-POST replay just gets the page back, so watch the click: download event, popup,
// navigation, or an alert.
//   node packages/captcha/probes/probe-deed-click.mjs --survey=174/p1
import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { REPO } from '../../core/repo-root.mjs';

const ROOT = REPO;
const FORM_URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const arg = (k, d) => { const v = process.argv.find((a) => a.startsWith(`--${k}=`)); return v ? v.split('=')[1] : d; };
const SURVEY = arg('survey', '174/p1');
const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const DUMP = join(ROOT, 'packages/captcha/probes/deed-probe');
mkdirSync(DUMP, { recursive: true });

const py = spawn(join(ROOT, 'packages/captcha/.venv/bin/python'), [join(ROOT, 'packages/captcha/pipeline/4-infer.py'), '--serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
const rl = createInterface({ input: py.stdout });
const q = [];
rl.on('line', (l) => q.shift()?.(l));
const solve = (b) => new Promise((r) => { q.push(r); py.stdin.write(`b64:${b}\n`); }).then((l) => l.split('\t')[0]);

const ctx = await chromium.launchPersistentContext(join(ROOT, '.chrome-profile-anyror-auto'), {
  channel: 'chrome', headless: false, viewport: null, acceptDownloads: true,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check', '--hide-crash-restore-bubble'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
page.on('dialog', async (d) => { console.log('DIALOG:', d.message()); await d.dismiss().catch(() => {}); });
ctx.on('page', (p) => console.log('POPUP opened:', p.url()));
page.on('response', (r) => { const ct = r.headers()['content-type'] || ''; if (/tiff|image|pdf|octet/i.test(ct)) console.log('RESPONSE', r.status(), ct, r.url().slice(0, 140)); });

await page.goto(FORM_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(1800);
await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '8');
await page.waitForTimeout(2400);
await page.selectOption('#ContentPlaceHolder1_ddlDistrict', '15');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlTaluka', '03');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
await page.waitForTimeout(1000);
await page.selectOption('#ContentPlaceHolder1_ddlVillage', '027');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlSurveyNo')?.options.length > 1, { timeout: 30000 });
await page.waitForTimeout(1000);
const val = await page.evaluate(({ want, GU }) => {
  const norm = (s) => String(s).replace(/~~/g, ' ').replace(/[૦-૯]/g, (c) => GU[c] || c).replace(/પૈકી/g, 'p').replace(/પ/g, 'p')
    .toLowerCase().replace(/[\s/|\\]+/g, '/').replace(/^\/+|\/+$/g, '').replace(/p\/(?=\d)/g, 'p').replace(/\/p/g, 'p');
  const o = Array.from(document.querySelector('#ContentPlaceHolder1_ddlSurveyNo').options).find((o) => norm(o.textContent) === norm(want));
  return o ? o.value : '';
}, { want: SURVEY, GU });
await page.selectOption('#ContentPlaceHolder1_ddlSurveyNo', val);

for (let t = 1; t <= 4; t++) {
  const b64 = await page.evaluate(() => ((document.querySelector('#ContentPlaceHolder1_i_captcha_1')?.src || '').match(/base64,(.+)$/) || [, ''])[1]);
  const code = await solve(b64);
  await page.fill('#ContentPlaceHolder1_txtCaptcha_1, input[id*="captcha" i][type="text"]', code);
  await page.click('#ContentPlaceHolder1_btnGo', { noWaitAfter: true, timeout: 20000 });
  await page.waitForURL(/InfoSurveyNoDetail/i, { timeout: 90000 }).catch(() => {});
  await page.waitForTimeout(1500);
  console.log(`captcha ${t}: ${code} → ${/InfoSurveyNoDetail/i.test(page.url()) ? 'ACCEPTED' : 'no'}`);
  if (/InfoSurveyNoDetail/i.test(page.url())) break;
  await page.waitForTimeout(2500);
  await Promise.all([page.waitForLoadState('domcontentloaded').catch(() => {}), page.evaluate(() => { try { __doPostBack('ctl00$ContentPlaceHolder1$lb_refresh_1', ''); } catch (e) {} })]);
  await page.waitForTimeout(1500);
}
if (!/InfoSurveyNoDetail/i.test(page.url())) { console.log('never reached detail'); await ctx.close(); process.exit(1); }

const links = await page.$$eval("#ContentPlaceHolder1_gvgarviProDet a", (as) => as.map((a) => ({ id: a.id, href: a.getAttribute('href'), text: (a.textContent || '').trim() })));
console.log('deed links:', JSON.stringify(links, null, 1));
if (!links.length) { console.log('no deed links'); await ctx.close(); process.exit(0); }

const before = page.url();
console.log('\n--- clicking the first View Deed ---');
const dl = page.waitForEvent('download', { timeout: 45000 }).catch(() => null);
const popup = ctx.waitForEvent('page', { timeout: 45000 }).catch(() => null);
await page.click(`#${links[0].id}`, { noWaitAfter: true, timeout: 20000 }).catch((e) => console.log('click err', e.message));
const got = await Promise.race([dl, popup, page.waitForTimeout(30000).then(() => 'timeout')]);
await page.waitForTimeout(3000);

if (got && got.suggestedFilename) {
  const p = join(DUMP, got.suggestedFilename());
  await got.saveAs(p);
  console.log('DOWNLOAD →', p);
} else if (got && got.url) {
  console.log('POPUP url:', got.url());
  await got.waitForLoadState('domcontentloaded').catch(() => {});
  writeFileSync(join(DUMP, 'popup.html'), await got.content().catch(() => ''));
  console.log('popup text:', (await got.evaluate(() => (document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 400)).catch(() => '?')));
} else {
  console.log('no download, no popup. url now:', page.url(), '(was', before + ')');
  const txt = await page.evaluate(() => (document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 600));
  console.log('page text:', txt);
  writeFileSync(join(DUMP, 'after-click.html'), await page.content());
  await page.screenshot({ path: join(DUMP, 'after-click.png'), fullPage: false }).catch(() => {});
}
py.stdin.end();
await ctx.close();
