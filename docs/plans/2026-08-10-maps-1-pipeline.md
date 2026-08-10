> ## ⚠️ PARTIALLY SUPERSEDED
>
> **Tasks 1–3 shipped** (session, Drive links, catalogue scraper) and are live in
> `tools/ejamin/` — though the scraper has since been rewritten for concurrency
> (~12 min statewide vs ~14 h serial), because ejamingujarat.com is a private
> commercial site, not a `gujarat.gov.in` host, so the AnyRoR politeness rules in the
> Global Constraints below do **not** apply to it.
>
> **Tasks 4–10 are vector-only and dead for Kheda/Anand.** Measured 2026-08-10: all 881
> Kheda + Anand sheets are rasterized — no text layer, no parcel paths, no
> georeferencing. `lib/content.mjs` and `lib/geo.mjs` were written from these tasks and
> only ever worked on the one Ahmedabad sample. Keep them for the ~5% of statewide
> sheets that are vector.
>
> Two errors in this document, for anyone reusing it: the Task 7 test expects
> `pageToLatLng` on the identity matrix to give `[4,3]` — the correct value is `[3,3]`.
> And GDCR scrapes 0 rows because the `selectClass` in Task 3's `TYPES` table is wrong.
>
> Current state: `docs/MAPS.md`. Options: `docs/plans/2026-08-11-maps-future-possibilities.md`.

# Maps, Plan 1 of 2: eJamin catalogue + parcel index pipeline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `tools/ejamin/` — a Node pipeline that catalogues every map sheet eJamin Gujarat publishes and, for Kheda + Anand village maps, extracts parcel polygons, survey-number labels and a lat/long transform into a compact per-village JSON index.

**Architecture:** Pure Node, no new dependencies. Small single-responsibility modules under `tools/ejamin/lib/` (PDF object/stream access, content-stream tokenising, geo registration, geometry, label classification), each covered by `node --test`. Three thin executables on top: `scrape-catalog.mjs`, `build-index.mjs`, `qa-index.mjs`. Nothing here knows about Android; the only contract with Plan 2 is the emitted JSON.

**Tech Stack:** Node 26 (`node --test` built-in runner), ES modules (`"type": "module"` already set in `package.json`), `node:zlib`, `node:fs`, global `fetch`. No new npm packages.

## Global Constraints

- Spec: `docs/specs/2026-08-10-maps-village-cadastre-design.md`. Read it before Task 1.
- **The district id space is per map type.** Kheda is `14` in one tab and `18` in another; Anand is `3` and `20`. Ids MUST be stored and used keyed by type. Never share an id across types.
- **Never invent land data.** If a label cannot be placed, it is omitted — never guessed. Unknown values are `null` in JSON, rendered `—` later.
- Requests to ejamingujarat.com are **serial with a 400 ms delay**, one browser-like `User-Agent`, reusing one session cookie. No concurrency. This matches the project's standing politeness rules for government sites (see the AnyRoR WAF notes).
- New code lives under `tools/ejamin/`. Do not modify `anyror/`, `src/`, or `android/`.
- Style follows `anyror/*.mjs`: ES modules, top-level `await`, `//` comments explaining *why*, no framework.
- Deep indexes are built for **Kheda + Anand village maps only**. The catalogue covers **all seven types, all districts**.

---

## File structure

| File | Responsibility |
|---|---|
| `tools/ejamin/lib/session.mjs` | One eJamin session: CSRF token, cookie, throttled `post()` |
| `tools/ejamin/lib/drive.mjs` | Normalise a Drive `link` → file id, view URL, download URL |
| `tools/ejamin/lib/pdf.mjs` | Locate and inflate PDF streams; find dictionary objects by number |
| `tools/ejamin/lib/content.mjs` | Tokenise a content stream → closed subpaths + placed text runs |
| `tools/ejamin/lib/geo.mjs` | `/Viewport` `/Measure` `GPTS`+`LPTS` → page↔lat/long affine |
| `tools/ejamin/lib/geom.mjs` | bbox, point-in-polygon, shared-edge adjacency |
| `tools/ejamin/lib/labels.mjs` | Classify a text run: survey number vs feature vs chrome |
| `tools/ejamin/scrape-catalog.mjs` | Executable → `tools/ejamin/out/catalog.json` |
| `tools/ejamin/build-index.mjs` | Executable → `out/indexes/village-<id>.index.json` + `manifest.json` |
| `tools/ejamin/qa-index.mjs` | Executable → demotes weak indexes to `LINK_ONLY` |
| `tools/ejamin/test/*.test.mjs` | `node --test` suites |
| `tools/ejamin/test/fixtures/` | Saved homepage snapshot + two real PDFs + one broken PDF |

Run all tests with `node --test tools/ejamin/test/`.

---

### Task 1: Session — token, cookie, throttled POST

**Files:**
- Create: `tools/ejamin/lib/session.mjs`
- Create: `tools/ejamin/test/session.test.mjs`
- Create: `tools/ejamin/test/fixtures/homepage.html` (saved snapshot)

**Interfaces:**
- Consumes: nothing.
- Produces: `extractToken(html) -> string`, `extractTpMapData(html) -> object`, `class Session { static async open(): Promise<Session>; async post(type, id): Promise<any>; }`

- [ ] **Step 1: Save the fixture**

```bash
mkdir -p tools/ejamin/test/fixtures tools/ejamin/out
curl -s -m 30 https://ejamingujarat.com/ -o tools/ejamin/test/fixtures/homepage.html
```

Confirm it contains both markers:

```bash
grep -c '_token", "' tools/ejamin/test/fixtures/homepage.html
grep -c 'let tpMapData' tools/ejamin/test/fixtures/homepage.html
```

Expected: both print `1` or more.

- [ ] **Step 2: Write the failing test**

Create `tools/ejamin/test/session.test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { extractToken, extractTpMapData } from '../lib/session.mjs';

const html = readFileSync(new URL('./fixtures/homepage.html', import.meta.url), 'utf8');

test('extractToken pulls the 40-char CSRF token', () => {
  const tok = extractToken(html);
  assert.match(tok, /^[A-Za-z0-9]{40}$/);
});

test('extractTpMapData returns TP schemes keyed by district id', () => {
  const data = extractTpMapData(html);
  const keys = Object.keys(data);
  assert.ok(keys.length > 0, 'expected at least one district key');
  const first = data[keys[0]][0];
  assert.ok(typeof first.tp_title === 'string');
  assert.match(first.link, /^https:\/\/drive\.google\.com\//);
});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `node --test tools/ejamin/test/session.test.mjs`
Expected: FAIL — `Cannot find module '../lib/session.mjs'`.

- [ ] **Step 4: Implement `session.mjs`**

```js
// One polite eJamin session. The site is a Laravel app: every map lookup is a POST to
// /villageMapGet carrying the page's CSRF token plus the session cookie, and it only answers
// when the request looks like the site's own jQuery ($.ajax sets X-Requested-With).
const HOME = 'https://ejamingujarat.com/';
const ENDPOINT = 'https://ejamingujarat.com/villageMapGet';
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36';

