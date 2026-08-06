// Scrapes whatever survey results are currently loaded in the live Chrome list page.
//   node scrape-current.mjs 221/p
import { chromium } from 'playwright-core';
import { ensureOut } from './src/store.mjs';
import { processSurvey } from './src/scrape.mjs';

const surveyKey = process.argv[2];
if (!surveyKey) { console.error('usage: node scrape-current.mjs <surveyKey>'); process.exit(1); }

ensureOut();
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const listPage = ctx.pages().find((p) => /ViewSurveyList/i.test(p.url())) || ctx.pages()[0];

console.log('Scraping currently-loaded results for', surveyKey);
await processSurvey(ctx, listPage, surveyKey, console.log);
await browser.close();
