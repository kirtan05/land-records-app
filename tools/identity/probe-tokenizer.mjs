// Throwaway measurement harness for tokenizer rule changes.
// Reports, per village, how many DISTINCT real surveys would collapse onto one token —
// the only failure mode that actually corrupts land records.
//   node tools/identity/probe-tokenizer.mjs
import { readdirSync, readFileSync } from 'node:fs';
import { surveyToken } from '../../src/identity.mjs';

const DIR = 'android/app/src/main/assets/surveys';

// Two raw strings are the SAME survey if they differ only in presentation: the ~~ marker,
// Gujarati vs ASCII digits, whitespace, and the paiki spelling. Anything else is a real
// difference, and a token that fuses them is a bug.
const GU = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };
const presentation = (s) =>
  String(s)
    .replace(/~~/g, ' ')
    .replace(/[૦-૯]/g, (c) => GU[c])
    .replace(/પૈકી/g, 'p')
    .replace(/પ/g, 'p')
    .replace(/\s+/g, '')
    .toUpperCase();

let totalReal = 0;
const villages = [];
for (const f of readdirSync(DIR)) {
  const arr = JSON.parse(readFileSync(`${DIR}/${f}`, 'utf8'));
  const byToken = new Map();
  for (const raw of arr) {
    const t = surveyToken(raw);
    if (!t) continue;
    if (!byToken.has(t)) byToken.set(t, new Set());
    byToken.get(t).add(presentation(raw));
  }
  const bad = [...byToken].filter(([, v]) => v.size > 1);
  totalReal += bad.length;
  villages.push({ f: f.replace('.json', ''), n: arr.length, tokens: byToken.size, bad });
}

for (const v of villages) {
  console.log(`${v.f.padEnd(12)} ${String(v.n).padStart(6)} raw  ${String(v.tokens).padStart(6)} tokens  ${String(v.bad.length).padStart(4)} fused`);
  for (const [t, set] of v.bad.slice(0, 6)) console.log(`      ${t} <- ${[...set].join(' | ')}`);
}
console.log(`\nTOTAL fused (real survey collisions): ${totalReal}`);
