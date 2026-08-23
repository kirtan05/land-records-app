// Cleanly resets one survey so it can be re-scraped: removes its index.csv rows,
// deletes its output folder, and clears its state entry.
//   node reset-survey.mjs 222/2/p
import { readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { OUT, readState, writeState } from '../../core/store.mjs';
import { surveyToken } from '../../core/normalize.mjs';

const key = process.argv[2];
if (!key) { console.error('usage: node reset-survey.mjs <surveyKey>'); process.exit(1); }
const token = surveyToken(key);

const idx = join(OUT, 'index.csv');
if (existsSync(idx)) {
  const lines = readFileSync(idx, 'utf8').split('\n');
  const header = lines[0];
  const kept = lines.slice(1).filter((l) => l && !l.startsWith(`"${key}",`));
  const removed = lines.slice(1).filter((l) => l).length - kept.length;
  writeFileSync(idx, [header, ...kept].join('\n') + '\n');
  console.log(`index.csv: removed ${removed} rows for ${key}`);
}
const dir = join(OUT, token);
if (existsSync(dir)) { rmSync(dir, { recursive: true, force: true }); console.log('deleted', dir); }
const s = readState(); delete s.surveys[key]; writeState(s);
console.log('reset done for', key);
