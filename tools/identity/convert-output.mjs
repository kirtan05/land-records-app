// §5, desktop half: convert the existing output/ tree into the synced schema.
//
//   node tools/identity/convert-output.mjs --db /tmp/land.db          # convert
//   node tools/identity/convert-output.mjs --check                    # idempotence test
//
// The converter is needed for the current corpus, and it doubles as the idempotence test the
// spec asks for: running it twice must produce a byte-identical database. It cannot recover
// what the scrapers never wrote — cascade codes and data_id — so places land as provisional
// `gj?:` ids and cases without a data_id are reported rather than guessed at.

import { readdirSync, readFileSync, existsSync, statSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import * as DB from '../../packages/core/sync-db.mjs';
import * as I from '../../packages/core/identity.mjs';

const OUT = 'output';
const ORIGIN = 'desktop-convert';

/**
 * Directory names in output/ are mostly bare survey tokens, but three are village-prefixed
 * ("Bhalej_174_P1", "Salun_125", "Valetva_41"). Tokenizing the whole string would fold the
 * village name into the survey number, so the prefix is split off first.
 */
const VILLAGE_PREFIXES = ['Bhalej', 'Salun', 'Valetva'];

export function splitDirName(name) {
  for (const v of VILLAGE_PREFIXES) {
    if (name.toLowerCase().startsWith(v.toLowerCase() + '_')) {
      return { village: v, survey: name.slice(v.length + 1) };
    }
  }
  // Everything else came from the original Bharoda run.
  return { village: 'Bharoda', survey: name };
}

export function convert(db, { log = () => {} } = {}) {
  const problems = [];
  let surveys = 0, cases = 0, blobs = 0, aliases = 0;
  const placeCache = new Map();

  const placeFor = (village) => {
    if (!placeCache.has(village)) {
      // The scrapers never recorded cascade codes, so these are deliberately provisional.
      // A later place_merge row rewrites them onto real coded ids, once, by uid.
      const id = I.provisionalPlaceId('Anand', '', village);
      placeCache.set(village, id);
      DB.upsertAll(db, 'place', [DB.stamp(db, 'place',
        { uid: id, district_code: null, taluka_code: null, village_code: null }, ORIGIN, 1)]);
      DB.upsertAll(db, 'place_name', [DB.stamp(db, 'place_name',
        { uid: I.uid('pn', id, 'village', 'en', village), place_id: id, script: 'en', source: 'legacy:village', name: village }, ORIGIN, 1)]);
    }
    return placeCache.get(village);
  };

  const dirs = readdirSync(OUT, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name !== 'captcha')
    .map((d) => d.name)
    .sort(); // stable order, so two runs write in the same sequence

  for (const name of dirs) {
    const { village, survey } = splitDirName(name);
    const place = placeFor(village);
    const token = I.surveyToken(survey);
    if (!token) { problems.push(`${name}: tokenizes to empty`); continue; }

    const su = `${place}/${token}`;
    surveys++;
    DB.upsertAll(db, 'survey', [DB.stamp(db, 'survey',
      { uid: su, place_id: place, token, survey_no: survey }, ORIGIN, 1)]);

    // Preserve the original directory name — it is the only record of how this was stored.
    for (const raw of new Set([survey, name])) {
      aliases++;
      DB.upsertAll(db, 'survey_alias', [DB.stamp(db, 'survey_alias',
        { uid: I.uid('sa', su, raw), survey_uid: su, raw, source: 'output-dir' }, ORIGIN, 1)]);
    }

    const dir = join(OUT, name);
    for (const f of readdirSync(dir).sort()) {
      const full = join(dir, f);
      if (!statSync(full).isFile()) continue;

      if (/^case\d+\.json$/.test(f)) {
        let obj;
        try { obj = JSON.parse(readFileSync(full, 'utf8')); } catch { problems.push(`${name}/${f}: bad JSON`); continue; }
        const dataId = String(obj.data_id ?? '').trim();
        if (!dataId) {
          // Identity would have to be guessed from display text, which drifts. Report it.
          problems.push(`${name}/${f}: no data_id — cannot key this case, skipped`);
          continue;
        }
        cases++;
        DB.upsertAll(db, 'ircms_case', [DB.stamp(db, 'ircms_case', {
          uid: I.caseUid(su, dataId),
          survey_uid: su,
          data_id: dataId,
          case_no: obj.case_str ?? obj.registration_no ?? null,
          case_status: obj.status ?? null,
          office: obj.offcode ?? obj.office ?? null,
          dtv: obj.dtv ?? null,
          parties: obj.parties ?? null,
          survno: obj.survey ?? null,
          // §7: these five are corrupt at rest on the desktop (regex bleed,
          // packages/core/scrape.mjs:100-105). Re-derived on import rather than carried over, so the
          // new schema does not inherit the bug with a nicer column type.
          disposal_date: cleanDate(obj.disposal_date),
          disposal_type: cleanShort(obj.disposal_type),
          no_appellant: cleanShort(obj.no_appellant),
          court_no: cleanShort(obj.court_no),
        }, ORIGIN, 1)]);
        continue;
      }

      if (/\.(pdf|tif|tiff|png|jpg)$/i.test(f)) {
        const bytes = readFileSync(full);
        const sha = I.blobUid(bytes);
        blobs++;
        DB.upsertAll(db, 'blob', [DB.stamp(db, 'blob',
          { uid: sha, size: bytes.length, mime: mimeOf(f) }, ORIGIN, 1)]);
      }
    }
  }

  log(`converted ${surveys} surveys, ${cases} cases, ${blobs} files, ${aliases} aliases; ${problems.length} problems`);
  return { surveys, cases, blobs, aliases, problems };
}

const mimeOf = (f) =>
  /\.pdf$/i.test(f) ? 'application/pdf'
    : /\.tiff?$/i.test(f) ? 'image/tiff'
      : /\.png$/i.test(f) ? 'image/png' : 'image/jpeg';

/** A date, or null. The desktop's regex bleed put whole sentences in this column. */
const cleanDate = (v) => {
  const m = /(\d{2}[-/]\d{2}[-/]\d{4})/.exec(String(v ?? ''));
  return m ? m[1].replace(/\//g, '-') : null;
};

/** A short label, or null — anything sentence-length is bleed, not a value. */
const cleanShort = (v) => {
  const s = String(v ?? '').replace(/\s+/g, ' ').trim();
  return s && s.length <= 40 ? s : null;
};

// ---------------------------------------------------------------------------

const args = process.argv.slice(2);
if (args.includes('--check')) {
  // The spec's requirement: running the converter twice must produce the same database.
  if (!existsSync(OUT)) { console.error('no output/ directory here'); process.exit(1); }

  const a = DB.open(':memory:');
  const r1 = convert(a, { log: (m) => console.log('  run 1:', m) });
  const fp1 = DB.fingerprint(a);

  // Same database, converted again — must be a pure no-op.
  const r2 = convert(a, { log: (m) => console.log('  run 2:', m) });
  const fp2 = DB.fingerprint(a);

  // And a completely fresh database — must reach the identical fingerprint.
  const b = DB.open(':memory:');
  convert(b, {});
  const fp3 = DB.fingerprint(b);

  const ok = fp1 === fp2 && fp1 === fp3;
  console.log(`\n  fingerprint run1 = ${fp1.slice(0, 16)}…`);
  console.log(`  fingerprint run2 = ${fp2.slice(0, 16)}…  ${fp1 === fp2 ? 'SAME' : 'DIFFERENT'}`);
  console.log(`  fingerprint fresh= ${fp3.slice(0, 16)}…  ${fp1 === fp3 ? 'SAME' : 'DIFFERENT'}`);
  if (r1.problems.length) {
    console.log(`\n  ${r1.problems.length} problems (first 10):`);
    for (const p of r1.problems.slice(0, 10)) console.log('    -', p);
  }
  console.log(ok ? '\nIDEMPOTENT ✓' : '\nNOT IDEMPOTENT ✗');
  process.exit(ok ? 0 : 1);
}

const dbFlag = args.indexOf('--db');
if (dbFlag >= 0) {
  const path = args[dbFlag + 1];
  if (existsSync(path)) rmSync(path);
  const db = DB.open(path);
  const r = convert(db, { log: console.log });
  for (const p of r.problems.slice(0, 20)) console.log('  problem:', p);
  console.log(`wrote ${path}`);
}
