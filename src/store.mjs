// Flat-file "database": per-case JSON, an index.csv, and a resume state file.
import { mkdirSync, writeFileSync, readFileSync, existsSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';
import { REPO } from './repo-root.mjs';

export const OUT = REPO+'/output';
const STATE = join(OUT, '_state.json');
const INDEX = join(OUT, 'index.csv');
const INDEX_COLS = [
  'survey_key', 'survey_token', 'case_index', 'sr_no', 'data_id', 'offcode',
  'registration_no', 'status', 'disposal_date', 'parties', 'case_pdf', 'order_pdf',
  'order_downloaded', 'scraped_at',
];

export function ensureOut() {
  mkdirSync(OUT, { recursive: true });
  if (!existsSync(INDEX)) writeFileSync(INDEX, INDEX_COLS.join(',') + '\n');
}
export function surveyDir(token) {
  const d = join(OUT, token);
  mkdirSync(d, { recursive: true });
  return d;
}

const csvCell = (v) => '"' + String(v ?? '').replace(/"/g, '""').replace(/\r?\n/g, ' ') + '"';
export function appendIndexRow(row) {
  appendFileSync(INDEX, INDEX_COLS.map((c) => csvCell(row[c])).join(',') + '\n');
}
export function writeCaseJson(token, n, obj) {
  writeFileSync(join(surveyDir(token), `case${n}.json`), JSON.stringify(obj, null, 2));
}

export function readState() {
  if (!existsSync(STATE)) return { surveys: {} };
  try { return JSON.parse(readFileSync(STATE, 'utf8')); } catch { return { surveys: {} }; }
}
export function writeState(s) { writeFileSync(STATE, JSON.stringify(s, null, 2)); }
export function isDone(key) { return readState().surveys[key]?.status === 'done'; }
export function markSurvey(key, info) {
  const s = readState();
  s.surveys[key] = { ...(s.surveys[key] || {}), ...info };
  writeState(s);
}
