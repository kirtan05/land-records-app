// Re-weed already-downloaded VF-7/12 folders offline (no re-download): drop "not scanned"
// placeholders, rebuild the chronological _ALL.pdf, and update manifest + state.
//   node anyror/reweed-vf712.mjs            # all downloaded surveys
//   node anyror/reweed-vf712.mjs 221_P      # one token
import { readFileSync, writeFileSync, existsSync, readdirSync, rmSync } from 'node:fs';
import { join, basename } from 'node:path';
import { execFileSync } from 'node:child_process';

const OUT = '/home/kirtan/Desktop/projects/irmsc/output';
const STATE = join(OUT, '_vf712_state.json');
const PLACEHOLDER = /શકેલ નથી|શકલ નથી|જર્જરીત|ઉપલબ્ધ નથી|does not exist|not available|record not found/i;
const isPlaceholder = (file) => { try { const raw = execFileSync('pdftotext', ['-l', '3', file, '-'], { encoding: 'utf8' }); const compact = raw.replace(/\s+/g, ''); const plain = raw.replace(/\s+/g, ' ').trim(); return PLACEHOLDER.test(raw) || (compact.length >= 10 && plain.length < 300); } catch { return false; } };
const periodOf = (f) => (basename(f).match(/_(\d{4}-\d{4})_/) || [, ''])[1];
const startYear = (f) => { const m = periodOf(f).match(/(\d{4})/); return m ? +m[1] : 9999; };

const onlyTok = process.argv[2];
const state = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {};
let changed = false;

for (const [key, info] of Object.entries(state)) {
  if (onlyTok && info.token !== onlyTok && key !== onlyTok) continue;
  const dir = join(OUT, info.token, 'oldvf712');
  if (!existsSync(dir)) { console.log(`${key}: no dir, skip`); continue; }
  const files = readdirSync(dir).filter((f) => f.endsWith('.pdf') && !/_ALL\.pdf$/i.test(f)).map((f) => join(dir, f));
  const keep = [], drop = [];
  for (const f of files) (isPlaceholder(f) ? drop : keep).push(f);
  for (const f of drop) rmSync(f, { force: true });
  keep.sort((a, b) => startYear(a) - startYear(b));

  const combined = join(dir, `VF712_${info.token}_ALL.pdf`);
  rmSync(combined, { force: true });
  if (keep.length === 1) writeFileSync(combined, readFileSync(keep[0]));
  else if (keep.length > 1) execFileSync('pdfunite', [...keep, combined]);

  // update manifest rows
  const manPath = join(OUT, info.token, `vf712_${info.token}.json`);
  if (existsSync(manPath)) {
    const man = JSON.parse(readFileSync(manPath, 'utf8'));
    const keepSet = new Set(keep.map((f) => basename(f)));
    for (const r of man.rows || []) { if (r.kept && r.file && !keepSet.has(basename(r.file))) { r.kept = false; r.reason = 'not-scanned/placeholder'; delete r.file; } }
    man.kept = keep.length; man.combined = keep.length ? combined : null;
    man.periods = keep.map(periodOf);
    writeFileSync(manPath, JSON.stringify(man, null, 2));
  }
  info.kept = keep.length; info.combined = keep.length ? combined : null; info.periods = keep.map(periodOf);
  changed = true;
  console.log(`${key}: kept ${keep.length}, weeded ${drop.length} placeholder(s) -> ${keep.length ? 'VF712_' + info.token + '_ALL.pdf' : '(none)'}`);
  if (drop.length) console.log('   weeded:', drop.map((f) => basename(f)).join(', '));
}
if (changed) writeFileSync(STATE, JSON.stringify(state, null, 2));
console.log('REWEED DONE');
