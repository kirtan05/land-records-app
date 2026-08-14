// End-to-end proof of the §3 goal, on real SQLite:
//   "exporting either database and importing it into the other is a no-op when the content
//    is the same, and a clean union when it is not."
//
//   node tools/identity/test-sync.mjs

import * as DB from '../../src/sync-db.mjs';
import { SYNC_TABLES } from '../../src/sync-schema.mjs';
import * as I from '../../src/identity.mjs';

let pass = 0;
const failures = [];
const check = (label, actual, expected) => {
  if (actual === expected) { pass++; return; }
  failures.push(`${label}\n    expected ${JSON.stringify(expected)}\n    actual   ${JSON.stringify(actual)}`);
};

const PLACE = I.placeId('15', '03', '029');
const SU = I.surveyUid(PLACE, '221/p');

/** A small but representative database: a place, its names, a survey, a case, a mark. */
function seed(db, origin, opts = {}) {
  const put = (t, row) => DB.upsertAll(db, t, [DB.stamp(db, t, row, origin, opts.now ?? 1000)]);
  put('place', { uid: PLACE, district_code: '15', taluka_code: '03', village_code: '029' });
  put('place_name', { uid: I.uid('pn', PLACE, 'gu', 'anyror'), place_id: PLACE, script: 'gu', source: 'anyror', name: 'ભરોડા' });
  put('place_name', { uid: I.uid('pn', PLACE, 'en', 'ircms'), place_id: PLACE, script: 'en', source: 'ircms', name: 'Bharoda' });
  put('survey', { uid: SU, place_id: PLACE, token: '221_P', survey_no: '221/p', area: opts.area ?? '1-2-3' });
  put('survey_alias', { uid: I.uid('sa', SU, '226/p૧ ~~ '), survey_uid: SU, raw: '226/p૧ ~~ ', source: 'anyror' });
  put('ircms_case', { uid: I.caseUid(SU, '1234567'), survey_uid: SU, data_id: '1234567', case_no: 'ABC/1/2020', case_status: 'PENDING' });
  return db;
}

// ---------------------------------------------------------------------------
// 1. Idempotence: import the same export twice, then a third time.
// ---------------------------------------------------------------------------
{
  const a = DB.open(':memory:');
  seed(a, 'laptop');
  const bundle = DB.exportBundle(a);

  const b = DB.open(':memory:');
  const first = DB.importBundle(b, bundle);
  check('fresh import inserts every row', first.insert, 6);
  check('fresh import changes nothing else', first.update + first.keep_local, 0);

  const second = DB.importBundle(b, bundle);
  check('RE-IMPORT IS A PURE NO-OP: nothing inserted', second.insert, 0);
  check('RE-IMPORT IS A PURE NO-OP: nothing updated', second.update, 0);
  check('RE-IMPORT IS A PURE NO-OP: all no-ops', second.noop, 6);

  DB.importBundle(b, bundle);
  check('a third import is still a no-op', DB.fingerprint(b), DB.fingerprint(a));
  check('the two databases are identical', DB.fingerprint(b), DB.fingerprint(a));
}

// ---------------------------------------------------------------------------
// 2. The same real records scraped INDEPENDENTLY on both machines must collide,
//    not duplicate — even with different clocks and a different write order.
// ---------------------------------------------------------------------------
{
  const laptop = DB.open(':memory:');
  const phone = DB.open(':memory:');
  seed(laptop, 'laptop', { now: 1000 });
  seed(phone, 'phone', { now: 5000 }); // different device, different clock, same facts

  const before = DB.importBundle(laptop, DB.exportBundle(phone));
  check('independent scrapes of the same facts do not duplicate', before.insert, 0);
  check('...they merge as no-ops', before.noop, 6);

  let rows = 0;
  for (const t of SYNC_TABLES) rows += laptop.prepare(`SELECT COUNT(*) c FROM ${t}`).get().c;
  check('the union has exactly the original row count', rows, 6);
  check('and both databases agree', DB.fingerprint(laptop), DB.fingerprint(phone));
}

