// Builds a digital catalog of all valid Bharoda survey numbers from recon data.
import { readFileSync, writeFileSync } from 'node:fs';
import { normalizeSurvey, buildCatalog } from './src/normalize.mjs';

const recon = JSON.parse(readFileSync('./recon-survey-options.json', 'utf8'));
const sv = recon.find((x) => x.id === 'sel_survey_no') || recon.sort((a, b) => b.count - a.count)[0];
const opts = sv.options.filter((o) => o.value !== '-1');

const catalog = buildCatalog(opts);

// flat catalog rows
const rows = [];
for (const [key, entries] of catalog) {
  for (const e of entries) rows.push({ normalized: key, value: e.value, display: e.text });
}
rows.sort((a, b) => a.normalized.localeCompare(b.normalized, 'en', { numeric: true }));

writeFileSync('./survey-catalog.json', JSON.stringify({ village: 'Bharoda', count: rows.length, rows }, null, 2));
const csv = 'normalized,display,value\n' + rows.map((r) => `"${r.normalized}","${r.display}","${r.value.replace(/"/g, '""')}"`).join('\n');
writeFileSync('./survey-catalog.csv', csv);

console.log('total options:', opts.length, '| unique normalized keys:', catalog.size);
const dups = rows.length - new Set(rows.map((r) => r.normalized)).size;
console.log('collisions (same normalized key, multiple values):', dups);

console.log('\n--- everything matching base 221 ---');
for (const r of rows.filter((r) => /^221(\/|$)/.test(r.normalized))) console.log(`${r.normalized}   <=   "${r.display}"   (value="${r.value}")`);

console.log('\n--- normalization demo (handwriting -> key) ---');
for (const t of ['221 p', '221/p', '221', '18/1અ', '11/p3', '6/1/અ', '2/પ']) console.log(`"${t}"  ->  "${normalizeSurvey(t)}"`);
