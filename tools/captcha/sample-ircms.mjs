// Harvest N iRCMS captcha samples. iRCMS captcha is an SVG served as JSON
// (POST /return_captcha, session cookie + _token). The ANSWER is inside the SVG
// as plain <text> nodes — so these samples are SELF-LABELED (no tagging needed),
// and the production solver for iRCMS is a deterministic SVG parse, not OCR.
//
// Saves: samples/ircms/NNN.svg  samples/ircms/NNN.png  labels.csv (auto-filled)
//   node tools/captcha/sample-ircms.mjs [--n=100] [--delay=400]
import { chromium } from 'playwright-core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = new URL('./samples/ircms/', import.meta.url).pathname;
mkdirSync(DIR, { recursive: true });
const N = +(process.argv.find((a) => a.startsWith('--n='))?.split('=')[1] || 100);
const DELAY = +(process.argv.find((a) => a.startsWith('--delay='))?.split('=')[1] || 400);

// NOTE: iRCMS serves a FortiWeb block page to headless Chrome — must run HEADED.
const browser = await chromium.launch({ channel: 'chrome', headless: false, args: ['--window-size=1200,800'] });
const page = await browser.newPage();
await page.goto('https://ircms.gujarat.gov.in/ViewSurveyList', { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForSelector('input[name="_token"]', { state: 'attached', timeout: 30000 });
const TOKEN = await page.evaluate(() => document.querySelector('input[name="_token"]').value);
const render = await browser.newPage(); // scratch page so the form page keeps its token/session

const labels = [];
for (let i = 1; i <= N; i++) {
  // Ask the site for a fresh captcha from inside the page (carries cookies + CSRF token)
  const svg = await page.evaluate(async (tok) => {
    const r = await fetch('https://ircms.gujarat.gov.in/return_captcha', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: '_token=' + encodeURIComponent(tok),
    });
    const j = await r.json();
    return j.captcha_svg;
  }, TOKEN);
  if (!svg) { console.log(`${i}: empty response — skipping`); await page.waitForTimeout(1000); continue; }

  // label = <text> nodes sorted by x (verify the page's own answer layout)
  const label = await page.evaluate((svgStr) => {
    const doc = new DOMParser().parseFromString(svgStr, 'image/svg+xml');
    return Array.from(doc.querySelectorAll('text'))
      .map((t) => ({ x: +t.getAttribute('x'), ch: t.textContent }))
      .sort((a, b) => a.x - b.x).map((t) => t.ch).join('');
  }, svg);

  const id = String(i).padStart(3, '0');
  writeFileSync(join(DIR, `${id}.svg`), svg);

  // render PNG at the SVG's natural size via the scratch page (for the OCR eval)
  await render.setContent(`<body style="margin:0"><img id="c" style="display:block"></body>`);
  await render.evaluate((svgStr) => {
    document.getElementById('c').src = 'data:image/svg+xml;base64,' + btoa(svgStr);
  }, svg);
  await render.locator('#c').screenshot({ path: join(DIR, `${id}.png`) });

  labels.push([id, label]);
  if (i % 10 === 0) console.log(`${i}/${N} — last: ${label}`);
  await page.waitForTimeout(DELAY);
}
writeFileSync(join(DIR, 'labels.csv'), 'file,label\n' + labels.map(([f, l]) => `${f},${l}`).join('\n') + '\n');
console.log(`DONE: ${labels.length}/${N} samples in ${DIR}`);
const chars = [...new Set(labels.map(([, l]) => l).join(''))].sort().join('');
const lens = [...new Set(labels.map(([, l]) => l.length))].sort();
console.log(`charset seen: "${chars}"  lengths: ${lens.join(',')}`);
await browser.close();