// ---------------------------------------------------------------------------
// 3. Convergence: genuinely different content, merged BOTH ways, must agree.
// ---------------------------------------------------------------------------
{
  const laptop = DB.open(':memory:');
  const phone = DB.open(':memory:');
  seed(laptop, 'laptop', { now: 1000, area: 'OLD' });
  seed(phone, 'phone', { now: 2000, area: 'NEW' }); // phone re-scraped later and saw a new area

  const lb = DB.exportBundle(laptop);
  const pb = DB.exportBundle(phone);
  DB.importBundle(laptop, pb);
  DB.importBundle(phone, lb);

  check('CONVERGENCE: both sides land on the same database', DB.fingerprint(laptop), DB.fingerprint(phone));
  check('and the newer scrape won', laptop.prepare('SELECT area FROM survey WHERE uid=?').get(SU).area, 'NEW');

  // Exchanging bundles again must now change nothing on either side.
  const again = DB.importBundle(laptop, DB.exportBundle(phone));
  check('a second exchange is a no-op', again.insert + again.update, 0);
}

// ---------------------------------------------------------------------------
// 4. Tombstones propagate; physical deletes would resurrect.
// ---------------------------------------------------------------------------
{
  const laptop = DB.open(':memory:');
  const phone = DB.open(':memory:');
  seed(laptop, 'laptop');
  DB.importBundle(phone, DB.exportBundle(laptop));

  const row = laptop.prepare('SELECT * FROM ircms_case WHERE survey_uid=?').get(SU);
  const dead = DB.stamp(laptop, 'ircms_case', { ...row, deleted: 1, updated_at: undefined, content_hash: undefined }, 'laptop');
  DB.upsertAll(laptop, 'ircms_case', [dead]);

  DB.importBundle(phone, DB.exportBundle(laptop));
  check('a tombstone propagates', phone.prepare('SELECT deleted FROM ircms_case WHERE uid=?').get(row.uid).deleted, 1);
  check('the row is still present, not physically gone',
    phone.prepare('SELECT COUNT(*) c FROM ircms_case').get().c, 1);

  // The other way round: the phone must not resurrect it from its older copy.
  DB.importBundle(laptop, DB.exportBundle(phone));
  check('and it does not resurrect', laptop.prepare('SELECT deleted FROM ircms_case WHERE uid=?').get(row.uid).deleted, 1);
}

// ---------------------------------------------------------------------------
// 5. §2: a re-fetch must not destroy the user's old-survey decisions.
// ---------------------------------------------------------------------------
{
  const db = DB.open(':memory:');
  const uid = I.surveyLinkUid(SU, '221_1');
  // The user rejected this candidate.
  DB.upsertAll(db, 'survey_link', [DB.stamp(db, 'survey_link',
    { uid, current_survey_uid: SU, old_token: '221_1', state: 'rejected', source: 'user' }, 'phone')]);

  // A later auto-match proposes it again, with a much newer clock.
  const counts = DB.merge(db, 'survey_link',
    [{ uid, current_survey_uid: SU, old_token: '221_1', state: 'candidate', source: 'automatch', updated_at: 9e12, deleted: 0 }],
    { fromScraper: true });

  check('auto-matching cannot un-reject a link', counts.rejected, 1);
  check('the rejection survives', db.prepare('SELECT state FROM survey_link WHERE uid=?').get(uid).state, 'rejected');
}

// ---------------------------------------------------------------------------
// 6. Incremental export: only what changed since the last sync.
// ---------------------------------------------------------------------------
{
  const db = DB.open(':memory:');
  seed(db, 'laptop', { now: 1000 });
  const watermark = DB.maxUpdatedAt(db);

  DB.upsertAll(db, 'survey', [DB.stamp(db, 'survey',
    { uid: I.surveyUid(PLACE, '999'), place_id: PLACE, token: '999', survey_no: '999' }, 'laptop', 50000)]);

  const delta = DB.exportBundle(db, watermark);
  const lines = delta.trim().split('\n').length - 1; // minus the header
  check('an incremental export carries only the new row', lines, 1);

  const other = DB.open(':memory:');
  DB.importBundle(other, DB.exportBundle(db)); // full
  check('a full export still carries everything', DB.fingerprint(other), DB.fingerprint(db));
}

if (failures.length) {
  console.error(`\n${failures.length} FAILED, ${pass} passed\n`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`sync: ${pass} checks passed (real SQLite, ${SYNC_TABLES.length} tables)`);
