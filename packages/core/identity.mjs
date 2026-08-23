// Canonical identity layer — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §1.
//
// EVERY rule in this file is duplicated, byte-for-byte, in the Android app at
//   apps/android/app/src/main/java/com/landrecords/app/data/identity/Identity.kt
// and both are held to the same fixture, tools/identity/vectors.json.
// If you change a rule here you MUST change it there, add a vector, and run both tests:
//   node tools/identity/test.mjs
//   cd apps/android && ./gradlew :app:testDebugUnitTest
//
// The point of the whole file: two machines that scrape the same real record must
// produce the same string for it, so importing one database into the other is a no-op.

import { createHash } from 'node:crypto';

const GU_DIGITS = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };

/**
 * Gujarati letters that appear as survey SUFFIXES, transliterated to ASCII.
 *
 * The spec's original rule was to strip every non-[A-Z0-9_] character. Measured against
 * the 15,293 real dropdown values bundled in assets/surveys, that fused 250 genuinely
 * different surveys into 250 shared tokens — e.g. in Bharoda (gj:15:03:029), all eight of
 *   40/1/અ  40/1/ક  40/1/ફ  40/1/બ  40/1/હ  40/1ઈ  40/1ગ  40/1ડ
 * collapsed onto "40_1", and 1678 collapsed with 1678/અ. Those are separate parcels with
 * separate owners; merging them is exactly the "never invent land data" failure. So the
 * suffix letters are transliterated instead of dropped. See tools/identity/README.md.
 *
 * Each entry is chosen to be distinct from every other, so no two letters can ever share a
 * token. Anything NOT listed here falls through to the codepoint escape in [foreignChar],
 * which guarantees the same property for characters this corpus has not shown us yet.
 */
const GU_LETTERS = {
  'અ': 'A', 'આ': 'AA', 'ઇ': 'I', 'ઈ': 'II', 'ઉ': 'U', 'ઊ': 'UU',
  'એ': 'E', 'ઐ': 'AI', 'ઓ': 'O', 'ઔ': 'AU',
  'ક': 'K', 'ખ': 'KH', 'ગ': 'G', 'ઘ': 'GH',
  'ચ': 'C', 'છ': 'CH', 'જ': 'J', 'ઝ': 'JH',
  'ટ': 'TT', 'ઠ': 'TTH', 'ડ': 'D', 'ઢ': 'DH', 'ણ': 'NN',
  'ત': 'T', 'થ': 'TH', 'દ': 'DD', 'ધ': 'DDH', 'ન': 'N',
  'ફ': 'F', 'બ': 'B', 'ભ': 'BH', 'મ': 'M',
  'ય': 'Y', 'ર': 'R', 'લ': 'L', 'ળ': 'LL', 'વ': 'V',
  'શ': 'SH', 'ષ': 'SS', 'સ': 'S', 'હ': 'H',
  // Dependent vowel signs. A sign only ever follows a consonant and an independent vowel
  // only ever starts a syllable, so sharing a form with the independent letter above can
  // never fuse two different strings.
  'ા': 'AA', 'િ': 'I', 'ી': 'II', 'ુ': 'U', 'ૂ': 'UU',
  'ે': 'E', 'ૈ': 'AI', 'ો': 'O', 'ૌ': 'AU',
  'ં': 'NG', 'ઃ': 'HH',
  // Virama joins two consonants ("ક્ર" = KRA). Kept as a distinct mark rather than dropped,
  // so "ક્ર" and "કર" cannot become the same token.
  '્': 'Q',
};

/**
 * Any character with no explicit mapping becomes "U<hex codepoint>" rather than vanishing.
 * Ugly on purpose: it is visible in a filename, it is deterministic on both machines, and
 * it can never silently fuse two different surveys the way stripping did.
 */
function foreignChar(c) {
  return 'U' + c.codePointAt(0).toString(16).toUpperCase().padStart(4, '0');
}

/** Unit separator. Joins uid parts and content-hash cells; never occurs in scraped text. */
export const US = '\x1f';

/** Sentinel for SQL NULL in a content hash. Distinct from the empty string on purpose. */
export const NULL_CELL = '\x00';