/** The CSRF token the page bakes into its own $.ajax calls. */
export function extractToken(html) {
  const m = html.match(/_token["']?\s*[:,]\s*["']([A-Za-z0-9]{40})["']/);
  if (!m) throw new Error('eJamin: CSRF token not found in homepage');
  return m[1];
}

/** TP-map rows for every district, embedded in the page as `let tpMapData = {...};`. */
export function extractTpMapData(html) {
  const i = html.indexOf('let tpMapData');
  if (i < 0) throw new Error('eJamin: tpMapData literal not found');
  const start = html.indexOf('{', i);
  // Brace-match rather than regex — the literal contains braces inside strings is not a risk here
  // (Laravel json_encode escapes nothing brace-like), but it spans ~500 KB and is not line-bounded.
  let depth = 0;
  for (let j = start; j < html.length; j++) {
    if (html[j] === '{') depth++;
    else if (html[j] === '}' && --depth === 0) return JSON.parse(html.slice(start, j + 1));
  }
  throw new Error('eJamin: tpMapData literal is unterminated');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class Session {
  constructor(token, cookie, html) {
    this.token = token;
    this.cookie = cookie;
    this.html = html;
    this.last = 0;
  }

  static async open() {
    const res = await fetch(HOME, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`eJamin: homepage HTTP ${res.status}`);
    const html = await res.text();
    const cookie = (res.headers.getSetCookie?.() ?? [])
      .map((c) => c.split(';')[0]).join('; ');
    return new Session(extractToken(html), cookie, html);
  }

  /** One throttled villageMapGet. Serial by construction — callers await each hop. */
  async post(type, id) {
    const wait = 400 - (Date.now() - this.last);
    if (wait > 0) await sleep(wait);
    this.last = Date.now();

    const body = new FormData();
    body.append('_token', this.token);
    body.append('id', String(id));
    body.append('type', type);

    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'User-Agent': UA, 'X-Requested-With': 'XMLHttpRequest', Referer: HOME, Cookie: this.cookie },
      body,
    });
    if (!res.ok) throw new Error(`eJamin: ${type}/${id} HTTP ${res.status}`);
    const json = await res.json();
    return json.status === 1 ? json.data : null;
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `node --test tools/ejamin/test/session.test.mjs`
Expected: PASS, 2 tests.

- [ ] **Step 6: Smoke-test the live session once**

```bash
node -e "import('./tools/ejamin/lib/session.mjs').then(async ({Session}) => {
  const s = await Session.open();
  console.log(JSON.stringify(await s.post('district', 3)).slice(0, 200));
})"
```

Expected: the Anand taluka list beginning `[{"id":29,"name":"Anand"}`.

- [ ] **Step 7: Commit**

```bash
git add tools/ejamin/lib/session.mjs tools/ejamin/test/session.test.mjs tools/ejamin/test/fixtures/homepage.html
git commit -m "feat(ejamin): polite session with CSRF token and tpMapData extraction"
```

---

### Task 2: Drive link normalisation

**Files:**
- Create: `tools/ejamin/lib/drive.mjs`
- Create: `tools/ejamin/test/drive.test.mjs`

**Interfaces:**
- Consumes: nothing.
- Produces: `driveUrls(link) -> { driveFileId, viewUrl, downloadUrl } | null`

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { driveUrls } from '../lib/drive.mjs';

test('parses an escaped file/d/<id>/view link', () => {
  const out = driveUrls('https:\\/\\/drive.google.com\\/file\\/d\\/1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB\\/view');
  assert.equal(out.driveFileId, '1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB');
  assert.equal(out.viewUrl, 'https://drive.google.com/file/d/1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB/view');
  assert.equal(out.downloadUrl, 'https://drive.google.com/uc?export=download&id=1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB');
});

test('parses an open?id= link', () => {
  assert.equal(driveUrls('https://drive.google.com/open?id=ABC123').driveFileId, 'ABC123');
});

test('returns null for a missing or non-Drive link', () => {
  assert.equal(driveUrls(null), null);
  assert.equal(driveUrls(''), null);
  assert.equal(driveUrls('https://example.com/x.pdf'), null);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/drive.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `drive.mjs`**

```js
// eJamin stores Drive links JSON-escaped ("https:\/\/drive.google.com\/..."), and the site sometimes
// carries the older open?id= form. Both reduce to a file id, which is all we need to build a
// no-auth download URL (verified: uc?export=download returns the PDF bytes directly).
export function driveUrls(link) {
  if (!link) return null;
  const clean = String(link).replace(/\\\//g, '/').trim();
  const m = clean.match(/\/file\/d\/([A-Za-z0-9_-]+)/) || clean.match(/[?&]id=([A-Za-z0-9_-]+)/);
  if (!m) return null;
  const id = m[1];
  return {
    driveFileId: id,
    viewUrl: `https://drive.google.com/file/d/${id}/view`,
    downloadUrl: `https://drive.google.com/uc?export=download&id=${id}`,
  };
}
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/drive.test.mjs`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add tools/ejamin/lib/drive.mjs tools/ejamin/test/drive.test.mjs
git commit -m "feat(ejamin): normalise escaped Drive links to file id + download URL"
```

---

### Task 3: Catalogue scraper

**Files:**
- Create: `tools/ejamin/scrape-catalog.mjs`
- Create: `tools/ejamin/test/catalog.test.mjs`
- Modify: `.gitignore` (ignore `tools/ejamin/out/pdf-cache/`)

**Interfaces:**
- Consumes: `Session`, `extractTpMapData` (Task 1); `driveUrls` (Task 2).
- Produces: `tools/ejamin/out/catalog.json` with shape
  `{ generatedAt, sheets: [{ type, districtId, districtName, talukaId, talukaName, villageId, villageName, driveFileId, viewUrl, downloadUrl }] }`.
  `talukaId`/`talukaName`/`villageId`/`villageName` are `null` for types that resolve earlier.
  Also exports `districtOptions(html, selectClass) -> [{id, name}]` for the test.

- [ ] **Step 1: Write the failing test**

The test only covers the pure parsing helper — the network walk is exercised by the live run in Step 5.

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { districtOptions } from '../scrape-catalog.mjs';

const html = readFileSync(new URL('./fixtures/homepage.html', import.meta.url), 'utf8');

test('village-map and DP tabs have SEPARATE district id spaces', () => {
  const village = districtOptions(html, 'districtData');
  const dp = districtOptions(html, 'districtDpData');
  const kv = village.find((d) => /^kheda$/i.test(d.name));
  const kd = dp.find((d) => /^kheda$/i.test(d.name));
  assert.ok(kv && kd, 'Kheda must appear in both tabs');
  assert.notEqual(kv.id, kd.id, 'ids MUST NOT be shared across map types');
});

