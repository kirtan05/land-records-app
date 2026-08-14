// Desktop half of the cross-language identity contract.
//   node tools/identity/test.mjs
// The Kotlin half is android/app/src/test/java/com/landrecords/app/data/identity/IdentityVectorsTest.kt
// and reads the SAME tools/identity/vectors.json. Both must pass.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import * as I from '../../src/identity.mjs';
import * as S from '../../src/sync-schema.mjs';
import * as M from '../../src/merge.mjs';
import * as O from '../../src/old-survey-match.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const V = JSON.parse(readFileSync(join(here, 'vectors.json'), 'utf8'));

let pass = 0;
const failures = [];

function check(group, label, actual, expected) {
  if (actual === expected) { pass++; return; }
  failures.push(`${group}: ${label}\n    expected ${JSON.stringify(expected)}\n    actual   ${JSON.stringify(actual)}`);
}

for (const c of V.surveyToken) {
  check('surveyToken', `${JSON.stringify(c.in)} (${c.why})`, I.surveyToken(c.in), c.out);
}

for (const c of V.placeId) {
  check('placeId', `${c.district}/${c.taluka}/${c.village}`, I.placeId(c.district, c.taluka, c.village), c.out);
}

for (const c of V.provisionalPlaceId) {
  check('provisionalPlaceId', `${c.district}|${c.taluka}|${c.village}`,
    I.provisionalPlaceId(c.district, c.taluka, c.village), c.out);
}

for (const c of V.surveyUid) {
  check('surveyUid', `${c.placeId} ${JSON.stringify(c.survey)}`, I.surveyUid(c.placeId, c.survey), c.out);
}

for (const c of V.uid) {
  check('uid', `${c.kind}(${c.parts.join(', ')})`, I.uid(c.kind, ...c.parts), c.out);
}

for (const c of V.canonicalCell) {
  check('canonicalCell', JSON.stringify(c.in), I.canonicalCell(c.in), c.out);
}

for (const c of V.contentHash) {
  check('contentHash', JSON.stringify(c.cols), I.contentHash(c.cols), c.out);
}

// Properties the vectors can't express as single input→output pairs.

// The tokenizer must be idempotent: token(token(x)) == token(x). If it were not, a
// migration that re-tokenizes an already-migrated database would change every key.
for (const c of V.surveyToken) {
  const once = I.surveyToken(c.in);
  check('idempotence', `token(token(${JSON.stringify(c.in)}))`, I.surveyToken(once), once);
}

// Distinct uid kinds over identical parts must not collide — the kind is inside the hash,
// not merely a cosmetic prefix.
{
  const a = I.uid('rs', 'x', 'y');
  const b = I.uid('ic', 'x', 'y');
  check('kind-in-hash', 'rs vs ic body differs', a.slice(3) !== b.slice(3), true);
}

// The unit separator must actually separate: ("a","bc") and ("ab","c") are different rows.
{
  const a = I.uid('rs', 'a', 'bc');
  const b = I.uid('rs', 'ab', 'c');
  check('separator', 'uid("a","bc") != uid("ab","c")', a !== b, true);
}
{
  const a = I.contentHash(['a', 'bc']);
  const b = I.contentHash(['ab', 'c']);
  check('separator', 'contentHash("a","bc") != contentHash("ab","c")', a !== b, true);
}

// NULL and "" must hash differently, or a cleared column would look unchanged.
check('null-vs-empty', 'contentHash([null]) != contentHash([""])',
  I.contentHash([null]) !== I.contentHash(['']), true);

// A provisional id is never mistaken for a coded one.
check('provisional', 'isProvisionalPlace(gj?:…)', I.isProvisionalPlace(I.provisionalPlaceId('a', 'b', 'c')), true);
check('provisional', 'isProvisionalPlace(gj:…)', I.isProvisionalPlace(I.placeId('15', '03', '029')), false);

// ---------------------------------------------------------------------------
// §3 merge engine
// ---------------------------------------------------------------------------

for (const t of V.syncedCols.tables) {
  check('syncedCols', t, JSON.stringify(S.syncedCols(t)), JSON.stringify(V.syncedCols.cols[t]));
  // `deleted` must be last in every table, or a new table could silently omit it and its
  // tombstones would merge as no-ops.
  check('syncedCols', `${t} ends with deleted`, S.syncedCols(t).at(-1), 'deleted');
}
check('syncedCols', 'table list', JSON.stringify(S.SYNC_TABLES), JSON.stringify(V.syncedCols.tables));
check('syncedCols', 'meta cols', JSON.stringify(S.SYNC_META), JSON.stringify(V.syncedCols.meta));
check('syncedCols', 'user-authored', JSON.stringify([...S.USER_AUTHORED]), JSON.stringify(V.syncedCols.userAuthored));

for (const c of V.merge) {
  const actual = M.decide(c.table, c.local, c.incoming);
  if (c.tiebreak) {
    // No fixed answer — the rule is only that greater content hash wins, identically on
    // both machines. Assert against that rule rather than a hard-coded side.
    const lh = M.hashRow(c.table, c.local);
    const rh = M.hashRow(c.table, c.incoming);
    check('merge', `${c.table} tiebreak (${c.why})`, actual, rh > lh ? M.UPDATE : M.KEEP_LOCAL);
  } else {
    check('merge', `${c.table} -> ${c.out} (${c.why})`, actual.toUpperCase(), c.out);
  }
}

