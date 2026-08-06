// Core scraping logic for the live (CDP-connected) iRCMS browser session.
import { join } from 'node:path';
import { writeFileSync } from 'node:fs';
import { surveyToken } from './normalize.mjs';
import { surveyDir, writeCaseJson, appendIndexRow, markSurvey } from './store.mjs';

// ---- case list (from a populated #surveylist_table) ----
export async function extractCaseList(page) {
  return await page.evaluate(() => {
    return Array.from(document.querySelectorAll('#surveylist_table tbody tr')).map((tr, row) => {
      const tds = Array.from(tr.cells).map((td) => td.innerText.replace(/\s+/g, ' ').trim());
      const btn = tr.querySelector('.view_status');
      const caseStr = tds[1] || '';
      return {
        row, sr_no: tds[0], case_str: caseStr, status: (caseStr.match(/\(([^)]+)\)/) || [, ''])[1],
        office: tds[2], dtv: tds[3], survey: tds[4], parties: tds[5],
        data_id: btn?.getAttribute('data-id')?.trim() || '', offcode: btn?.getAttribute('data-offcode')?.trim() || '',
        survno: btn?.getAttribute('data-survno') || '',
      };
    });
  });
}

// ---- case list (via direct AJAX, no table / no captcha consumption), with retries on 504 ----
export async function fetchCaseListAjax(listPage, surveyValue, log = console.log, retries = 4) {
  for (let attempt = 1; attempt <= retries; attempt++) {
    const r = await listPage.evaluate(async ({ surveyValue }) => {
      const token = document.querySelector('input[name=_token]').value;
      const captcha = document.querySelector('#txt_captcha').value.trim();
      const res = await new Promise((resolve) => {
        $.ajax({
          url: 'https://ircms.gujarat.gov.in/ViewSurveyListController', type: 'POST', method: 'POST', timeout: 150000,
          data: { dist: '15', taluka: '03', village: '029', surveyno: surveyValue, captcha_code: captcha, _token: token },
          success: (d) => resolve({ ok: true, d }), error: (xhr) => resolve({ ok: false, status: xhr.status }),
        });
      });
      if (!res.ok) return { ok: false, status: res.status };
      const d = res.d;
      if (d && d.success === false) return { ok: false, message: d.message };
      const div = document.createElement('div');
      const strip = (h) => { div.innerHTML = h || ''; return div.textContent.replace(/\s+/g, ' ').trim(); };
      const cases = [];
      for (const k in d) {
        const v = d[k];
        if (!v || !v.sr_no) continue;
        div.innerHTML = v.view || '';
        const btn = div.querySelector('.view_status');
        const caseStr = strip(v.case_str);
        cases.push({
          sr_no: v.sr_no, case_str: caseStr, status: (caseStr.match(/\(([^)]+)\)/) || [, ''])[1],
          office: strip(v.offname), dtv: strip(v.dtv), survey: strip(v.sno_str), parties: strip(v.pet_res),
          data_id: btn?.getAttribute('data-id')?.trim() || '', offcode: btn?.getAttribute('data-offcode')?.trim() || '',
          survno: btn?.getAttribute('data-survno') || '',
        });
      }
      return { ok: true, cases };
    }, { surveyValue });

    if (r.ok) return { cases: r.cases };
    if (r.message) return { error: 'message', message: r.message }; // invalid captcha OR no records
    log(`    search attempt ${attempt}/${retries} -> ${r.status} (retrying)`);
    await listPage.waitForTimeout(2500);
  }
  return { error: 'timeout' };
}

// ---- open a case detail tab by key (works with or without the results table) ----
export async function openDetailByKey(ctx, listPage, c) {
  const ck = Buffer.from(`${c.data_id}:${c.offcode}:${c.survno}`, 'utf8').toString('base64');
  const [detail] = await Promise.all([
    ctx.waitForEvent('page', { timeout: 45000 }),
    listPage.evaluate((ck) => { const el = document.querySelector('#casekey'); el.value = ck; el.form.submit(); }, ck),
  ]);
  return detail;
}
export function openDetailByRow(ctx, listPage, c) {
  return Promise.all([
    ctx.waitForEvent('page', { timeout: 45000 }),
    listPage.locator('#surveylist_table tbody tr').nth(c.row).locator('.view_status').click(),
  ]).then(([d]) => d);
}

