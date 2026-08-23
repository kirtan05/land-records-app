// Inspect InfoSurveyNoDetail.aspx: find the watermark layer + structure; baseline PDF.
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { REPO } from '../src/repo-root.mjs';

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => /InfoSurveyNoDetail|SurveyNoDetail/i.test(p.url()))
  || ctx.pages().find((p) => /anyror/i.test(p.url()));
console.log('detail url:', page.url(), '| title:', await page.title());

const info = await page.evaluate(() => {
  const WM = 'જેનો બીજો કોઈ ઉપયોગ';
  const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
  // 1) where does the watermark phrase live?
  const bodyHtml = document.body.innerHTML;
  const phraseCount = (document.body.innerText.match(/જેનો બીજો કોઈ ઉપયોગ/g) || []).length;
  // elements whose OWN text is exactly the watermark phrase (tiled text nodes)
  const wmEls = Array.from(document.querySelectorAll('*')).filter((e) => e.children.length === 0 && /જેનો બીજો કોઈ ઉપયોગ/.test(e.textContent || ''));
  const wmSample = wmEls.slice(0, 4).map((e) => ({ tag: e.tagName, cls: e.className, id: e.id, pos: getComputedStyle(e).position, color: getComputedStyle(e).color }));
  // 2) background-images in use
  const bg = new Map();
  for (const e of Array.from(document.querySelectorAll('*'))) {
    const b = getComputedStyle(e).backgroundImage;
    if (b && b !== 'none') { const k = b.slice(0, 40); bg.set(k, (bg.get(k) || 0) + 1); }
  }
  // 3) elements with watermark-ish class/id
  const wmClass = Array.from(document.querySelectorAll('[class*=water],[id*=water],[class*=mark],[class*= wm]')).slice(0, 8).map((e) => ({ tag: e.tagName, cls: e.className, id: e.id }));
  // 4) structure
  const tables = document.querySelectorAll('table').length;
  const headings = [...new Set(Array.from(document.querySelectorAll('b,strong,th,h1,h2,h3,h4,.panel-heading')).map((e) => clean(e.textContent)).filter((t) => t && t.length < 50))].slice(0, 40);
  return { phraseCount, wmElCount: wmEls.length, wmSample, bgImages: [...bg.entries()], wmClass, tables, headings, bodyLen: document.body.innerText.length };
});
console.log(JSON.stringify(info, null, 2));

writeFileSync(REPO+'/anyror/detail-page.html', await page.content());
await page.screenshot({ path: REPO+'/anyror/detail-full.png', fullPage: true });
try { await page.pdf({ path: REPO+'/anyror/detail-baseline.pdf', format: 'A4', printBackground: true }); console.log('baseline pdf OK'); }
catch (e) { console.log('pdf err', e.message); }
console.log('saved detail-page.html, detail-full.png, detail-baseline.pdf');
await browser.close();
