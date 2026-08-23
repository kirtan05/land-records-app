// Fill any entry scan a run failed to download from another survey in the SAME village.
//
// Safe because the scan handler is keyed by village + entry number, not by survey:
// Info6oldImage.ashx?dtv=<geo>&eno=<entry>&pagecnt=<n>. Proven byte-identical — the files pulled
// for Bhalej 174/p1 and 174/p2 have the same md5 for every shared entry. So a missing scan is
// filled from the copy we already hold, and the row is marked `via:"village-copy"` so the
// provenance is never lost.
//   node packages/captcha/heal-entries.mjs
import { readdirSync, readFileSync, writeFileSync, existsSync, copyFileSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from '../core/repo-root.mjs';

const OUT = REPO+'/output';
const dirs = readdirSync(OUT).filter((d) => d.startsWith('Bhalej_') && existsSync(join(OUT, d, 'entries/entries.json')));

// number → {dir, files[]} from every survey that actually has the scan
const have = new Map();
for (const d of dirs) {
  const j = JSON.parse(readFileSync(join(OUT, d, 'entries/entries.json'), 'utf8'));
  for (const c of j.captured || []) {
    if (c.files?.length && !have.has(c.number)) have.set(c.number, { dir: d, files: c.files });
  }
}

let healed = 0;
for (const d of dirs) {
  const p = join(OUT, d, 'entries/entries.json');
  const j = JSON.parse(readFileSync(p, 'utf8'));
  let touched = false;
  for (const c of j.captured || []) {
    if (c.files?.length || c.unavailable) continue;      // fine, or the site itself said no
    const src = have.get(c.number);
    if (!src || src.dir === d) continue;
    for (const f of src.files) {
      const from = join(OUT, src.dir, 'entries', f);
      if (!existsSync(from)) continue;
      copyFileSync(from, join(OUT, d, 'entries', f));
    }
    c.files = src.files.slice();
    c.via = 'village-copy';
    c.copiedFrom = src.dir;
    delete c.downloadFailed;
    touched = true; healed++;
    console.log(`  ${d}: entry ${c.number} ← ${src.dir} (same village, same entry, identical file)`);
  }
  if (touched) writeFileSync(p, JSON.stringify(j, null, 1));
}
console.log(healed ? `healed ${healed} entr${healed > 1 ? 'ies' : 'y'}` : 'nothing to heal');