test('districtOptions skips the placeholder option', () => {
  assert.ok(districtOptions(html, 'districtData').every((d) => d.id && d.name !== 'Select District'));
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/catalog.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `scrape-catalog.mjs`**

```js
// Catalogues every map sheet eJamin publishes: all seven types, all districts.
// Cheap and politely serial; run it again whenever eJamin adds sheets.
//
// CRITICAL: each tab has its OWN district id space (Kheda is 14 in one tab and 18 in another).
// Ids are therefore always read from that tab's own <select> and stored per type.
import { writeFileSync, mkdirSync } from 'node:fs';
import { Session, extractTpMapData } from './lib/session.mjs';
import { driveUrls } from './lib/drive.mjs';

// Each type names the CSS class of its district <select> and the villageMapGet `type` values
// used to descend. `leaf` is the hop that returns the sheet row(s) instead of another list.
const TYPES = [
  { type: 'VILLAGE_MAP', selectClass: 'districtData', hops: ['district', 'taluka'], leaf: 'village' },
  { type: 'TP_MAP', selectClass: 'tpDistrictData', hops: [], leaf: 'tpTitle', fromTpMapData: true },
  { type: 'GDCR', selectClass: 'districtGdcrData', hops: [], leaf: 'districtGdcr' },
  { type: 'DP', selectClass: 'districtDpData', hops: ['districtDp'], leaf: 'talukaDp' },
];

/** The <option>s of one tab's district <select>, minus the placeholder. */
export function districtOptions(html, selectClass) {
  const sel = new RegExp(`<select[^>]*class="[^"]*${selectClass}[^"]*"[\\s\\S]*?</select>`, 'i');
  const block = html.match(sel);
  if (!block) return [];
  return [...block[0].matchAll(/<option value="(\d+)">([^<]+)<\/option>/g)]
    .map((m) => ({ id: Number(m[1]), name: m[2].trim() }))
    .filter((d) => d.name && !/^select /i.test(d.name));
}

function sheet(type, d, t, v, link) {
  const urls = driveUrls(link);
  if (!urls) return null; // no link = no sheet; never fabricate one
  return {
    type,
    districtId: d?.id ?? null, districtName: d?.name ?? null,
    talukaId: t?.id ?? null, talukaName: t?.name ?? null,
    villageId: v?.id ?? null, villageName: v?.name ?? null,
    ...urls,
  };
}

async function main() {
  const s = await Session.open();
  const sheets = [];

  // TP maps come free: the homepage embeds every district's schemes.
  const tp = extractTpMapData(s.html);
  const tpDistricts = districtOptions(s.html, 'tpDistrictData');
  for (const [districtId, rows] of Object.entries(tp)) {
    const d = tpDistricts.find((x) => x.id === Number(districtId)) ?? { id: Number(districtId), name: null };
    for (const r of rows) {
      const row = sheet('TP_MAP', d, null, { id: r.id, name: r.tp_title }, r.link);
      if (row) sheets.push(row);
      const g = sheet('GDSR', d, null, { id: r.id, name: r.tp_title }, r.gdsr_link);
      if (g) sheets.push(g);
      const f = sheet('F_FORM', d, null, { id: r.id, name: r.tp_title }, r.f_form_link);
      if (f) sheets.push(f);
    }
  }

  // Village maps: district -> taluka -> village -> sheet row.
  for (const d of districtOptions(s.html, 'districtData')) {
    const talukas = (await s.post('district', d.id)) ?? [];
    console.log(`VILLAGE_MAP ${d.name}: ${talukas.length} talukas`);
    for (const t of talukas) {
      const villages = (await s.post('taluka', t.id)) ?? [];
      for (const v of villages) {
        const row = await s.post('village', v.id);
        const out = sheet('VILLAGE_MAP', d, t, v, row?.link);
        if (out) sheets.push(out);
      }
    }
  }

  // GDCR resolves at district level and returns an array of named links.
  for (const d of districtOptions(s.html, 'districtGdcrData')) {
    const rows = (await s.post('districtGdcr', d.id)) ?? [];
    for (const r of Array.isArray(rows) ? rows : [rows]) {
      const out = sheet('GDCR', d, null, { id: r.id, name: r.name }, r.link);
      if (out) sheets.push(out);
    }
  }

  // DP resolves at taluka level.
  for (const d of districtOptions(s.html, 'districtDpData')) {
    const talukas = (await s.post('districtDp', d.id)) ?? [];
    for (const t of talukas) {
      const rows = (await s.post('talukaDp', t.id)) ?? [];
      for (const r of Array.isArray(rows) ? rows : [rows]) {
        const out = sheet('DP', d, t, { id: r.id, name: r.name }, r.link);
        if (out) sheets.push(out);
      }
    }
  }

  mkdirSync('tools/ejamin/out', { recursive: true });
  const catalog = { generatedAt: new Date().toISOString().slice(0, 10), sheets };
  writeFileSync('tools/ejamin/out/catalog.json', JSON.stringify(catalog, null, 1));
  console.log(`catalog: ${sheets.length} sheets`);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();
```

If a tab's `selectClass` in `TYPES` does not match the saved homepage, read the fixture and correct the class name — do not guess. `districtOptions` returning `[]` for a tab is the signal.

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/catalog.test.mjs`
Expected: PASS, 2 tests. The first test is the guard against the per-type id-space bug.

- [ ] **Step 5: Run the live scrape**

Run: `node tools/ejamin/scrape-catalog.mjs`
Expected: progress lines per district, then `catalog: <N> sheets` with N in the thousands. It takes a while — serial by design.

Sanity-check Kheda and Anand village coverage:

```bash
node -e "const c=require('./tools/ejamin/out/catalog.json');
for (const d of ['Kheda','Anand'])
  console.log(d, c.sheets.filter(s=>s.type==='VILLAGE_MAP'&&s.districtName===d).length);"
```

Expected: a non-zero count for each. If either is zero, stop and fix before continuing — Plan 1's whole point is those two districts.

- [ ] **Step 6: Ignore the PDF cache**

Append to `.gitignore`:

```
tools/ejamin/out/pdf-cache/
```

- [ ] **Step 7: Commit**

```bash
git add tools/ejamin/scrape-catalog.mjs tools/ejamin/test/catalog.test.mjs tools/ejamin/out/catalog.json .gitignore
git commit -m "feat(ejamin): catalogue every map sheet, keyed per map type"
```

---

### Task 4: PDF object + stream access

**Files:**
- Create: `tools/ejamin/lib/pdf.mjs`
- Create: `tools/ejamin/test/pdf.test.mjs`
- Create: `tools/ejamin/test/fixtures/badarkha.pdf`

**Interfaces:**
- Consumes: nothing.
- Produces: `inflateStreams(buf) -> string[]` (page-content candidates, largest first), `findDicts(buf, name) -> string[]` (raw dictionary text of every object containing `/name`), `pageSize(buf) -> [w, h]`

- [ ] **Step 1: Save the fixture**

```bash
curl -sL -m 60 "https://drive.google.com/uc?export=download&id=1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB" \
  -o tools/ejamin/test/fixtures/badarkha.pdf
pdfinfo tools/ejamin/test/fixtures/badarkha.pdf | head -3
```

Expected: `Creator: Esri ArcMap 10.4.0.5524`, `Pages: 1`.

- [ ] **Step 2: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { inflateStreams, pageSize, findDicts } from '../lib/pdf.mjs';

const buf = readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url));

test('inflateStreams returns the page content, largest first', () => {
  const streams = inflateStreams(buf);
  assert.ok(streams.length >= 1);
  assert.ok(streams[0].length > 100000, 'biggest stream should be the A0 page content');
  assert.ok(streams[0].includes('Tj'), 'page content must contain text operators');
});

test('pageSize reads the A0 MediaBox', () => {
  const [w, h] = pageSize(buf);
  assert.ok(Math.abs(w - 3370.51) < 1, `width ${w}`);
  assert.ok(Math.abs(h - 2384.25) < 1, `height ${h}`);
});

test('findDicts locates the georeferencing viewports', () => {
  assert.ok(findDicts(buf, 'Viewport').length >= 1);
});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `node --test tools/ejamin/test/pdf.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 4: Implement `pdf.mjs`**

```js
// Minimal PDF reader — just enough for ArcMap-exported map sheets, with no dependencies.
// These files are simple: one page, Flate-compressed streams, no encryption, no object streams.
// We scan for streams rather than walking the xref, because that is robust to the slightly
// non-conformant output some ArcMap versions produce.
import { inflateSync } from 'node:zlib';

/** Every Flate stream we can inflate, as latin1 text, biggest first. */
export function inflateStreams(buf) {
  const out = [];
  const hay = buf.toString('latin1');
  const re = /stream\r?\n/g;
  let m;
  while ((m = re.exec(hay)) !== null) {
    const start = m.index + m[0].length;
    const end = hay.indexOf('endstream', start);
    if (end < 0) continue;
    try {
      out.push(inflateSync(buf.subarray(start, end)).toString('latin1'));
    } catch {
      // Not Flate (an embedded image, or a raw stream) — content streams always are.
    }
  }
  return out.sort((a, b) => b.length - a.length);
}

/** [width, height] in points from the first /MediaBox. */
export function pageSize(buf) {
  const m = buf.toString('latin1').match(/\/MediaBox\s*\[\s*([\d.-]+)\s+([\d.-]+)\s+([\d.-]+)\s+([\d.-]+)\s*\]/);
  if (!m) throw new Error('pdf: no /MediaBox');
  return [Math.abs(+m[3] - +m[1]), Math.abs(+m[4] - +m[2])];
}

/** Raw text of each region introduced by /<name>, up to a bounded window — enough for /Viewport. */
export function findDicts(buf, name) {
  const hay = buf.toString('latin1');
  const out = [];
  let i = 0;
  while ((i = hay.indexOf(`/${name}`, i)) >= 0) {
    out.push(hay.slice(i, i + 2000));
    i += name.length + 1;
  }
  return out;
}
```

- [ ] **Step 5: Run the tests**

Run: `node --test tools/ejamin/test/pdf.test.mjs`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add tools/ejamin/lib/pdf.mjs tools/ejamin/test/pdf.test.mjs tools/ejamin/test/fixtures/badarkha.pdf
git commit -m "feat(ejamin): dependency-free PDF stream and dictionary access"
```

---

### Task 5: Content-stream tokeniser — polygons and placed text

**Files:**
- Create: `tools/ejamin/lib/content.mjs`
- Create: `tools/ejamin/test/content.test.mjs`

**Interfaces:**
- Consumes: `inflateStreams` (Task 4).
- Produces:
  - `parsePaths(stream) -> [[ [x,y], ... ], ...]` — closed subpaths in page space
  - `parseTexts(stream) -> [{ text, x, y }]` — text runs at their placed origin

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { inflateStreams } from '../lib/pdf.mjs';
import { parsePaths, parseTexts } from '../lib/content.mjs';

const page = inflateStreams(readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url)))[0];

test('parsePaths finds closed parcel polygons', () => {
  const polys = parsePaths(page);
  assert.ok(polys.length > 50, `expected many parcels, got ${polys.length}`);
  assert.ok(polys.every((p) => p.length >= 3), 'every polygon needs 3+ points');
});

test('parsePaths handles a hand-built subpath with CRLF and h close', () => {
  const polys = parsePaths('q\r\n0 0 m\r\n10 0 l\r\n10 10 l\r\nh\r\nW* n\r\n');
  assert.deepEqual(polys[0], [[0, 0], [10, 0], [10, 10]]);
});

test('parseTexts places survey numbers at real coordinates', () => {
  const texts = parseTexts(page);
  const hit = texts.find((t) => t.text.trim() === '221' || t.text.trim() === '74/P');
  assert.ok(hit, 'expected a known survey label');
  assert.ok(hit.x > 0 && hit.y > 0, 'label must carry a placed origin');
});

test('parseTexts decodes a simple Tm + Tj pair', () => {
  const out = parseTexts('BT\r\n1 0 0 1 100 200 Tm\r\n(42) Tj\r\nET\r\n');
  assert.deepEqual(out, [{ text: '42', x: 100, y: 200 }]);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/content.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `content.mjs`**

```js
// Tokenises an ArcMap page content stream into the only two things the app needs:
// parcel outlines (closed subpaths) and placed text runs (survey numbers, road names).
//
// This is deliberately NOT a general PDF interpreter. ArcMap emits axis-plain geometry:
// `m`/`l` for parcel boundaries, `h` to close, and text as `Tm`-positioned `Tj` runs.
// Curves (`c`, `v`, `y`) are flattened to their endpoint — parcel edges are straight in
// cadastral sheets, and the few curved decorations do not need sub-point fidelity.

const NUM = '-?\\d*\\.?\\d+';

/** Closed subpaths, in page space. */
export function parsePaths(stream) {
  const polys = [];
  let cur = null;
  const re = new RegExp(
    `(${NUM})\\s+(${NUM})\\s+(m|l)\\b` +
    `|(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+c\\b` +
    `|\\bh\\b`,
    'g',
  );
  let m;
  while ((m = re.exec(stream)) !== null) {
    if (m[3] === 'm') {
      if (cur && cur.length >= 3) polys.push(cur);
      cur = [[+m[1], +m[2]]];
    } else if (m[3] === 'l') {
      cur?.push([+m[1], +m[2]]);
    } else if (m[9] !== undefined) {
      cur?.push([+m[9], +m[10]]); // curve endpoint only
    } else {
      if (cur && cur.length >= 3) polys.push(cur);
      cur = null;
    }
  }
  if (cur && cur.length >= 3) polys.push(cur);
  return polys;
}

/** PDF string literal → text, honouring the escapes ArcMap actually emits. */
function decode(lit) {
  return lit.replace(/\\([nrtbf()\\])/g, (_, c) =>
    ({ n: '\n', r: '\r', t: '\t', b: '\b', f: '\f' }[c] ?? c));
}

/** Text runs with the origin from the current text matrix. */
export function parseTexts(stream) {
  const out = [];
  let x = 0, y = 0;
  const re = new RegExp(
    `(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+Tm\\b` +
    `|(${NUM})\\s+(${NUM})\\s+Td\\b` +
    `|\\(((?:[^()\\\\]|\\\\.)*)\\)\\s*Tj\\b`,
    'g',
  );
  let m;
  while ((m = re.exec(stream)) !== null) {
    if (m[5] !== undefined) {
      x = +m[5]; y = +m[6];
    } else if (m[7] !== undefined) {
      x += +m[7]; y += +m[8];
    } else if (m[9] !== undefined) {
      const text = decode(m[9]);
      if (text.trim()) out.push({ text, x, y });
    }
  }
  return out;
}
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/content.test.mjs`
Expected: PASS, 4 tests.

If the real-PDF test finds no `221`/`74/P`, print the first 40 labels and pick two that genuinely appear on this sheet — the fixture's numbers are the authority, not this plan:

```bash
node -e "import('./tools/ejamin/lib/pdf.mjs').then(async (p)=>{const {parseTexts}=await import('./tools/ejamin/lib/content.mjs');
const fs=require('fs');const s=p.inflateStreams(fs.readFileSync('tools/ejamin/test/fixtures/badarkha.pdf'))[0];
console.log(parseTexts(s).slice(0,40));})"
```

- [ ] **Step 5: Commit**

```bash
git add tools/ejamin/lib/content.mjs tools/ejamin/test/content.test.mjs
git commit -m "feat(ejamin): extract parcel polygons and placed text from page content"
```

---

### Task 6: Geometry — bbox, point-in-polygon, shared-edge adjacency

**Files:**
- Create: `tools/ejamin/lib/geom.mjs`
- Create: `tools/ejamin/test/geom.test.mjs`

**Interfaces:**
- Consumes: nothing.
- Produces: `bbox(poly) -> [minX, minY, maxX, maxY]`, `area(poly) -> number`, `contains(poly, [x,y]) -> boolean`, `adjacency(polys, tol=1.5) -> number[][]` (index → neighbour indices)

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bbox, area, contains, adjacency } from '../lib/geom.mjs';

const sq = (x, y, s = 10) => [[x, y], [x + s, y], [x + s, y + s], [x, y + s]];

test('bbox and area of a unit-ish square', () => {
  assert.deepEqual(bbox(sq(0, 0)), [0, 0, 10, 10]);
  assert.equal(area(sq(0, 0)), 100);
});

test('contains handles inside, outside and a concave notch', () => {
  assert.equal(contains(sq(0, 0), [5, 5]), true);
  assert.equal(contains(sq(0, 0), [15, 5]), false);
  const L = [[0, 0], [10, 0], [10, 4], [4, 4], [4, 10], [0, 10]];
  assert.equal(contains(L, [2, 8]), true);
  assert.equal(contains(L, [8, 8]), false);
});

test('adjacency links only parcels sharing an edge', () => {
  // 0 and 1 share the x=10 edge; 2 sits diagonally, touching only at a corner.
  const polys = [sq(0, 0), sq(10, 0), sq(30, 30)];
  const adj = adjacency(polys);
  assert.deepEqual(adj[0], [1]);
  assert.deepEqual(adj[1], [0]);
  assert.deepEqual(adj[2], []);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/geom.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `geom.mjs`**

```js
// Plane geometry on page-space polygons. Kept free of PDF and of Android concerns so both the
// pipeline and (ported) the app can rely on identical semantics.

