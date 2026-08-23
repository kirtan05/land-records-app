// Builds a clean PDF-only tree:  <Survey>/Case<NN>/{ case.pdf, order.pdf, case+order.pdf }
// (no JSON). Merged case+order made with pdfunite. Pending cases have only the case PDF.
import { readFileSync, existsSync, mkdirSync, copyFileSync, rmSync, readdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import { execFileSync } from 'node:child_process';
import { OUT, readState } from './src/store.mjs';
import { REPO } from '../../src/repo-root.mjs';

const DIST = REPO+'/dist';
const ROOT = join(DIST, 'Bharoda_iRCMS_Cases');
rmSync(DIST, { recursive: true, force: true });
mkdirSync(ROOT, { recursive: true });

const state = readState().surveys;
let cases = 0, orders = 0, merged = 0, pending = 0;
for (const [key, info] of Object.entries(state)) {
  if (info.status !== 'done') continue;
  const sum = JSON.parse(readFileSync(join(OUT, info.token, '_summary.json'), 'utf8'));
  for (const r of sum.records) {
    const caseDir = join(ROOT, info.token, `Case${String(r.case_index).padStart(2, '0')}`);
    mkdirSync(caseDir, { recursive: true });
    if (r.case_pdf && existsSync(r.case_pdf)) { copyFileSync(r.case_pdf, join(caseDir, basename(r.case_pdf))); cases++; }
    const ord = String(r.order_pdf || '').split(';').filter(Boolean).filter(existsSync);
    for (const op of ord) { copyFileSync(op, join(caseDir, basename(op))); orders++; }
    if (ord.length && r.case_pdf && existsSync(r.case_pdf)) {
      const out = join(caseDir, basename(r.case_pdf).replace(/\.pdf$/i, '_Case+Order.pdf'));
      execFileSync('pdfunite', [r.case_pdf, ...ord, out]);
      merged++;
    } else pending++;
  }
  // survey-level combined PDF (clickable index + bookmarks)
  const allPdf = join(OUT, info.token, `Bharoda_SurveyNo_${info.token}_ALL.pdf`);
  if (existsSync(allPdf)) copyFileSync(allPdf, join(ROOT, info.token, basename(allPdf)));
}
// AnyRoR land-record PDFs at survey-folder level (also creates folders for AnyRoR-only surveys)
let anyror = 0;
const anyrorState = existsSync(join(OUT, '_anyror_state.json')) ? JSON.parse(readFileSync(join(OUT, '_anyror_state.json'), 'utf8')) : {};
for (const info of Object.values(anyrorState)) {
  if (!info.done || !info.pdf || !existsSync(info.pdf)) continue;
  const dir = join(ROOT, info.token); mkdirSync(dir, { recursive: true });
  copyFileSync(info.pdf, join(dir, basename(info.pdf))); anyror++;
}
// old-scanned VF-7/12: copy each survey's oldvf712/ folder (period PDFs + combined) into the survey folder
let vf712 = 0;
const vfState = existsSync(join(OUT, '_vf712_state.json')) ? JSON.parse(readFileSync(join(OUT, '_vf712_state.json'), 'utf8')) : {};
for (const info of Object.values(vfState)) {
  if (!info.dir || !existsSync(info.dir)) continue;
  const dstDir = join(ROOT, info.token, 'oldvf712'); mkdirSync(dstDir, { recursive: true });
  for (const f of readdirSync(info.dir)) { if (f.toLowerCase().endsWith('.pdf')) { copyFileSync(join(info.dir, f), join(dstDir, f)); vf712++; } }
}
// master Excel at the zip root
const xlsx = join(OUT, 'iRCMS_Bharoda_Master.xlsx');
if (existsSync(xlsx)) copyFileSync(xlsx, join(ROOT, basename(xlsx)));
console.log(`staged ${cases} case PDFs, ${orders} order PDFs, ${merged} merged case+order PDFs, ${anyror} AnyRoR land records, ${vf712} VF-7/12 scans (${pending} pending cases = case only)`);
console.log('tree root:', ROOT);
