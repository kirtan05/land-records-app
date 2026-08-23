// RECON: what does AnyRoR record type 6 (Old Scanned VF-6 / જુના હક્ક પત્રક) ask for?
// Cascade to Anand/Umreth/Bhalej under type 6 and dump every visible form control.
//   node packages/captcha/recon-vf6.mjs
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';

const ctx = await chromium.launchPersistentContext(join(ROOT, '.chrome-profile-anyror-auto'), {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--window-size=1400,1000', '--window-position=0,0', '--no-first-run', '--no-default-browser-check'],
});
const page = ctx.pages()[0] || (await ctx.newPage());
page.on('dialog', (d) => d.dismiss().catch(() => {}));

const dump = async (tag) => {
  const d = await page.evaluate(() => ({
    url: location.href,
    controls: Array.from(document.querySelectorAll('select,input,textarea')).filter((e) => {
      const s = getComputedStyle(e); return s.display !== 'none' && s.visibility !== 'hidden' && e.type !== 'hidden';
    }).map((e) => ({
      tag: e.tagName, id: e.id, name: e.name, type: e.type || '', value: e.value,
      label: (document.querySelector(`label[for="${e.id}"]`)?.innerText || '').replace(/\s+/g, ' ').trim(),
      near: (e.closest('div,td,tr')?.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 90),
      nopts: e.options ? e.options.length : undefined,
      opts: e.options ? Array.from(e.options).slice(0, 6).map((o) => `${o.value}=${(o.text || '').trim()}`) : undefined,
    })),
  }));
  console.log(`\n===== ${tag} =====\n` + JSON.stringify(d, null, 1));
  return d;
};

await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(1800);
const types = await page.$$eval('#ContentPlaceHolder1_drpLandRecord option', (os) => os.map((o) => `${o.value} = ${o.text.trim()}`));
console.log('RECORD TYPES:\n' + types.join('\n'));

await page.selectOption('#ContentPlaceHolder1_drpLandRecord', '6');
await page.waitForTimeout(2500);
await dump('after type=6');

await page.selectOption('#ContentPlaceHolder1_ddlDistrict', '15');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlTaluka')?.options.length > 1, { timeout: 30000 });
await page.selectOption('#ContentPlaceHolder1_ddlTaluka', '03');
await page.waitForFunction(() => document.querySelector('#ContentPlaceHolder1_ddlVillage')?.options.length > 1, { timeout: 30000 });
await page.waitForTimeout(1200);
await page.selectOption('#ContentPlaceHolder1_ddlVillage', '027');
await page.waitForTimeout(3000);
const d = await dump('after Anand/Umreth/Bhalej under type=6');
writeFileSync(join(ROOT, 'packages/captcha/recon-vf6.json'), JSON.stringify(d, null, 1));
writeFileSync(join(ROOT, 'packages/captcha/recon-vf6.html'), await page.content());
console.log('\nsaved recon-vf6.json / .html');
await page.waitForTimeout(1000);
await ctx.close();
