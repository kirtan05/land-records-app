// Verifies order download: open disposed case N, read the #download_file form,
// replay the POST to download_order with the live session, save the PDF bytes.
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';

const N = parseInt(process.argv[2] || '3', 10);
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const list = ctx.pages().find((p) => /ViewSurveyList/i.test(p.url())) || ctx.pages()[0];

const [detail] = await Promise.all([
  ctx.waitForEvent('page', { timeout: 30000 }),
  list.locator('#surveylist_table tbody tr').nth(N - 1).locator('.view_status').click(),
]);
await detail.waitForLoadState('domcontentloaded').catch(() => {});
await detail.waitForTimeout(3000);

const form = await detail.evaluate(() => {
  const f = document.querySelector('#download_file');
  const orders = Array.from(document.querySelectorAll('table')).flatMap((t) => {
    const txt = t.innerText || '';
    if (!/Order\/Judgement|Order Details/i.test(txt)) return [];
    return Array.from(t.querySelectorAll('tbody tr, tr')).map((tr) => Array.from(tr.cells).map((c) => c.innerText.replace(/\s+/g, ' ').trim())).filter((r) => r.length > 2);
  });
  if (!f) return { found: false, orders };
  const fd = {};
  f.querySelectorAll('input').forEach((i) => (fd[i.name] = i.value));
  return { found: true, action: new URL(f.getAttribute('action'), location.href).href, fields: fd, orders };
});
console.log('order form:', JSON.stringify({ found: form.found, action: form.action, fieldNames: form.fields && Object.keys(form.fields) }, null, 2));
console.log('order table rows:', JSON.stringify(form.orders, null, 2));

if (form.found) {
  const resp = await ctx.request.post(form.action, { form: form.fields });
  const buf = await resp.body();
  const head = buf.slice(0, 5).toString('latin1');
  writeFileSync(`./test-order-${N}.pdf`, buf);
  console.log(`\nPOST ${form.action} -> status ${resp.status()} | ${resp.headers()['content-type']} | ${buf.length} bytes | magic="${head}" | isPDF=${head.startsWith('%PDF')}`);
}
await detail.close();
await browser.close();