for (const c of V.nextUpdatedAt) {
  check('nextUpdatedAt', c.why, M.nextUpdatedAt(c.now, c.localMax), c.out);
}

// Re-merging an export must be a pure no-op — the property the whole design rests on.
{
  const rows = [
    { uid: 'a', place_id: 'gj:15:03:029', token: '221_P', updated_at: 100, deleted: 0 },
    { uid: 'b', place_id: 'gj:15:03:029', token: '222_P', updated_at: 100, deleted: 0 },
  ];
  const first = M.mergeTable('survey', rows, new Map());
  check('idempotence', 'first import inserts', first.writes.length, 2);
  const local = new Map(first.writes.map((r) => [r.uid, r]));
  const second = M.mergeTable('survey', rows, local);
  check('idempotence', 're-import writes nothing', second.writes.length, 0);
  check('idempotence', 're-import is all no-ops', second.counts.noop, 2);
  // ...and again with a *newer* clock, which must still change nothing.
  const third = M.mergeTable('survey', rows.map((r) => ({ ...r, updated_at: 9e9 })), local);
  check('idempotence', 'newer clock, same content, still a no-op', third.writes.length, 0);
}

// A scraper may never write user-authored tables (§3).
{
  const r = [{ uid: 'm1', target_uid: 'x', color: 'red', updated_at: 1, deleted: 0 }];
  check('user-authored', 'scraper cannot write mark',
    M.mergeTable('mark', r, new Map(), { fromScraper: true }).writes.length, 0);
  check('user-authored', 'an import CAN write mark',
    M.mergeTable('mark', r, new Map()).writes.length, 1);
}

// An INCOMING row's declared content_hash is never trusted — only our own stored hash is.
// A sender claiming "same as yours" while carrying different columns must still be seen as
// a change, or the two databases would silently diverge and never reconcile.
{
  const localRow = { uid: 'a', place_id: 'p', token: '221_P', area: 'OLD', updated_at: 1, deleted: 0 };
  localRow.content_hash = M.hashRow('survey', localRow);
  const liar = { uid: 'a', place_id: 'p', token: '221_P', area: 'NEW', updated_at: 2, deleted: 0,
                 content_hash: localRow.content_hash }; // lies: claims to match local
  const res = M.mergeTable('survey', [liar], new Map([['a', localRow]]));
  check('hash-recompute', 'a lying incoming hash is recomputed, not believed', res.writes.length, 1);
  check('hash-recompute', 'and the written row carries the TRUE hash',
    res.writes[0].content_hash, M.hashRow('survey', { ...liar, content_hash: undefined }));
}

// An unknown table is an error, not a silently empty hash.
{
  let threw = false;
  try { S.syncedCols('not_a_table'); } catch { threw = true; }
  check('schema', 'unknown table throws', threw, true);
}

// ---------------------------------------------------------------------------
// §2 old survey numbers
// ---------------------------------------------------------------------------

check('oldSurveyMatch', 'fetch-without-asking threshold', O.FETCH_WITHOUT_ASKING, V.oldSurveyMatch.fetchWithoutAsking);

for (const c of V.oldSurveyMatch.cases) {
  const ranked = O.rank(c.current, c.options);
  check('rank', `${c.current} (${c.why})`, JSON.stringify(ranked.map((r) => r.token)), JSON.stringify(c.rankedTokens));
  const p = O.plan(c.current, c.options);
  check('plan', `${c.current} fetchNow`, p.fetchNow.length, c.fetchNow);
  check('plan', `${c.current} mustAsk`, p.mustAsk, c.mustAsk);
  // Nothing may be dropped: everything is either fetched now or explicitly deferred.
  check('plan', `${c.current} nothing lost`, p.fetchNow.length + p.deferred.length, ranked.length);
}

for (const c of V.oldSurveyMatch.needsDecision) {
  const ranked = c.ranked.map((t) => ({ raw: t, token: t, exact: false }));
  check('needsDecision', c.why, JSON.stringify(O.needsDecision(ranked, c.existing).map((x) => x.token)), JSON.stringify(c.out));
}

// The link uid is the PAIR, so the same decision on two machines is one row (§2 rule 2).
{
  const a = O.linkRow('gj:15:03:029/174_P1', '174_1', 'rejected', 'user@laptop');
  const b = O.linkRow('gj:15:03:029/174_P1', '174_1', 'confirmed', 'user@phone');
  check('link-uid', 'same pair -> same uid whoever decided', a.uid, b.uid);
  const other = O.linkRow('gj:15:03:029/174_P1', '174_2', 'rejected', 'user');
  check('link-uid', 'a different old token -> a different row', a.uid !== other.uid, true);
}

if (failures.length) {
  console.error(`\n${failures.length} FAILED, ${pass} passed\n`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`identity+merge: ${pass} checks passed (${V.surveyToken.length} token, ${V.uid.length} uid, ${V.merge.length} merge vectors)`);
