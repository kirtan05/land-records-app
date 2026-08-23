// Merge semantics — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §3.
//
// Mirrored byte-for-byte in the app at
//   apps/android/app/src/main/java/com/landrecords/app/data/sync/MergeEngine.kt
// and both are held to the `merge` section of tools/identity/vectors.json.
//
// The property the whole design rests on: exporting either database and importing it into
// the other is a NO-OP when the content is the same, and a clean union when it is not.

import { contentHash } from './identity.mjs';
import { syncedCols, USER_AUTHORED } from './sync-schema.mjs';

/** What merging one incoming row did. `noop` is the one that proves idempotence. */
export const INSERT = 'insert';
export const UPDATE = 'update';
export const NOOP = 'noop';
export const KEEP_LOCAL = 'keep_local';
export const REJECTED = 'rejected'; // refused by a table rule, e.g. a scraper touching a mark

/** content_hash of a row object, using the table's fixed column order. */
export function hashRow(table, row) {
  return contentHash(syncedCols(table).map((c) => row[c] ?? null));
}

/**
 * Decide what to do with one incoming row.
 *
 *   L is null                      -> INSERT
 *   hashes equal                   -> NOOP        <- idempotence, and immune to clock skew
 *   R.updated_at > L.updated_at    -> UPDATE
 *   R.updated_at < L.updated_at    -> KEEP_LOCAL
 *   equal timestamps               -> greater content_hash wins   <- deterministic tiebreak
 *
 * Last-write-wins with a content-hash short-circuit. These are scraped facts with a monotone
 * information gradient — a later scrape sees the same or newer state — so per-field CRDT
 * merging buys nothing. The short-circuit is what makes re-merging the same export a pure
 * no-op, and stops clock skew from flip-flopping identical content.
 *
 * [local] and [incoming] must already carry `content_hash`; use [hashRow] if they don't.
 */
export function decide(table, local, incoming) {
  if (!local) return INSERT;

  const lh = local.content_hash ?? hashRow(table, local);
  const rh = incoming.content_hash ?? hashRow(table, incoming);
  if (lh === rh) return NOOP;

  // survey_link is user knowledge. Auto-matching may propose, never overrule (§2 rule 3).
  if (table === 'survey_link') {
    const incomingIsAuto = incoming.state === 'candidate';
    const localIsCurated = local.state && local.state !== 'candidate';
    if (incomingIsAuto && localIsCurated) return REJECTED;
  }

  const lt = Number(local.updated_at ?? 0);
  const rt = Number(incoming.updated_at ?? 0);
  if (rt > lt) return UPDATE;
  if (rt < lt) return KEEP_LOCAL;
  return rh > lh ? UPDATE : KEEP_LOCAL;
}

/**
 * Merge a batch of incoming rows for one table against a lookup of local rows by uid.
 * Pure: returns the decisions and the rows to write, and mutates nothing. The caller does
 * the SQL, so the same function serves node-SQLite, Room, and the tests.
 *
 * [opts.fromScraper] marks the batch as coming from a fetch rather than from an import of
 * another database. Scrapers may not write user-authored tables at all (§3), except for
 * proposing survey_link candidates, which `decide` handles.
 */
export function mergeTable(table, incomingRows, localByUid, opts = {}) {
  const cols = syncedCols(table);
  const counts = { insert: 0, update: 0, noop: 0, keep_local: 0, rejected: 0 };
  const writes = [];

  for (const rawIncoming of incomingRows) {
    const incoming = { ...rawIncoming };
    if (!incoming.uid) throw new Error(`${table}: incoming row has no uid`);
    // Always recompute rather than trusting the sender: a row whose declared hash disagreed
    // with its own columns would merge as "changed" forever, or worse, never.
    incoming.content_hash = contentHash(cols.map((c) => incoming[c] ?? null));

    if (opts.fromScraper && USER_AUTHORED.has(table) && table !== 'survey_link') {
      counts.rejected++;
      continue;
    }

    const local = localByUid.get(incoming.uid) ?? null;
    const action = decide(table, local, incoming);
    counts[action]++;
    if (action === INSERT || action === UPDATE) writes.push(incoming);
  }
  return { counts, writes };
}

/**
 * The timestamp a writer must stamp on a row it changes.
 *
 * `max(now, local_max + 1)` — so a device whose clock is behind cannot have *all* of its
 * writes silently lose to the other machine's. Without the +1, two writes inside the same
 * millisecond would tie and fall to the hash tiebreak, which is deterministic but arbitrary.
 */
export function nextUpdatedAt(now, localMax) {
  const m = Number(localMax ?? 0);
  return Math.max(Number(now), m + 1);
}

/**
 * Tombstone, never physical delete (§3). A physical delete resurrects on the next merge,
 * because the other database still holds the row and nothing records that it went away.
 * Parents tombstone children explicitly — there is no SQL cascade — and blobs are never
 * deleted by sync at all; local reachability GC handles those.
 */
export function tombstone(table, row, now, localMax) {
  const out = { ...row, deleted: 1, updated_at: nextUpdatedAt(now, localMax) };
  out.content_hash = hashRow(table, out);
  return out;
}
