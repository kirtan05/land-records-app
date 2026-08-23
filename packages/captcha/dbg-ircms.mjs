import { chromium } from 'playwright-core';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
try {
  const resp = await page.goto('https://ircms.gujarat.gov.in/ViewSurveyList', { waitUntil: 'domcontentloaded', timeout: 45000 });
  console.log('status:', resp?.status(), 'url:', page.url(), 'title:', await page.title());
  const html = await page.content();
  console.log('len:', html.length, '| has _token:', html.includes('name="_token"'), '| has captcha:', html.includes('captcha'));
  console.log(html.slice(0, 400));
} catch (e) { console.log('NAV ERROR:', e.message); }
await browser.close();