export function bbox(poly) {
  let a = Infinity, b = Infinity, c = -Infinity, d = -Infinity;
  for (const [x, y] of poly) {
    if (x < a) a = x; if (y < b) b = y;
    if (x > c) c = x; if (y > d) d = y;
  }
  return [a, b, c, d];
}

/** Absolute shoelace area. */
export function area(poly) {
  let s = 0;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    s += poly[j][0] * poly[i][1] - poly[i][0] * poly[j][1];
  }
  return Math.abs(s) / 2;
}

/** Ray casting. Points exactly on an edge are treated as inside by the half-open test. */
export function contains(poly, [x, y]) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i], [xj, yj] = poly[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/**
 * Neighbours = parcels sharing a boundary, not merely parcels drawn near each other.
 * Two parcels are adjacent when at least two of their vertices coincide within [tol] points —
 * i.e. they share a whole edge. A single coincident vertex is a corner touch, which is NOT
 * an adjoining property, so the threshold is two.
 */
export function adjacency(polys, tol = 1.5) {
  const boxes = polys.map(bbox);
  const out = polys.map(() => []);
  const key = (p) => `${Math.round(p[0] / tol)}:${Math.round(p[1] / tol)}`;
  const sets = polys.map((p) => new Set(p.map(key)));

  for (let i = 0; i < polys.length; i++) {
    for (let j = i + 1; j < polys.length; j++) {
      const [ax0, ay0, ax1, ay1] = boxes[i], [bx0, by0, bx1, by1] = boxes[j];
      if (ax1 + tol < bx0 || bx1 + tol < ax0 || ay1 + tol < by0 || by1 + tol < ay0) continue;
      let shared = 0;
      for (const k of sets[i]) if (sets[j].has(k) && ++shared === 2) break;
      if (shared >= 2) { out[i].push(j); out[j].push(i); }
    }
  }
  return out;
}
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/geom.test.mjs`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add tools/ejamin/lib/geom.mjs tools/ejamin/test/geom.test.mjs
git commit -m "feat(ejamin): shared-edge adjacency and point-in-polygon geometry"
```

---

### Task 7: Georeferencing

**Files:**
- Create: `tools/ejamin/lib/geo.mjs`
- Create: `tools/ejamin/test/geo.test.mjs`

**Interfaces:**
- Consumes: `findDicts` (Task 4).
- Produces: `parseGeo(buf) -> { matrix: [a,b,c,d,e,f], crs: 'EPSG:4326' } | null`, `pageToLatLng(matrix, [x,y]) -> [lat, lng]`

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parseGeo, pageToLatLng } from '../lib/geo.mjs';

