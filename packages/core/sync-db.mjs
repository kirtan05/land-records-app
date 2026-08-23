// The synced database — §1–§4. Desktop half; the app's is data/sync/SyncDb.kt.
//
// Deliberately schema-driven rather than hand-written per table: every statement is
// generated from SYNC_TABLES/syncedCols, so adding a table cannot leave a DAO, an export
// path or a content hash behind.

import { DatabaseSync } from 'node:sqlite';
import { SYNC_TABLES, SYNC_META, syncedCols } from './sync-schema.mjs';
import { mergeTable, nextUpdatedAt } from './merge.mjs';
import { contentHash } from './identity.mjs';

/**
 * Columns stored as INTEGER. Everything else is TEXT.
 * `updated_at` MUST be an integer — the merge compares it numerically, and SQLite would
 * otherwise sort "1000" before "999" as text.
 */
const INT_COLS = new Set(['updated_at', 'deleted', 'doc_count', 'ordinal', 'size']);

const colType = (c) => (INT_COLS.has(c) ? 'INTEGER' : 'TEXT');

/** Every column of a table, in physical order: uid first, then meta, then content. */
export function allCols(table) {
  return ['uid', 'content_hash', 'updated_at', 'origin', ...syncedCols(table)];
}

export function open(path) {
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = OFF'); // parents tombstone children explicitly; no cascade (§3)
  createTables(db);
  return db;
}

export function createTables(db) {
  for (const t of SYNC_TABLES) {
    const defs = allCols(t).map((c) => (c === 'uid' ? 'uid TEXT PRIMARY KEY' : `${c} ${colType(c)}`));
    db.exec(`CREATE TABLE IF NOT EXISTS ${t} (${defs.join(', ')})`);
    // Every table is swept by updated_at during an incremental export.
    db.exec(`CREATE INDEX IF NOT EXISTS ${t}_updated ON ${t}(updated_at)`);
  }
}

export function rowsByUid(db, table, uids) {
  const out = new Map();
  if (!uids.length) return out;
  // Chunked to stay under SQLite's variable limit on a big import.
  for (let i = 0; i < uids.length; i += 500) {
    const chunk = uids.slice(i, i + 500);
    const q = db.prepare(`SELECT * FROM ${table} WHERE uid IN (${chunk.map(() => '?').join(',')})`);
    for (const r of q.all(...chunk)) out.set(r.uid, r);
  }
  return out;
}

export function allRows(db, table, sinceUpdatedAt = 0) {
  return db.prepare(`SELECT * FROM ${table} WHERE updated_at > ? ORDER BY uid`).all(sinceUpdatedAt);
}

export function maxUpdatedAt(db) {
  let m = 0;
  for (const t of SYNC_TABLES) {
    const r = db.prepare(`SELECT MAX(updated_at) AS m FROM ${t}`).get();
    if (r?.m) m = Math.max(m, Number(r.m));
  }
  return m;
}

export function upsertAll(db, table, rows) {
  if (!rows.length) return 0;
  const cols = allCols(table);
  const stmt = db.prepare(
    `INSERT INTO ${table} (${cols.join(',')}) VALUES (${cols.map(() => '?').join(',')})
     ON CONFLICT(uid) DO UPDATE SET ${cols.filter((c) => c !== 'uid').map((c) => `${c}=excluded.${c}`).join(', ')}`,
  );
  for (const r of rows) {
    stmt.run(...cols.map((c) => {
      const v = r[c];
      if (v === undefined || v === null) return null;
      if (typeof v === 'boolean') return v ? 1 : 0;
      return v;
    }));
  }
  return rows.length;
}

/**
 * Stamp a row for writing: fill `origin`/`updated_at` if absent and (re)compute the hash.
 * Callers never set content_hash themselves — it is derived, and a hand-set one would rot.
 */
export function stamp(db, table, row, origin, now = Date.now()) {
  const out = { ...row };
  out.origin = out.origin ?? origin;
  out.deleted = out.deleted ?? 0;
  out.updated_at = out.updated_at ?? nextUpdatedAt(now, maxUpdatedAt(db));
  out.content_hash = contentHash(syncedCols(table).map((c) => out[c] ?? null));
  return out;
}

/** Merge a batch into one table and write the winners. Returns the decision counts. */
export function merge(db, table, incoming, opts = {}) {
  const local = rowsByUid(db, table, incoming.map((r) => r.uid));
  const { counts, writes } = mergeTable(table, incoming, local, opts);
  upsertAll(db, table, writes);
  return counts;
}

// ---------------------------------------------------------------------------
// Export / import bundle
// ---------------------------------------------------------------------------

/**
 * A bundle is newline-delimited JSON: one header, then one row per line tagged with its
 * table. JSONL rather than a copy of the .db file because the two sides are Room and
 * node-sqlite — different page formats, same rows — and because it diffs and streams.
 */
export function exportBundle(db, sinceUpdatedAt = 0) {
  const lines = [JSON.stringify({ v: 1, meta: SYNC_META, since: sinceUpdatedAt, tables: SYNC_TABLES })];
  for (const t of SYNC_TABLES) {
    for (const r of allRows(db, t, sinceUpdatedAt)) lines.push(JSON.stringify({ t, r }));
  }
  return lines.join('\n') + '\n';
}

/**
 * Import a bundle. Rows are grouped by table and merged through the same engine the app
 * uses, so an import behaves exactly like a sync — including being a no-op when the
 * content already matches.
 */
export function importBundle(db, text, opts = {}) {
  const byTable = new Map();
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    const o = JSON.parse(line);
    if (!o.t) continue; // the header
    if (!byTable.has(o.t)) byTable.set(o.t, []);
    byTable.get(o.t).push(o.r);
  }
  const totals = { insert: 0, update: 0, noop: 0, keep_local: 0, rejected: 0 };
  // Tables in schema order so parents land before children.
  for (const t of SYNC_TABLES) {
    const rows = byTable.get(t);
    if (!rows?.length) continue;
    const counts = merge(db, t, rows, opts);
    for (const k of Object.keys(totals)) totals[k] += counts[k] ?? 0;
  }
  return totals;
}

/**
 * A stable fingerprint of the whole database: every row's uid+content_hash, in a fixed
 * order. Two databases that merged the same facts must produce the same string, whatever
 * order they saw them in or what their clocks said. This is the idempotence assertion.
 */
export function fingerprint(db) {
  const parts = [];
  for (const t of SYNC_TABLES) {
    for (const r of db.prepare(`SELECT uid, content_hash FROM ${t} ORDER BY uid`).all()) {
      parts.push(`${t}\t${r.uid}\t${r.content_hash}`);
    }
  }
  return contentHash(parts);
}
