// READ-ONLY recon of https://ircms.gujarat.gov.in/ViewSurveyList
// Walks District->Taluka->Village->SurveyNo dropdowns, dumps form + AJAX endpoints.
// Does NOT submit the form, solve a CAPTCHA, or save any case data.
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';
import { REPO } from '../../core/repo-root.mjs';

const URL = 'https://ircms.gujarat.gov.in/ViewSurveyList';
const OUT = REPO;
const HEADLESS = process.env.HEADED ? false : true;

const log = (...a) => console.log(...a);
const sep = (t) => log('\n===== ' + t + ' =====');

async function dumpSelects(page) {
  return await page.$$eval('select', (sels) =>
    sels.map((s) => ({
      id: s.id,
      name: s.name,
      optionCount: s.options.length,
      options: Array.from(s.options).slice(0, 8).map((o) => ({ value: o.value, text: o.textContent.trim() })),
    }))
  );
}

// find a <select> whose options contain text matching `re`; return {id,name,matchValue}
async function findSelectByOption(page, re) {
  const sels = await page.$$eval('select', (nodes, reSrc) => {
    const rx = new RegExp(reSrc, 'i');
    return nodes.map((s) => {
      const hit = Array.from(s.options).find((o) => rx.test(o.textContent || ''));
      return hit ? { id: s.id, name: s.name, value: hit.value, text: hit.textContent.trim() } : null;
    });
  }, re.source);
  return sels.find(Boolean) || null;
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: HEADLESS });
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  const page = await ctx.newPage();

  const net = [];
  page.on('request', (r) => {
    const t = r.resourceType();
    if (t === 'xhr' || t === 'fetch' || r.method() === 'POST') net.push(`${r.method()} ${r.url()}`);
  });

  try {
    sep('NAVIGATE');
    const resp = await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    log('status', resp && resp.status());
    log('title', await page.title());
    log('final url', page.url());
    await page.waitForTimeout(2500);

    sep('INITIAL SELECTS');
    log(JSON.stringify(await dumpSelects(page), null, 2));

    sep('CAPTCHA + BUTTONS');
    const imgs = await page.$$eval('img', (ns) =>
      ns.map((i) => ({ id: i.id, src: (i.getAttribute('src') || '').slice(0, 80), alt: i.alt })).filter((i) => /captcha|verif/i.test(i.id + i.src + i.alt))
    );
    log('captcha imgs:', JSON.stringify(imgs, null, 2));
    const inputs = await page.$$eval('input', (ns) =>
      ns.map((i) => ({ id: i.id, name: i.name, type: i.type, placeholder: i.placeholder }))
    );
    log('inputs:', JSON.stringify(inputs, null, 2));
    const btns = await page.$$eval('button, input[type=submit], input[type=button], a.btn', (ns) =>
      ns.map((b) => ({ id: b.id, text: (b.textContent || b.value || '').trim(), tag: b.tagName }))
    );
    log('buttons:', JSON.stringify(btns, null, 2));

    // ---- cascade ----
    sep('CASCADE: District=ANAND');
    const district = await findSelectByOption(page, /anand/i);
    log('district select:', JSON.stringify(district));
    if (district && district.id) {
      await page.selectOption('#' + district.id, district.value);
      await page.waitForTimeout(2500);
    }

    sep('CASCADE: Taluka=UMRETH');
    const taluka = await findSelectByOption(page, /umreth/i);
    log('taluka select:', JSON.stringify(taluka));
    if (taluka && taluka.id) {
      await page.selectOption('#' + taluka.id, taluka.value);
      await page.waitForTimeout(2500);
    }

    sep('CASCADE: Village=BHARODA');
    const village = await findSelectByOption(page, /bharoda|bhroda|ભરોડા/i);
    log('village select:', JSON.stringify(village));
    if (village && village.id) {
      await page.selectOption('#' + village.id, village.value);
      await page.waitForTimeout(2500);
    }

    sep('SURVEY NO OPTIONS (full)');
    const allSelects = await dumpSelects(page);
    log('selects after cascade:', JSON.stringify(allSelects, null, 2));
    // the survey select = the one with the most options now (excluding the geo ones)
    const surveyFull = await page.$$eval('select', (sels) =>
      sels.map((s) => ({ id: s.id, name: s.name, count: s.options.length, options: Array.from(s.options).map((o) => ({ value: o.value, text: o.textContent.trim() })) }))
    );
    surveyFull.sort((a, b) => b.count - a.count);
    const survey = surveyFull[0];
    log('LIKELY survey select id:', survey.id, 'name:', survey.name, 'count:', survey.count);
    writeFileSync(OUT + '/recon-survey-options.json', JSON.stringify(surveyFull, null, 2));
    log('sample survey options:', JSON.stringify(survey.options.slice(0, 30), null, 2));

    sep('NETWORK (xhr/post)');
    log([...new Set(net)].join('\n'));

    // full HTML for offline inspection
    writeFileSync(OUT + '/recon-page.html', await page.content());
    log('\nSaved recon-page.html and recon-survey-options.json');
  } catch (e) {
    log('RECON ERROR:', e.message);
  } finally {
    await browser.close();
  }
})();
