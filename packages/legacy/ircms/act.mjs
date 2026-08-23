// Connects to the live Chrome (started by session-start.mjs) over CDP and inspects/acts
// on the current page WITHOUT disturbing the user's session.
//
//   node act.mjs dump            -> save current-page.html + print structure summary
//   node act.mjs shot [name]     -> save screenshot png (Claude reads it)
//   node act.mjs evalfile f.js   -> run JS expression from file in page, print JSON result
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync } from 'node:fs';

const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = browser.contexts()[0];
const pages = ctx.pages();
const page = pages.find((p) => /ircms\.gujarat/i.test(p.url())) || pages[pages.length - 1];
const cmd = process.argv[2] || 'dump';

if (cmd === 'shot') {
  const name = process.argv[3] || 'shot';
  await page.screenshot({ path: `./${name}.png`, fullPage: true });
  console.log('saved', name + '.png', '| url:', page.url());
} else if (cmd === 'evalfile') {
  const js = readFileSync(process.argv[3], 'utf8');
  const out = await page.evaluate(js);
  console.log(typeof out === 'string' ? out : JSON.stringify(out, null, 2));
} else {
  // dump
  writeFileSync('./current-page.html', await page.content());
  const info = await page.evaluate(() => {
    const txt = (e) => (e?.textContent || '').replace(/\s+/g, ' ').trim();
    const tables = Array.from(document.querySelectorAll('table')).map((t, i) => ({
      i, id: t.id, cls: t.className,
      rows: t.rows.length,
      headers: Array.from(t.querySelectorAll('th')).slice(0, 12).map((th) => txt(th)),
      firstDataRow: t.rows[1] ? Array.from(t.rows[1].cells).map((c) => txt(c)) : [],
    }));
    const links = Array.from(document.querySelectorAll('a')).map((a) => ({
      text: txt(a), href: a.getAttribute('href'), onclick: a.getAttribute('onclick'),
    })).filter((l) => l.text || l.onclick);
    const clickables = Array.from(document.querySelectorAll('[onclick]')).slice(0, 40).map((e) => ({
      tag: e.tagName, text: txt(e).slice(0, 40), onclick: (e.getAttribute('onclick') || '').slice(0, 120),
    }));
    const buttons = Array.from(document.querySelectorAll('button, input[type=button], input[type=submit]')).map((b) => ({
      id: b.id, name: b.name, text: txt(b) || b.value,
    }));
    return { url: location.href, title: document.title, bodyLen: document.body.innerText.length,
      tableCount: tables.length, tables, linkCount: links.length, links: links.slice(0, 60), clickables, buttons };
  });
  console.log(JSON.stringify(info, null, 2));
}
await browser.close(); // detaches CDP only; the user's Chrome stays open
