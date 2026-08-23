// Offline preview: render the saved detail HTML (CSS pulled from the live server via <base>)
// through the shared formatter — lets us iterate the PDF without re-solving a CAPTCHA.
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync } from 'node:fs';
import { applyCleanFormat, PDF_OPTS } from './format.mjs';
import { REPO } from '../core/repo-root.mjs';

// patch the saved HTML with a <base> so ../css/... resolves to the live server
let html = readFileSync(REPO+'/anyror/detail-page.html', 'utf8');
if (!/<base /i.test(html)) html = html.replace(/<head([^>]*)>/i, '<head$1><base href="https://anyror.gujarat.gov.in/Information_pages/">');
writeFileSync(REPO+'/anyror/detail-local.html', html);

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
await page.goto('file://'+REPO+'/anyror/detail-local.html', { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
await page.waitForTimeout(1500);

const meta = await applyCleanFormat(page, '221/p');
await page.waitForTimeout(300);
await page.pdf({ path: REPO+'/anyror/AnyRoR_221_P_v6.pdf', ...PDF_OPTS });
console.log('rendered v6 |', JSON.stringify(meta));
await browser.close();