// ---- detail metadata (lossless: header fields + every table + raw text) ----
export async function extractDetail(detail) {
  return await detail.evaluate(() => {
    const clean = (s) => (s || '').replace(/ /g, ' ').replace(/[ \t]+/g, ' ').replace(/\s*\n\s*/g, '\n').trim();
    const body = clean(document.body.innerText);
    const grab = (re) => (body.match(re) || [, ''])[1]?.trim() || '';
    const tables = Array.from(document.querySelectorAll('table')).map((t) => {
      let label = '', p = t.previousElementSibling;
      for (let hop = 0; p && hop < 4 && !label; hop++, p = p.previousElementSibling) {
        const txt = clean(p.textContent); if (txt && txt.length < 40) label = txt;
      }
      return { label, rows: Array.from(t.querySelectorAll('tr')).map((tr) => Array.from(tr.cells).map((c) => clean(c.innerText))) };
    });
    return {
      registration_no: grab(/Registration No\s*:\s*(.+?)\s*\|?\s*(?:Registration Date|\(\d|\n|$)/i),
      registration_date: grab(/Registration Date[^:]*:\s*([0-9/]+)/i),
      internal_id: grab(/\((\d{10,})\)/),
      case_status: grab(/Case Status\s*:\s*([^\n]+)/i),
      disposal_date: grab(/Case Disposal Date\s*:\s*([^\n]+)/i),
      disposal_type: grab(/Disposal Type\s*:\s*([^\n]+)/i),
      court_no: grab(/Court No\s*:\s*([^\n]+)/i),
      no_appellant: grab(/No Of Appellant\s*:\s*([^\n]+)/i),
      no_respondent: grab(/No Of Respondent\s*:\s*([^\n]+)/i),
      tables, raw_text: body,
    };
  });
}

export async function extractOrderForms(detail) {
  return await detail.evaluate(() => {
    return Array.from(document.querySelectorAll('form')).filter((f) => /download_order/i.test(f.getAttribute('action') || '')).map((f) => {
      const fields = {}; f.querySelectorAll('input').forEach((i) => { if (i.name) fields[i.name] = i.value; });
      return { action: new URL(f.getAttribute('action'), location.href).href, fields, hasFile: !!fields.f };
    }).filter((x) => x.hasFile);
  });
}

export async function downloadOrderBytes(ctx, form) {
  const resp = await ctx.request.post(form.action, { form: form.fields, timeout: 90000 });
  let buf = await resp.body();
  const i = buf.indexOf(Buffer.from('%PDF'));
  if (i > 0) buf = buf.subarray(i);
  return { buf, status: resp.status(), ctype: resp.headers()['content-type'] || '' };
}

// ---- shared per-case loop; openFn(c) returns the opened detail page ----
export async function processCases(ctx, listPage, surveyKey, allCases, openFn, log = console.log) {
  const token = surveyToken(surveyKey);
  const dir = surveyDir(token);
  const seen = new Set();
  const cases = allCases.filter((c) => (c.data_id && !seen.has(c.data_id) ? (seen.add(c.data_id), true) : (log(`  - skip duplicate id ${c.data_id} (sr ${c.sr_no})`), false)));

  let n = 0; const records = [];
  for (const c of cases) {
    n++;
    const casePdf = join(dir, `Bharoda_SurveyNo_${token}_Case${n}.pdf`);
    let rec;
    for (let attempt = 1; attempt <= 3; attempt++) {
      rec = { case_index: n, ...c, case_pdf: casePdf, order_pdf: '', order_downloaded: false, error: '' };
      let detail;
      try {
        detail = await openFn(c);
        await detail.waitForLoadState('domcontentloaded').catch(() => {});
        await detail.waitForTimeout(2500);
        const meta = await extractDetail(detail);
        rec.registration_no = meta.registration_no || rec.case_str;
        rec.disposal_date = meta.disposal_date || '';
        rec.detail = meta;
        await detail.pdf({ path: casePdf, format: 'A4', printBackground: true, margin: { top: '8mm', bottom: '8mm', left: '6mm', right: '6mm' } });

        const orderForms = await extractOrderForms(detail);
        let k = 0;
        for (const f of orderForms) {
          k++;
          const suffix = orderForms.length > 1 ? `_Order${k}` : '_Order';
          const orderPdf = join(dir, `Bharoda_SurveyNo_${token}_Case${n}${suffix}.pdf`);
          const { buf, status, ctype } = await downloadOrderBytes(ctx, f);
          if (status === 200 && /pdf/i.test(ctype) && buf.slice(0, 4).toString() === '%PDF') {
            writeFileSync(orderPdf, buf);
            rec.order_pdf = rec.order_pdf ? rec.order_pdf + ';' + orderPdf : orderPdf;
            rec.order_downloaded = true;
          } else throw new Error(`order ${k}: ${status} ${ctype} ${buf.length}b`);
        }
        log(`  ✓ case ${n}/${cases.length} [${rec.status}] ${rec.registration_no}${rec.order_downloaded ? ' +order' : ''}${attempt > 1 ? ` (retry ${attempt})` : ''}`);
        if (detail) await detail.close().catch(() => {});
        break;
      } catch (e) {
        rec.error = e.message;
        if (detail) await detail.close().catch(() => {});
        if (attempt < 3) { log(`  … case ${n} attempt ${attempt} failed (${e.message}); retrying`); await listPage.waitForTimeout(2500); }
        else log(`  ✗ case ${n} (gave up after ${attempt}): ${e.message}`);
      }
    }
    rec.scraped_at = new Date().toISOString();
    writeCaseJson(token, n, rec);
    appendIndexRow({ survey_key: surveyKey, survey_token: token, ...rec });
    records.push(rec);
  }

  const orders = records.filter((r) => r.order_downloaded).length;
  markSurvey(surveyKey, { status: 'done', token, case_count: n, orders, finished_at: new Date().toISOString() });
  writeFileSync(join(dir, '_summary.json'), JSON.stringify({ surveyKey, token, case_count: n, orders, records }, null, 2));
  log(`  DONE ${surveyKey}: ${n} cases, ${orders} orders -> output/${token}/`);
  return { token, count: n, orders };
}

// table path (results already populated by a View click)
export async function processSurvey(ctx, listPage, surveyKey, log = console.log) {
  const all = await extractCaseList(listPage);
  log(`  ${all.length} rows in results table`);
  return processCases(ctx, listPage, surveyKey, all, (c) => openDetailByRow(ctx, listPage, c), log);
}