const buf = readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url));

test('parseGeo reads the ArcMap viewport registration', () => {
  const geo = parseGeo(buf);
  assert.ok(geo, 'Badarkha is a GeoPDF; a transform must be found');
  assert.equal(geo.matrix.length, 6);
  assert.equal(geo.crs, 'EPSG:4326');
});

test('a page point maps into Gujarat', () => {
  const geo = parseGeo(buf);
  const [lat, lng] = pageToLatLng(geo.matrix, [1685, 1192]); // page centre
  assert.ok(lat > 20 && lat < 25, `lat ${lat} should be inside Gujarat`);
  assert.ok(lng > 68 && lng < 75, `lng ${lng} should be inside Gujarat`);
});

test('pageToLatLng is a plain affine application', () => {
  assert.deepEqual(pageToLatLng([1, 0, 0, 1, 0, 0], [3, 4]), [4, 3]);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/geo.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `geo.mjs`**

```js
// ArcMap writes an OGC "geospatial PDF" registration: each /Viewport carries a /Measure dict whose
// /GPTS are geographic points (lat, lng pairs) and whose /LPTS are the matching points in the
// viewport's own unit square. Four correspondences over-determine an affine, so we least-squares
// three of them — which is exact for the axis-aligned north-up sheets ArcMap produces.
import { findDicts } from './pdf.mjs';

const nums = (s) => [...s.matchAll(/-?\d+\.?\d*/g)].map(Number);

/** Solve a 3x3 system by Gaussian elimination; returns null if singular. */
function solve3(A, b) {
  const M = A.map((row, i) => [...row, b[i]]);
  for (let c = 0; c < 3; c++) {
    let p = c;
    for (let r = c + 1; r < 3; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
    if (Math.abs(M[p][c]) < 1e-12) return null;
    [M[c], M[p]] = [M[p], M[c]];
    for (let r = 0; r < 3; r++) {
      if (r === c) continue;
      const f = M[r][c] / M[c][c];
      for (let k = c; k < 4; k++) M[r][k] -= f * M[c][k];
    }
  }
  return [M[0][3] / M[0][0], M[1][3] / M[1][1], M[2][3] / M[2][2]];
}

/**
 * The page→(lat,lng) affine as [a,b,c, d,e,f] where
 *   lat = a*x + b*y + c
 *   lng = d*x + e*y + f
 * Returns null when the sheet carries no usable registration — the caller must then demote the
 * village to LINK_ONLY rather than invent coordinates.
 */
export function parseGeo(buf) {
  for (const dict of findDicts(buf, 'Viewport')) {
    const bboxM = dict.match(/\/BBox\s*\[([^\]]+)\]/);
    const gptsM = dict.match(/\/GPTS\s*\[([^\]]+)\]/);
    const lptsM = dict.match(/\/LPTS\s*\[([^\]]+)\]/);
    if (!bboxM || !gptsM || !lptsM) continue;

    const [bx0, by0, bx1, by1] = nums(bboxM[1]);
    const g = nums(gptsM[1]); // lat, lng, lat, lng, ...
    const l = nums(lptsM[1]); // u, v, u, v, ... in the viewport unit square
    const n = Math.min(g.length / 2, l.length / 2);
    if (n < 3) continue;

    // Unit-square point -> absolute page point.
    const pts = [];
    for (let i = 0; i < n; i++) {
      pts.push({
        x: Math.min(bx0, bx1) + l[i * 2] * Math.abs(bx1 - bx0),
        y: Math.min(by0, by1) + l[i * 2 + 1] * Math.abs(by1 - by0),
        lat: g[i * 2],
        lng: g[i * 2 + 1],
      });
    }
    const A = pts.slice(0, 3).map((p) => [p.x, p.y, 1]);
    const lat = solve3(A, pts.slice(0, 3).map((p) => p.lat));
    const lng = solve3(A, pts.slice(0, 3).map((p) => p.lng));
    if (!lat || !lng) continue;
    return { matrix: [...lat, ...lng], crs: 'EPSG:4326' };
  }
  return null;
}

export function pageToLatLng([a, b, c, d, e, f], [x, y]) {
  return [a * x + b * y + c, d * x + e * y + f];
}
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/geo.test.mjs`
Expected: PASS, 3 tests.

If the Gujarat bounds test fails, print the raw `/GPTS` and `/LPTS` and check the pair order — some ArcMap builds emit lng-then-lat. Fix the ordering in `parseGeo` and note it in a comment; do not loosen the bounds assertion, which is the only thing proving the transform is real.

- [ ] **Step 5: Commit**

```bash
git add tools/ejamin/lib/geo.mjs tools/ejamin/test/geo.test.mjs
git commit -m "feat(ejamin): derive page-to-lat/long affine from GeoPDF viewports"
```

---

### Task 8: Label classification

**Files:**
- Create: `tools/ejamin/lib/labels.mjs`
- Create: `tools/ejamin/test/labels.test.mjs`

**Interfaces:**
- Consumes: nothing.
- Produces: `classify(text) -> { kind: 'SURVEY'|'ROAD'|'PLACE'|'NOTE'|'CHROME', value: string }`, `normaliseSurvey(text) -> string`

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classify, normaliseSurvey } from '../lib/labels.mjs';

test('plain and part survey numbers', () => {
  assert.deepEqual(classify('221'), { kind: 'SURVEY', value: '221' });
  assert.deepEqual(classify('74/P'), { kind: 'SURVEY', value: '74/P' });
  assert.deepEqual(classify(' 74 / p '), { kind: 'SURVEY', value: '74/P' });
});

test('Gujarati numerals normalise to Latin for matching', () => {
  assert.equal(normaliseSurvey('૨૨૧'), '221');
  assert.deepEqual(classify('૭૪/પ'), { kind: 'SURVEY', value: '74/P' });
});

test('features and chrome are not survey numbers', () => {
  assert.equal(classify('Road').kind, 'ROAD');
  assert.equal(classify('Kavitha').kind, 'PLACE');
  assert.equal(classify('Not Promulgated').kind, 'NOTE');
  assert.equal(classify('District:- Ahmedabad').kind, 'CHROME');
  assert.equal(classify('Taluka').kind, 'CHROME');
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/labels.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `labels.mjs`**

```js
// Sorts the sheet's text runs into what the app can act on. Anything not confidently a survey
// number stays out of the searchable set — a wrong match is worse than a miss, because the user
// would be told a plot is somewhere it is not.

const GU_DIGITS = '૦૧૨૩૪૫૬૭૮૯';

/** Gujarati digits → Latin, and the Gujarati part-marker 'પ' → 'P'. Everything else is preserved. */
export function normaliseSurvey(text) {
  return [...String(text)]
    .map((ch) => {
      const i = GU_DIGITS.indexOf(ch);
      if (i >= 0) return String(i);
      if (ch === 'પ') return 'P';
      return ch;
    })
    .join('')
    .replace(/\s+/g, '')
    .toUpperCase();
}

const CHROME = /^(district|taluka|village|scale|index|legend|north|prepared|survey of india|:-)/i;
const ROAD = /\b(road|rasta|marg|highway|canal|kans|nala|river|railway)\b/i;
const NOTE = /\b(not promulgated|promulgated|gamtal|reserved)\b/i;

export function classify(text) {
  const raw = String(text).trim();
  if (!raw) return { kind: 'CHROME', value: raw };
  if (CHROME.test(raw) || raw.includes(':-')) return { kind: 'CHROME', value: raw };
  if (NOTE.test(raw)) return { kind: 'NOTE', value: raw };
  if (ROAD.test(raw)) return { kind: 'ROAD', value: raw };

  const n = normaliseSurvey(raw);
  // A survey number is digits, optionally followed by a part suffix (/P, /A, /1, /P1).
  if (/^\d{1,4}(\/[A-Z0-9]{1,3})?$/.test(n)) return { kind: 'SURVEY', value: n };

  // Anything else that is purely letters is a neighbouring village or hamlet name.
  if (/^[A-Za-z઀-૿][A-Za-z઀-૿\s_.-]*$/.test(raw)) return { kind: 'PLACE', value: raw };
  return { kind: 'NOTE', value: raw };
}
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/labels.test.mjs`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add tools/ejamin/lib/labels.mjs tools/ejamin/test/labels.test.mjs
git commit -m "feat(ejamin): classify sheet labels; normalise Gujarati survey numbers"
```

---

### Task 9: Index builder

**Files:**
- Create: `tools/ejamin/build-index.mjs`
- Create: `tools/ejamin/test/build-index.test.mjs`

**Interfaces:**
- Consumes: everything from Tasks 2 and 4–8; `catalog.json` from Task 3.
- Produces: `buildIndex(buf, meta) -> indexObject` (pure, testable), and as an executable
  `tools/ejamin/out/indexes/village-<id>.index.json` + `out/indexes/manifest.json`.

  Index shape (the contract Plan 2 consumes):
  ```json
  {
    "villageId": 811, "villageName": "Adas",
    "districtName": "Anand", "talukaName": "Anand",
    "pageSize": [3370.51, 2384.25],
    "geo": { "matrix": [1,0,0,0,1,0], "crs": "EPSG:4326" },
    "parcels": [{ "id": 0, "surveyNo": "221/P", "poly": [[x,y]], "adj": [1,2] }],
    "features": [{ "kind": "ROAD", "label": "Kans Road", "x": 100, "y": 200 }],
    "quality": "GOOD"
  }
  ```
  `surveyNo` is `null` when no label fell inside that parcel. `geo` is `null` when unregistered.

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildIndex } from '../build-index.mjs';

const buf = readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url));
const idx = buildIndex(buf, { villageId: 1, villageName: 'Badarkha', districtName: 'Ahmedabad', talukaName: 'Dholka' });

test('index carries parcels, most of them labelled', () => {
  assert.ok(idx.parcels.length > 50, `parcels ${idx.parcels.length}`);
  const labelled = idx.parcels.filter((p) => p.surveyNo).length;
  assert.ok(labelled / idx.parcels.length > 0.5, `only ${labelled}/${idx.parcels.length} labelled`);
});

test('survey numbers are unique and normalised', () => {
  const nos = idx.parcels.map((p) => p.surveyNo).filter(Boolean);
  assert.equal(new Set(nos).size, nos.length, 'a survey number must not label two parcels');
  assert.ok(nos.every((n) => /^\d{1,4}(\/[A-Z0-9]{1,3})?$/.test(n)));
});

test('geo transform and page size are present', () => {
  assert.ok(idx.geo, 'Badarkha is registered');
  assert.ok(Math.abs(idx.pageSize[0] - 3370.51) < 1);
});

test('adjacency is symmetric and never self-referential', () => {
  for (const p of idx.parcels) {
    assert.ok(!p.adj.includes(p.id));
    for (const n of p.adj) assert.ok(idx.parcels[n].adj.includes(p.id), `${p.id}<->${n} asymmetric`);
  }
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/build-index.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `build-index.mjs`**

```js
// Turns one village-map PDF into the compact index the app renders. Pure function first
// (buildIndex), executable wrapper second, so the extraction is testable without network.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { inflateStreams, pageSize } from './lib/pdf.mjs';
import { parsePaths, parseTexts } from './lib/content.mjs';
import { parseGeo } from './lib/geo.mjs';
import { bbox, area, contains, adjacency } from './lib/geom.mjs';
import { classify } from './lib/labels.mjs';

// Parcels smaller than this are hatching, arrowheads and title-block rules, not land.
const MIN_PARCEL_AREA = 200;
// The page frame itself is a closed path; anything covering most of the sheet is not a parcel.
const MAX_PARCEL_FRACTION = 0.6;

export function buildIndex(buf, meta) {
  const [w, h] = pageSize(buf);
  const page = inflateStreams(buf)[0] ?? '';

  const polys = parsePaths(page).filter((p) => {
    const a = area(p);
    return a >= MIN_PARCEL_AREA && a <= w * h * MAX_PARCEL_FRACTION;
  });

  const texts = parseTexts(page).map((t) => ({ ...t, ...classify(t.text) }));

  // Label a parcel with the survey number sitting inside it. Smallest containing parcel wins, so a
  // number inside a block that itself sits inside a larger enclosing path binds to the real plot.
  const order = polys.map((p, i) => ({ i, a: area(p) })).sort((x, y) => x.a - y.a);
  const surveyNo = new Array(polys.length).fill(null);
  const used = new Set();
  for (const t of texts) {
    if (t.kind !== 'SURVEY' || used.has(t.value)) continue;
    for (const { i } of order) {
      if (surveyNo[i] === null && contains(polys[i], [t.x, t.y])) {
        surveyNo[i] = t.value;
        used.add(t.value);
        break;
      }
    }
  }

  const adj = adjacency(polys);
  const parcels = polys.map((poly, i) => ({
    id: i,
    surveyNo: surveyNo[i],
    poly: poly.map(([x, y]) => [round(x), round(y)]),
    adj: adj[i],
  }));

  const features = texts
    .filter((t) => t.kind === 'ROAD' || t.kind === 'PLACE' || t.kind === 'NOTE')
    .map((t) => ({ kind: t.kind, label: t.value, x: round(t.x), y: round(t.y) }));

  return {
    villageId: meta.villageId,
    villageName: meta.villageName,
    districtName: meta.districtName,
    talukaName: meta.talukaName,
    pageSize: [round(w), round(h)],
    geo: parseGeo(buf),
    parcels,
    features,
    quality: 'GOOD', // qa-index.mjs is the authority; this is the optimistic default
  };
}

const round = (n) => Math.round(n * 100) / 100;

async function main() {
  const districts = ['Kheda', 'Anand'];
  const catalog = JSON.parse(readFileSync('tools/ejamin/out/catalog.json', 'utf8'));
  const sheets = catalog.sheets.filter(
    (s) => s.type === 'VILLAGE_MAP' && districts.includes(s.districtName),
  );
  mkdirSync('tools/ejamin/out/indexes', { recursive: true });
  mkdirSync('tools/ejamin/out/pdf-cache', { recursive: true });

  const manifest = [];
  for (const s of sheets) {
    const cache = `tools/ejamin/out/pdf-cache/${s.driveFileId}.pdf`;
    if (!existsSync(cache)) {
      const res = await fetch(s.downloadUrl);
      if (!res.ok) { console.warn(`skip ${s.villageName}: HTTP ${res.status}`); continue; }
      writeFileSync(cache, Buffer.from(await res.arrayBuffer()));
    }
    const buf = readFileSync(cache);
    if (!buf.subarray(0, 5).toString().startsWith('%PDF')) {
      console.warn(`skip ${s.villageName}: not a PDF (Drive quota page?)`);
      continue;
    }
    let idx;
    try {
      idx = buildIndex(buf, s);
    } catch (e) {
      console.warn(`skip ${s.villageName}: ${e.message}`);
      continue;
    }
    const file = `village-${s.villageId}.index.json`;
    const json = JSON.stringify(idx);
    writeFileSync(`tools/ejamin/out/indexes/${file}`, json);
    manifest.push({
      villageId: s.villageId, villageName: s.villageName,
      districtName: s.districtName, talukaName: s.talukaName,
      file, bytes: json.length,
      sha256: createHash('sha256').update(json).digest('hex'),
      parcels: idx.parcels.length,
      labelled: idx.parcels.filter((p) => p.surveyNo).length,
      geo: !!idx.geo,
      quality: idx.quality,
    });
    console.log(`${s.districtName}/${s.villageName}: ${idx.parcels.length} parcels`);
  }
  writeFileSync('tools/ejamin/out/indexes/manifest.json', JSON.stringify({ villages: manifest }, null, 1));
  console.log(`indexes: ${manifest.length}`);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/build-index.test.mjs`
Expected: PASS, 4 tests.

If the labelled-fraction test fails, do not lower the threshold. Print the unlabelled parcels' bounding boxes and the unplaced survey labels, and fix the cause (usually `MIN_PARCEL_AREA` or a text run split across two `Tj` operators).

- [ ] **Step 5: Build the real indexes**

Run: `node tools/ejamin/build-index.mjs`
Expected: one line per village, then `indexes: <N>`.

- [ ] **Step 6: Commit**

```bash
git add tools/ejamin/build-index.mjs tools/ejamin/test/build-index.test.mjs
git commit -m "feat(ejamin): build per-village parcel indexes from GeoPDF sheets"
```

---

### Task 10: QA gate

**Files:**
- Create: `tools/ejamin/qa-index.mjs`
- Create: `tools/ejamin/test/qa.test.mjs`

**Interfaces:**
- Consumes: `out/indexes/manifest.json` and the index files (Task 9).
- Produces: `assess(idx) -> { quality: 'GOOD'|'LINK_ONLY', reason: string|null }`; rewrites each index's `quality` field and the manifest in place.

- [ ] **Step 1: Write the failing test**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assess } from '../qa-index.mjs';

const good = {
  geo: { matrix: [1, 0, 0, 0, 1, 0], crs: 'EPSG:4326' },
  parcels: Array.from({ length: 40 }, (_, i) => ({ id: i, surveyNo: String(i + 1), poly: [], adj: [] })),
};

test('a healthy index passes', () => {
  assert.deepEqual(assess(good), { quality: 'GOOD', reason: null });
});

test('no geo registration demotes to LINK_ONLY', () => {
  assert.equal(assess({ ...good, geo: null }).quality, 'LINK_ONLY');
});

test('too few parcels demotes to LINK_ONLY', () => {
  assert.equal(assess({ ...good, parcels: good.parcels.slice(0, 4) }).quality, 'LINK_ONLY');
});

test('more than 30% unlabelled demotes to LINK_ONLY', () => {
  const parcels = good.parcels.map((p, i) => (i < 20 ? { ...p, surveyNo: null } : p));
  const out = assess({ ...good, parcels });
  assert.equal(out.quality, 'LINK_ONLY');
  assert.match(out.reason, /unlabelled/);
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `node --test tools/ejamin/test/qa.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `qa-index.mjs`**

```js
// The honesty gate. A village whose extraction is weak ships as LINK_ONLY — the app then shows the
// original sheet instead of a map that would place survey numbers in the wrong field. Shipping a
// wrong map is worse than shipping no map.
import { readFileSync, writeFileSync } from 'node:fs';

const MIN_PARCELS = 5;
const MAX_UNLABELLED = 0.3;

export function assess(idx) {
  if (!idx.geo) return { quality: 'LINK_ONLY', reason: 'no georeferencing on the sheet' };
  if (!idx.parcels || idx.parcels.length < MIN_PARCELS) {
    return { quality: 'LINK_ONLY', reason: `only ${idx.parcels?.length ?? 0} parcels extracted` };
  }
  const unlabelled = idx.parcels.filter((p) => !p.surveyNo).length / idx.parcels.length;
  if (unlabelled > MAX_UNLABELLED) {
    return { quality: 'LINK_ONLY', reason: `${Math.round(unlabelled * 100)}% of parcels unlabelled` };
  }
  return { quality: 'GOOD', reason: null };
}

function main() {
  const path = 'tools/ejamin/out/indexes/manifest.json';
  const manifest = JSON.parse(readFileSync(path, 'utf8'));
  let demoted = 0;
  for (const row of manifest.villages) {
    const file = `tools/ejamin/out/indexes/${row.file}`;
    const idx = JSON.parse(readFileSync(file, 'utf8'));
    const verdict = assess(idx);
    idx.quality = verdict.quality;
    writeFileSync(file, JSON.stringify(idx));
    row.quality = verdict.quality;
    row.reason = verdict.reason;
    if (verdict.quality === 'LINK_ONLY') {
      demoted++;
      console.log(`LINK_ONLY ${row.districtName}/${row.villageName}: ${verdict.reason}`);
    }
  }
  writeFileSync(path, JSON.stringify(manifest, null, 1));
  console.log(`${manifest.villages.length - demoted} GOOD, ${demoted} LINK_ONLY`);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
```

- [ ] **Step 4: Run the tests**

Run: `node --test tools/ejamin/test/qa.test.mjs`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the gate over the real output**

Run: `node tools/ejamin/qa-index.mjs`
Expected: a summary line. Note the GOOD/LINK_ONLY split — this number is the honest coverage figure for Kheda + Anand and belongs in the handoff to Plan 2.

- [ ] **Step 6: Verify one village against the printed sheet by hand**

Pick a GOOD village. Open its `viewUrl` from `catalog.json` in a browser, choose three survey numbers scattered across the sheet, and confirm the index agrees:

```bash
node -e "const i=require('./tools/ejamin/out/indexes/village-<ID>.index.json');
for (const n of ['<A>','<B>','<C>']) {
  const p=i.parcels.find(p=>p.surveyNo===n);
  console.log(n, p ? 'found, '+p.adj.length+' neighbours' : 'MISSING');
}"
```

Expected: all three found, with a plausible neighbour count (2–8). This is the only check that catches a systematically shifted transform, and it must be done before Plan 2 starts.

- [ ] **Step 7: Run the whole suite and commit**

```bash
node --test tools/ejamin/test/
git add tools/ejamin/qa-index.mjs tools/ejamin/test/qa.test.mjs tools/ejamin/out/indexes/manifest.json
git commit -m "feat(ejamin): QA gate demotes weak extractions to LINK_ONLY"
```

---

## Handoff to Plan 2

Plan 2 consumes exactly three artefacts:

1. `tools/ejamin/out/catalog.json` — copied to `android/app/src/main/assets/maps/catalog.json`.
2. `tools/ejamin/out/indexes/village-*.index.json` — published to the releases repo.
3. `tools/ejamin/out/indexes/manifest.json` — the checksum + quality list the app trusts.

Record the GOOD/LINK_ONLY counts from Task 10 Step 5 in the Plan 2 kickoff, so the app's coverage copy is truthful.
