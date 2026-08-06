// Matches the transcribed handwritten survey numbers against the Bharoda catalog.
import { readFileSync, writeFileSync } from 'node:fs';
import { normalizeSurvey } from './src/normalize.mjs';

// --- Transcription of the handwritten JPG (verify against image) ---
const HANDWRITTEN = [
  '221/P',
  '222/1',
  '226/P/1',
  '230/P1/P3',
  '222/2/P',
  '228/P1/P',
  '222/3/P1',
  '222/3/P2',
  '229/P',
];

const cat = JSON.parse(readFileSync('./survey-catalog.json', 'utf8'));
const byKey = new Map(cat.rows.map((r) => [r.normalized, r]));

const results = HANDWRITTEN.map((raw) => {
  const key = normalizeSurvey(raw);
  const exact = byKey.get(key);
  let near = [];
  if (!exact) {
    near = cat.rows
      .filter((r) => r.normalized.startsWith(key.split('/')[0] + '/') || r.normalized === key.split('/')[0])
      .slice(0, 12)
      .map((r) => r.normalized);
  }
  return { handwritten: raw, normalized: key, matched: !!exact, value: exact?.value || null, display: exact?.display || null, near };
});

writeFileSync('./survey-input.json', JSON.stringify(results, null, 2));
const csv = 'handwritten,normalized,matched,display,value\n' +
  results.map((r) => `"${r.handwritten}","${r.normalized}",${r.matched},"${r.display || ''}","${(r.value || '').replace(/"/g, '""')}"`).join('\n');
writeFileSync('./survey-input.csv', csv);

console.log('# | handwritten   | normalized   | matched | dropdown value');
console.log('--+---------------+--------------+---------+----------------');
results.forEach((r, i) =>
  console.log(`${String(i + 1).padStart(1)} | ${r.handwritten.padEnd(13)} | ${r.normalized.padEnd(12)} | ${(r.matched ? 'YES' : 'NO ').padEnd(7)} | ${r.matched ? '"' + r.value + '"' : 'near: ' + r.near.join(', ')}`),
);
const ok = results.filter((r) => r.matched).length;
console.log(`\nmatched ${ok}/${results.length}`);