// ---------------------------------------------------------------------------
// 1.2  Survey token
// ---------------------------------------------------------------------------

/**
 * The one canonical survey tokenizer. Rules, in this exact order:
 *
 *   "~~" → " "                       drop AnyRoR's dropdown marker
 *   ૦-૯ → 0-9                        gujarati digits
 *   "પૈકી" → "p", then "પ" → "p"     the word before the letter, else "પૈકી" leaves "pૈકી"
 *   "-" → "/" unless between digits  suffix dash is a delimiter; 690-696 is a range
 *   [\s/|\\]+ → "/"                  every other separator, including handwritten bars
 *   trim "/"
 *   "p/(?=\d)" → "p"                 paiki binds to its number: p/1 → p1
 *   uppercase
 *   "/" → "_"
 *   transliterate [^A-Z0-9_+-]       GU_LETTERS, else the U+XXXX escape — never dropped
 *   "_+" → "_"                       "101__3" → "101_3"
 *   trim "_"                         "100_1_" → "100_1"
 *
 * Two rules differ from the spec as written, both because the spec's version was measured
 * to fuse different surveys together (see tools/identity/README.md):
 *
 *   - Gujarati letters are TRANSLITERATED, not stripped: "845/અ" → "845_A", not "845".
 *   - "+" and "-" are KEPT: "364+365" is one combined survey and "690-696" is a range,
 *     and neither is the same parcel as "364" or as "690/696".
 *
 * The raw form is never lost either way — it is preserved verbatim in survey_alias.
 */
