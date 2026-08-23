// Staple one survey into ONE printable file:
//     cover  →  integrated record  →  every old (red) નોંધ scan, one per page, labelled.
// This is the thing dad asked for: "is it possible in old vf6 to enter all entry numbers together
// and get one pdf". The site only answers one entry number at a time; this does the walking.
//   node packages/captcha/pdf/combine-survey-pdf.mjs               # every output/Bhalej_*/
//   node packages/captcha/pdf/combine-survey-pdf.mjs Bhalej_174_P1
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, readdirSync, rmSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { REPO } from '../../core/repo-root.mjs';

const ROOT = REPO;
const OUT = join(ROOT, 'output');
const only = process.argv[2];
const PDF = { format: 'A4', landscape: true, printBackground: true, margin: { top: '8mm', bottom: '8mm', left: '8mm', right: '8mm' } };
const esc = (s) => String(s ?? '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const numOf = (n) => parseInt(String(n).replace(/\D/g, ''), 10) || 0;

const CSS = `
  @page { size: A4 landscape; margin: 8mm; }
  * { box-sizing: border-box; }
  body { margin:0; font-family:'Noto Sans Gujarati','Noto Sans',Arial,sans-serif; color:#111; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  .page { page-break-after: always; }
  .page:last-child { page-break-after: auto; }
  h1 { font-size:19pt; margin:0 0 2px; color:#1f4e78; }
  .sub { font-size:11pt; color:#222; margin-bottom:2px; }
  .rule { border-bottom:2.5px solid #1f4e78; margin:6px 0 10px; }
  .chips span { margin-right:18px; font-size:10pt; white-space:nowrap; }
  h2 { font-size:12.5pt; margin:14px 0 5px; color:#1f4e78; }
  table { width:100%; border-collapse:collapse; font-size:9.6pt; }
  td, th { border:.5pt solid #445; padding:3px 6px; vertical-align:top; }
  th { background:#e8eef6; font-weight:700; text-align:left; }
  .red { color:#C41E1E !important; font-weight:900; }
  .muted { color:#555; }
  .note { font-size:9pt; color:#333; margin-top:8px; line-height:1.45; }
  .scanhead { display:flex; align-items:baseline; gap:14px; border-bottom:1.5px solid #C41E1E; padding-bottom:3px; margin-bottom:6px; }
  .scanhead .n { font-size:16pt; font-weight:900; color:#C41E1E !important; }
  .scanhead .t { font-size:10.5pt; color:#222; }
  .scanhead .p { margin-left:auto; font-size:9.5pt; color:#555; }
  .scanwrap { text-align:center; }
  .scanwrap img { max-width:100%; max-height:172mm; border:.5pt solid #778; }
`;

const tokens = readdirSync(OUT).filter((d) => d.startsWith('Bhalej_') && (!only || d === only));
const browser = await chromium.launch({ channel: 'chrome', headless: true });

for (const tok of tokens) {
  const dir = join(OUT, tok), eDir = join(dir, 'entries');
  const metaPath = join(dir, `anyror_${tok}.json`);
  if (!existsSync(metaPath)) { console.log(`  ${tok}: no metadata — skip`); continue; }
  const meta = JSON.parse(readFileSync(metaPath, 'utf8'));
  const survey = meta.survey || tok;
  const integrated = join(dir, `AnyRoR_Bhalej_${survey.replace(/\//g, '_')}_Integrated.pdf`);
  if (!existsSync(integrated)) { console.log(`  ${tok}: no integrated PDF — run render-bhalej-pdf.mjs first`); continue; }
  const ent = existsSync(join(eDir, 'entries.json')) ? JSON.parse(readFileSync(join(eDir, 'entries.json'), 'utf8')) : { rows: [], captured: [] };
  const rows = ent.rows || meta.entry_index || [];
  const captured = (ent.captured || []).slice().sort((a, b) => numOf(a.number) - numOf(b.number));
  const withScan = captured.filter((c) => c.files?.length);
  // "the site says it has nothing" and "we could not download it" are different facts about a land
  // record; never merge them into one 'missing' bucket.
  const without = captured.filter((c) => !c.files?.length && c.unavailable);
  const failed = captured.filter((c) => !c.files?.length && !c.unavailable);
  const redAll = rows.filter((r) => r.red);

  // Sub-registrar deeds ride the same integrated page (gvgarviProDet) — they are already inside
  // section 1, but one row per PARTY makes them easy to miss, so fold them by document and list
  // them on the cover. Columns: office, survey, year, doc no, date, role, party name, value.
  const deedRows = meta.deeds || [];
  const deedMap = new Map();
  for (const d of deedRows) {
    const key = `${d[2] || ''}/${d[3] || ''}`;
    if (!deedMap.has(key)) deedMap.set(key, { office: d[0] || '', survey: d[1] || '', year: d[2] || '', no: d[3] || '', date: d[4] || '', value: d[7] || '', parties: [] });
    if (d[6]) deedMap.get(key).parties.push(`${d[6]}${d[5] ? ` (${d[5]})` : ''}`);
  }
  const deeds = [...deedMap.values()];
  const dDir = join(dir, 'deeds');
  const deedPdfs = existsSync(dDir) ? readdirSync(dDir).filter((f) => f.endsWith('.pdf')).sort() : [];
  const deedFailNotes = existsSync(dDir) ? readdirSync(dDir).filter((f) => f.endsWith('.response.txt')) : [];

  // ── cover ───────────────────────────────────────────────────────────────────
  const cover = `<!doctype html><meta charset="utf-8"><style>${CSS}</style><div class="page">
    <h1>Bhalej ${esc(survey)} — સંપૂર્ણ રેકોર્ડ / Complete record</h1>
    <div class="sub">Village <b>Bhalej</b> · Taluka <b>Umreth</b> · District <b>Anand</b>
      &nbsp;|&nbsp; Survey / Block No <b>${esc(meta.survey_label || survey)}</b>
      ${meta.as_of ? `&nbsp;|&nbsp; AnyRoR as of ${esc(meta.as_of)}` : ''}</div>
    <div class="rule"></div>
    <div class="chips">
      ${meta.total_area ? `<span>કુલ ક્ષેત્રફળ / Area: <b>${esc(meta.total_area)}</b></span>` : ''}
      ${meta.total_assessment ? `<span>આકાર / Assessment: <b>${esc(meta.total_assessment)}</b></span>` : ''}
      ${meta.tenure ? `<span>સત્તાપ્રકાર / Tenure: <b>${esc(meta.tenure)}</b></span>` : ''}
      ${meta.land_use ? `<span>ઉપયોગ / Land Use: <b>${esc(meta.land_use)}</b></span>` : ''}
    </div>
    <h2>આ ફાઇલમાં શું છે / What is inside</h2>
    <table>
      <tr><th style="width:38%">વિભાગ / Section</th><th>વિગત / Detail</th></tr>
      <tr><td>1. સંકલિત સર્વે રેકોર્ડ<br><span class="muted">Integrated survey record (AnyRoR)</span></td>
          <td>ખાતેદાર, બોજા, ગણોત, પાક, નોંધ નંબરોની યાદી, અને <b>${(meta.computerised_entries || []).length || (meta.computerised_entries ?? 0)}</b> કમ્પ્યુટરાઇઝ્ડ નોંધોની પૂરી લખાણ-વિગત.</td></tr>
      <tr><td>2. સબ-રજીસ્ટ્રાર દસ્તાવેજ<br><span class="muted">Sub-registrar registered deeds</span></td>
          <td>${deeds.length ? `<b>${deeds.length}</b> દસ્તાવેજ — વિગત વિભાગ 1 ના “Sub registrar Deed Details” માં અને નીચે.${deedPdfs.length ? ` <b>${deedPdfs.length}</b> દસ્તાવેજની સ્કૅન નકલ આ ફાઇલના છેડે જોડેલ છે.` : ''}` : 'આ સરવે નંબર પર કોઈ નોંધાયેલ દસ્તાવેજ મળ્યો નથી. <span class="muted">no registered deed listed</span>'}</td></tr>
      <tr><td>3. જૂની હાથે લખેલી નોંધોની સ્કૅન નકલ<br><span class="muted">Old hand-written entries (VF-6 scans)</span></td>
          <td><b>${withScan.length}</b> નોંધની સ્કૅન નકલ જોડેલ છે — એક નોંધ, એક પાનું.
          ${without.length ? `<br><b>${without.length}</b> નોંધ સરકારી સાઇટ પર ઉપલબ્ધ નથી (નીચે યાદી).` : ''}
          ${failed.length ? `<br><b>${failed.length}</b> નોંધ આ વખતે ઉતરી નથી — ફરી પ્રયત્ન કરવાનો છે.` : ''}</td></tr>
    </table>
    ${deeds.length ? `<h2>સબ-રજીસ્ટ્રાર દસ્તાવેજ / Registered deeds &nbsp;<span class="muted" style="font-size:9.5pt;">(${deeds.length})</span></h2>
    <table>
      <tr><th style="width:11%">દસ્તાવેજ નં.<br><span class="muted">Doc no</span></th><th style="width:11%">તારીખ<br><span class="muted">Date</span></th>
          <th style="width:11%">કિંમત<br><span class="muted">Value ₹</span></th><th style="width:13%">સરવે નં.</th><th>પક્ષકારો / Parties</th><th style="width:15%">કચેરી / Office</th></tr>
      ${deeds.map((d) => `<tr><td><b>${esc(d.no)}</b>${d.year ? ` / ${esc(d.year)}` : ''}</td><td>${esc(d.date)}</td>
        <td>${esc(d.value)}</td><td>${esc(d.survey)}</td><td>${d.parties.map(esc).join('<br>')}</td><td>${esc(d.office)}</td></tr>`).join('')}
    </table>
    ${deedPdfs.length
      ? `<div class="note muted">દરેક દસ્તાવેજની સ્કૅન નકલ આ ફાઇલના છેડે જોડેલ છે.<br>The SRO scan of each deed is attached at the end of this file.</div>`
      : deedFailNotes.length
      ? `<div class="note muted">દસ્તાવેજની સ્કૅન નકલ (“View Deed”) AnyRoR પરથી ઉતરતી નથી — સાઇટે ના પાડી; નકલ માટે સબ-રજીસ્ટ્રાર કચેરી.<br>The deed images behind “View Deed” could not be downloaded; the details above are what AnyRoR holds.</div>`
      : ''}` : ''}
    <h2>જૂની નોંધો / Old hand-written entries &nbsp;<span class="muted" style="font-size:9.5pt;">(કુલ ${redAll.length})</span></h2>
    <table>
      <tr><th style="width:22%">સ્થિતિ / Status</th><th>નોંધ નંબર / Entry numbers</th></tr>
      <tr><td><b>સ્કૅન જોડેલ છે</b><br><span class="muted">scan attached below</span></td>
          <td class="red">${withScan.map((c) => esc(c.number)).join(' &nbsp;·&nbsp; ') || '—'}</td></tr>
      ${without.length ? `<tr><td><b>ઉપલબ્ધ નથી</b><br><span class="muted">not available on AnyRoR</span></td>
          <td>${without.map((c) => esc(c.number)).join(' &nbsp;·&nbsp; ')}</td></tr>` : ''}
      ${failed.length ? `<tr><td><b>ફરી ઉતારવાનું બાકી</b><br><span class="muted">download failed — retry</span></td>
          <td>${failed.map((c) => esc(c.number)).join(' &nbsp;·&nbsp; ')}</td></tr>` : ''}
    </table>
    ${without.length ? `<div class="note"><b>ઉપલબ્ધ ન હોય તે નોંધો વિશે:</b> AnyRoR પોતે જવાબ આપે છે —
      “${esc(without[0].unavailable || '')}”<br>
      <span class="muted">i.e. these entries are simply not scanned into AnyRoR; the ones above are every entry the site does hold. For those, the Mamlatdar (તાલુકા) office record is the only source.</span></div>` : ''}
    <div class="note muted">નોંધ: કમ્પ્યુટરાઇઝ્ડ (વાદળી) નોંધોની વિગત વિભાગ 1 માં લખાણ સ્વરૂપે જ છે — તેની અલગ સ્કૅન નકલ હોતી નથી.</div>
  </div>`;

  // ── one page per scan ───────────────────────────────────────────────────────
  const pages = [];
  for (const c of withScan) {
    c.files.forEach((f, i) => {
      const p = join(eDir, f);
      if (!existsSync(p)) return;
      const b64 = readFileSync(p).toString('base64');
      const mime = f.endsWith('.png') ? 'image/png' : 'image/jpeg';
      pages.push(`<div class="page">
        <div class="scanhead"><span class="n">નોંધ નંબર ${esc(c.number)}</span>
          <span class="t">જૂની હાથે લખેલી નોંધ — સ્કૅન નકલ &nbsp;·&nbsp; Old hand-written entry (VF-6 scan) &nbsp;·&nbsp; Bhalej ${esc(survey)}</span>
          <span class="p">પાનું ${i + 1} / ${c.files.length}</span></div>
        <div class="scanwrap"><img src="data:${mime};base64,${b64}"></div>
      </div>`);
    });
  }

  // ── scanned deed files, rasterised so they sit in the same labelled layout ──────────
  //    (deeds/*.pdf comes from packages/captcha/runners/fetch-deeds.mjs; TIFFs are already converted there)
  if (deedPdfs.length) {
    const scratch = mkdtempSync(join(tmpdir(), 'deedpg-'));
    for (const f of deedPdfs) {
      const m = f.match(/deed_([^_]+)_(.+)\.pdf$/);
      const label = m ? `${m[2]} / ${m[1]}` : f.replace(/\.pdf$/, '');
      const stem = join(scratch, f.replace(/\.pdf$/, ''));
      try { execFileSync('pdftoppm', ['-r', '150', '-png', join(dDir, f), stem]); } catch { continue; }
      const imgs = readdirSync(scratch).filter((x) => x.startsWith(f.replace(/\.pdf$/, '')) && x.endsWith('.png')).sort();
      imgs.forEach((img, i) => {
        const b64 = readFileSync(join(scratch, img)).toString('base64');
        pages.push(`<div class="page">
          <div class="scanhead" style="border-bottom-color:#1f4e78;"><span class="n" style="color:#1f4e78 !important;">દસ્તાવેજ નં. ${esc(label)}</span>
            <span class="t">સબ-રજીસ્ટ્રાર નોંધાયેલ દસ્તાવેજ — સ્કૅન નકલ &nbsp;·&nbsp; Registered deed (SRO scan) &nbsp;·&nbsp; Bhalej ${esc(survey)}</span>
            <span class="p">પાનું ${i + 1} / ${imgs.length}</span></div>
          <div class="scanwrap"><img src="data:image/png;base64,${b64}" style="max-height:174mm;"></div>
        </div>`);
      });
    }
    try { rmSync(scratch, { recursive: true, force: true }); } catch {}
  }

  const tmpCover = join(dir, '.cover.html'), tmpScans = join(dir, '.scans.html');
  writeFileSync(tmpCover, cover);
  const page = await browser.newPage({ viewport: { width: 1123, height: 794 } });
  await page.goto('file://' + tmpCover, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts?.ready).catch(() => {});
  const coverPdf = join(dir, '.cover.pdf');
  await page.pdf({ path: coverPdf, ...PDF });

  const parts = [coverPdf, integrated];
  if (pages.length) {
    writeFileSync(tmpScans, `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${pages.join('\n')}`);
    await page.goto('file://' + tmpScans, { waitUntil: 'load' });
    await page.evaluate(() => document.fonts?.ready).catch(() => {});
    const scansPdf = join(dir, '.scans.pdf');
    await page.pdf({ path: scansPdf, ...PDF });
    parts.push(scansPdf);
  }
  await page.close();

  const finalPdf = join(dir, `Bhalej_${survey.replace(/\//g, '-')}_FULL.pdf`);
  execFileSync('pdfunite', [...parts, finalPdf]);
  [tmpCover, tmpScans, join(dir, '.cover.pdf'), join(dir, '.scans.pdf')].forEach((f) => { try { rmSync(f); } catch {} });
  const n = execFileSync('pdfinfo', [finalPdf]).toString().match(/Pages:\s+(\d+)/)?.[1];
  console.log(`  ${tok} → ${finalPdf.split('/').pop()}  (${n} pages · ${withScan.length} old-entry scans · ${without.length} unavailable · ${failed.length} to retry)`);
}
await browser.close();
console.log('COMBINE DONE');
