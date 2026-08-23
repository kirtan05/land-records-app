// READ-ONLY recon of AnyRoR Rural Land Record (ASP.NET WebForms).
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { REPO } from '../src/repo-root.mjs';

const URL = 'https://anyror.gujarat.gov.in/LandRecordRural.aspx/1000';
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = await ctx.newPage();
try {
  const resp = await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(2500);
  console.log('status', resp && resp.status(), '| title', await page.title(), '| url', page.url());

  const info = await page.evaluate(() => {
    const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const selects = Array.from(document.querySelectorAll('select')).map((s) => ({
      id: s.id, name: s.name, opts: s.options.length,
      sample: Array.from(s.options).slice(0, 6).map((o) => ({ v: o.value, t: clean(o.textContent) })),
    }));
    const imgs = Array.from(document.querySelectorAll('img')).map((i) => ({ id: i.id, src: (i.getAttribute('src') || '').slice(0, 70) }))
      .filter((i) => /captcha|code|verif/i.test(i.id + i.src));
    const inputs = Array.from(document.querySelectorAll('input')).map((i) => ({ id: i.id, name: i.name, type: i.type })).filter((i) => i.type !== 'hidden');
    const hidden = Array.from(document.querySelectorAll('input[type=hidden]')).map((i) => i.name);
    const btns = Array.from(document.querySelectorAll('input[type=submit],input[type=button],button,a.btn')).map((b) => ({ id: b.id, name: b.name, val: clean(b.value || b.textContent) }));
    return { selects, imgs, inputs, hidden, btns };
  });
  console.log(JSON.stringify(info, null, 2));
  writeFileSync(REPO+'/anyror/recon-page.html', await page.content());
  await page.screenshot({ path: REPO+'/anyror/recon-1.png' });
  console.log('saved recon-1.png + recon-page.html ; tab left open');
} catch (e) { console.log('ERR', e.message); }
await browser.close();