export function surveyToken(raw) {
  if (raw == null) return '';
  let s = String(raw);
  s = s.replace(/~~/g, ' ');
  s = s.replace(/[૦-૯]/g, (c) => GU_DIGITS[c] ?? c);
  s = s.replace(/પૈકી/g, 'p');
  s = s.replace(/પ/g, 'p');
  // A "-" between two digits is a RANGE (690-696) and is part of the number, so it survives
  // into the token. Any other "-" is a suffix delimiter (233-અ, 15-અ-P) and behaves like "/".
  s = s.replace(/(?<![0-9])-|-(?![0-9])/g, '/');
  s = s.replace(/[\s/|\\]+/g, '/');
  s = s.replace(/^\/+|\/+$/g, '');
  s = s.replace(/p\/(?=\d)/g, 'p');
  s = s.toUpperCase();
  s = s.replace(/\//g, '_');
  // Transliterate whatever is left that is not token-safe, so nothing is silently dropped.
  s = s.replace(/[^A-Z0-9_+-]/gu, (c) => GU_LETTERS[c] ?? foreignChar(c));
  s = s.replace(/_+/g, '_');
  s = s.replace(/^_+|_+$/g, '');
  return s;
}

// ---------------------------------------------------------------------------
// 1.1  Place id
// ---------------------------------------------------------------------------

/** Zero-pad a cascade code to its level's width. "3" → "03". Non-digits pass through. */
function padCode(code, width) {
  const s = String(code ?? '').trim();
  if (!/^\d+$/.test(s)) return s;
  return s.padStart(width, '0');
}

/**
 * A coded place id from the AnyRoR/iRCMS cascade codes — the same codes on both sites.
 * Zero-padding is part of the identity, so it is applied here rather than trusted from
 * the caller: `placeId("15","3","29")` and `placeId("15","03","029")` are the same place.
 */
export function placeId(districtCode, talukaCode, villageCode) {
  return `gj:${padCode(districtCode, 2)}:${padCode(talukaCode, 2)}:${padCode(villageCode, 3)}`;
}

/**
 * Case- and whitespace-insensitive form of a place name, used ONLY to derive a
 * provisional id. Trim, collapse internal whitespace runs to one space, lowercase.
 * Names are not transliterated: a Gujarati spelling and its English spelling get
 * different provisional ids, and a later place_merge row reconciles them by uid.
 */
function foldName(s) {
  return String(s ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}

/**
 * A deterministic id for legacy data that has no cascade codes (the 188 existing
 * output/ dirs, and app rows whose crosswalk is ambiguous). Visibly provisional —
 * the "gj?:" prefix — so it can be rewritten to a real coded id later, once, by uid.
 */
export function provisionalPlaceId(district, taluka, village) {
  const key = 'name|' + [district, taluka, village].map(foldName).join('|');
  return 'gj?:' + sha256Hex(key).slice(0, 12);
}

/** True for an id produced by [provisionalPlaceId] — i.e. one still awaiting real codes. */
export function isProvisionalPlace(id) {
  return String(id ?? '').startsWith('gj?:');
}

// ---------------------------------------------------------------------------
// 1.2 / 1.3  Uids
// ---------------------------------------------------------------------------

export function sha256Hex(s) {
  return createHash('sha256').update(Buffer.from(String(s), 'utf8')).digest('hex');
}

/** Readable, not hashed — a survey uid is a debuggable path: "gj:15:03:029/221_P". */
export function surveyUid(place, surveyRaw) {
  return `${place}/${surveyToken(surveyRaw)}`;
}

/**
 * uid(kind, parts…) = prefix + "_" + hex(sha256(kind ␟ parts…))[0:24]
 *
 * Parts are joined with the unit separator so that ("a","bc") and ("ab","c") differ,
 * and empty parts stay significant. Nothing that varies between two scrapes of the
 * same real record may appear here — no timestamps, file paths, DOM ordinals, sr_no,
 * case_index or selectIndex. Those are payload, never identity.
 */
export function uid(kind, ...parts) {
  const material = [kind, ...parts.map((p) => (p == null ? '' : String(p)))].join(US);
  return `${kind}_${sha256Hex(material).slice(0, 24)}`;
}

export const recordSetUid = (surveyUid_, recordType) => uid('rs', surveyUid_, recordType);
export const caseUid = (surveyUid_, dataId) => uid('ic', surveyUid_, 'ircms', dataId);
export const orderUid = (caseUid_, ordinal) => uid('io', caseUid_, ordinal);
export const vfScanUid = (surveyUid_, period, thok, block, oldSurvey) =>
  uid('vs', surveyUid_, 'vf712', period, thok, block, oldSurvey);
export const entryUid = (surveyUid_, entryNumber) => uid('en', surveyUid_, 'entry', entryNumber);
export const deedUid = (place, office, docYear, docNo) => uid('dd', place, 'deed', office, docYear, docNo);
export const deedPartyUid = (deedUid_, partyType, partyName, ordinal) =>
  uid('dp', deedUid_, partyType, partyName, ordinal);
export const deedLinkUid = (deedUid_, surveyUid_) => uid('dl', deedUid_, surveyUid_);
export const surveyLinkUid = (currentSurveyUid, oldToken) => uid('sl', currentSurveyUid, oldToken);

/** Blobs are addressed by the UNtruncated hash of their bytes — no prefix, no slicing. */
export function blobUid(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

// ---------------------------------------------------------------------------
// 3  Content hash
// ---------------------------------------------------------------------------

/**
 * One column's contribution to a content hash. SQLite is loosely typed and JS/Kotlin
 * disagree about number formatting, so the mapping is pinned here:
 *   null    → "\x00"   (a distinct value, NOT "")
 *   boolean → "1"/"0"  (matches how Room stores them)
 *   integer → decimal, no exponent, no trailing ".0"
 *   other   → the string itself
 */
export function canonicalCell(v) {
  if (v === null || v === undefined) return NULL_CELL;
  if (typeof v === 'boolean') return v ? '1' : '0';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error(`non-finite number in content hash: ${v}`);
    if (Number.isInteger(v)) return String(v);
    throw new Error(`non-integer number in content hash: ${v} — store it as text`);
  }
  return String(v);
}

/**
 * content_hash(row) = sha256(join(canonical(col) for col in SYNCED_COLS[table], ␟))
 *
 * `cols` MUST be the table's fixed SYNCED_COLS order (see identitySchema.mjs), not
 * SQLite's column order — the two implementations have to agree on order or every row
 * looks changed. Excludes uid / content_hash / updated_at / origin; INCLUDES deleted.
 */
export function contentHash(values) {
  return sha256Hex(values.map(canonicalCell).join(US));
}
