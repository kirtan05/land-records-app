// Re-render a survey's clean integrated PDF from its SAVED detail HTML — fully offline.
// AnyRoR's own stylesheets are cached in assets/anyror-css/, so this never touches the site.
//   node packages/captcha/render-bhalej-pdf.mjs                 # every output/Bhalej_*/
//   node packages/captcha/render-bhalej-pdf.mjs Bhalej_174_P1
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { applyCleanFormat, PDF_OPTS } from '../anyror/format.mjs';
import { REPO } from '../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
// ../css/… inside the saved page resolves against this (non-existent) directory, so it lands in
// assets/anyror-css/css/… — the local copy of the site's stylesheets.
const BASE = 'file://' + join(ROOT, 'assets/anyror-css/Information_pages/');
const only = process.argv[2];

const tokens = readdirSync(OUT).filter((d) => d.startsWith('Bhalej_') && (!only || d === only));
if (!tokens.length) { console.log('nothing to render'); process.exit(0); }

const browser = await chromium.launch({ channel: 'chrome', headless: true });
for (const tok of tokens) {
  const dir = join(OUT, tok);
  const htmlPath = join(dir, `anyror_${tok}.html`);
  const jsonPath = join(dir, `anyror_${tok}.json`);
  if (!existsSync(htmlPath)) { console.log(`  ${tok}: no saved HTML`); continue; }
  const meta = existsSync(jsonPath) ? JSON.parse(readFileSync(jsonPath, 'utf8')) : {};
  let html = readFileSync(htmlPath, 'utf8');
  if (!/<base /i.test(html)) html = html.replace(/<head([^>]*)>/i, `<head$1><base href="${BASE}">`);
  const local = join(dir, `.render_${tok}.html`);
  writeFileSync(local, html);

  const page = await browser.newPage({ viewport: { width: 1123, height: 900 } });
  await page.goto('file://' + local, { waitUntil: 'load', timeout: 60000 }).catch(() => {});
  await page.evaluate(() => document.fonts?.ready).catch(() => {});
  await page.waitForTimeout(900);
  await applyCleanFormat(page, meta.survey_label || meta.survey || tok, {
    district: meta.district || 'Anand', taluka: meta.taluka || 'Umreth', village: meta.village || 'Bhalej',
  });
  await page.waitForTimeout(300);
  const survey = (meta.survey || tok).replace(/\//g, '_');
  const pdfPath = join(dir, `AnyRoR_Bhalej_${survey}_Integrated.pdf`);
  await page.pdf({ path: pdfPath, ...PDF_OPTS });
  await page.close();
  console.log(`  ${tok} → ${pdfPath.split('/').pop()}`);
}
await browser.close();
console.log('OFFLINE RENDER DONE');
