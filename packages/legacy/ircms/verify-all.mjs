// Final verification: walks output/, validates every PDF, cross-checks records.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { OUT, readState } from '../../core/store.mjs';

const isPdf = (p) => { try { const fd = readFileSync(p); return fd.slice(0, 4).toString() === '%PDF' && fd.length > 800; } catch { return false; } };

const state = readState().surveys;
let totCases = 0, totCasePdf = 0, totOrderPdf = 0, badPdf = 0, errs = [];
const rows = [];

for (const [key, info] of Object.entries(state)) {
  if (info.status === 'no_cases') { rows.push([key, info.status, '-', '-', '-']); continue; }
  if (info.status !== 'done') { rows.push([key, info.status || '?', '-', '-', '-']); continue; }
  const dir = join(OUT, info.token);
  const sum = JSON.parse(readFileSync(join(dir, '_summary.json'), 'utf8'));
  let casePdf = 0, orderPdf = 0;
  for (const r of sum.records) {
    totCases++;
    if (r.case_pdf && existsSync(r.case_pdf) && isPdf(r.case_pdf)) casePdf++; else { badPdf++; errs.push(`${key} case${r.case_index}: case PDF missing/invalid`); }
    if (r.order_downloaded) for (const op of String(r.order_pdf).split(';').filter(Boolean)) { if (existsSync(op) && isPdf(op)) orderPdf++; else { badPdf++; errs.push(`${key} case${r.case_index}: order PDF missing/invalid`); } }
    if (r.error) errs.push(`${key} case${r.case_index}: recorded error -> ${r.error}`);
  }
  totCasePdf += casePdf; totOrderPdf += orderPdf;
  rows.push([key, 'done', sum.records.length, casePdf, orderPdf]);
}

// also scan for any stray non-%PDF files
let scanned = 0;
for (const d of readdirSync(OUT)) {
  const dd = join(OUT, d); if (!statSync(dd).isDirectory()) continue;
  for (const f of readdirSync(dd)) if (f.endsWith('.pdf')) { scanned++; if (!isPdf(join(dd, f))) { badPdf++; errs.push(`stray invalid PDF: ${d}/${f}`); } }
}

console.log('Survey          Status     Cases  CasePDF  OrderPDF');
console.log('--------------  ---------  -----  -------  --------');
for (const r of rows) console.log(`${String(r[0]).padEnd(14)}  ${String(r[1]).padEnd(9)}  ${String(r[2]).padStart(5)}  ${String(r[3]).padStart(7)}  ${String(r[4]).padStart(8)}`);
console.log('\nTOTALS:');
console.log(`  surveys with cases: ${rows.filter((r) => r[1] === 'done').length} | empty (no cases): ${rows.filter((r) => r[1] === 'no_cases').length}`);
console.log(`  cases: ${totCases} | case PDFs valid: ${totCasePdf} | order PDFs valid: ${totOrderPdf} | total PDFs scanned: ${scanned}`);
console.log(`  invalid/missing PDFs: ${badPdf}`);
console.log(errs.length ? '\nISSUES:\n - ' + errs.join('\n - ') : '\n✅ All PDFs valid, no missing files, no recorded case errors.');
