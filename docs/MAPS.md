# Maps: what exists, what the data actually is

Reference for the village-map feature. Everything in "Measured facts" was verified
against real files on 2026-08-10/11 — none of it is inferred. Read this before
planning any map work; a whole design was once built on a single unrepresentative
sample and had to be thrown away.

Future options live in `docs/plans/2026-08-11-maps-future-possibilities.md`.

---

## 1. What ships today (v0.9.0+)

- **Village card → official map.** Each village card in the Library shows a small map
  glyph when that village has a published sheet; tapping it opens the government PDF
  (Google Drive) in the browser.
- **Maps browse screen** (top bar): district → taluka → village across all of Gujarat.
  Kept deliberately as the future home of a real in-app map view.
- **Bundled catalogue:** `apps/android/app/src/main/assets/maps/villages.json`, 788 KB,
  33 districts / 261 talukas / **16,044 villages**. Grouped `district → taluka →
  [[villageName, driveFileId]]`; both Drive URLs are derived from the file id:
  - view: `https://drive.google.com/file/d/<id>/view`
  - download: `https://drive.google.com/uc?export=download&id=<id>` (no auth needed)
- Browsing works offline; only opening a map needs a connection.

**Not shipped, and not currently possible from this data:** survey-number search on the
map, adjoining-parcel detection, tap-to-select a parcel, in-app rendering with pan/zoom.
Section 3 explains why.

---

## 2. The source: ejamingujarat.com

A **private commercial site**, not a `gujarat.gov.in` host. The AnyRoR/iRCMS politeness
rules do **not** apply to it — see `packages/maps/lib/session.mjs`.

### Endpoints

One POST does everything:

```
POST https://ejamingujarat.com/villageMapGet
Headers: X-Requested-With: XMLHttpRequest   (the site's jQuery sets this; it is required)
Body (multipart): _token=<csrf>, id=<id>, type=<...>
```

| `type` | `id` is | returns |
|---|---|---|
| `district` | district id | talukas |
| `taluka` | taluka id | villages |
| `village` | village id | the sheet row, incl. `link` |
| `districtGdcr` | district id | GDCR rows |
| `districtDp` / `talukaDp` | district / taluka id | DP rows |
| `tpTitle` | TP scheme id | TP row (+ `gdsr_link`, `f_form_link`) |

The CSRF token and session cookie come from one GET of the homepage. **TP-map data for
every district is embedded in that same homepage** as a `let tpMapData = {…}` literal —
no crawling needed for it.

### The trap: district ids are per map type

Each tab numbers its districts independently. **Kheda is `14` in one tab and `18` in
another; Anand is `3` and `20`.** Ids are only meaningful within a type. Sharing one
across types silently returns a different district's data. `packages/maps/test/catalog.test.mjs`
guards this.

### Crawl performance

Serial with a 400 ms delay: **~14 hours** (measured — 45 min for one district). The
server answers in seconds, so the delay was never the bottleneck.

With a counting semaphore at concurrency 16 and back-off on 429/5xx: **~12 minutes for
the whole state**, with **0 throttling events and 0 session re-opens**. `EJAMIN_CONCURRENCY`
tunes it; the gate halves itself whenever the server pushes back.

### Catalogue totals (2026-08-10)

| Type | Sheets |
|---|---|
| VILLAGE_MAP | 16,044 |
| TP_MAP | 925 |
| DP | 848 |
| GDCR | **0 — known bug** |
| **Total** | **17,817** |

Kheda: 524 village maps. Anand: 357.

**Known bug:** GDCR returns zero rows. Near-certainly a wrong `selectClass` in the
`TYPES` table of `packages/maps/scrape-catalog.mjs` — the tab's district `<select>` is not
being found, so nothing is crawled. Village and TP maps are unaffected.

---

## 3. The sheets themselves — and why search is not possible today

All village maps are produced from the same source by the same software: **Esri ArcMap
10.4.0.5524**, one A0 page (~3370 × 2384 pt), same template, same legend. Visually they
are indistinguishable.

They were **exported in batches with different settings**, and that is the whole story:

| | Vector export | Raster export |
|---|---|---|
| Size | 0.3–0.6 MB | 2–5 MB |
| `/Font` | present (~25) | **0** |
| Parcel outlines | real vector paths (`m`/`l`/`h`) | none — one JPEG |
| Survey numbers | real text (`Tj`) — `pdftotext` reads them | pixels only |
| Georeferencing | `/Viewport` + `/Measure` + `/GPTS` + `/LPTS` | **none** |
| Timezone stamp | `+05` | `Z` |

Example: five Dholka (Ahmedabad) villages exported 2024-08-13 *seconds apart* are all
vector + georeferenced. Bharoda (Umreth, Anand) from 2024-07-20 is raster. Same taluka
can contain both — it tracks the export batch/operator, **not** the district.

### Coverage measured

- **Kheda + Anand: 881 village sheets classified — 0 vector, 881 raster.** No exceptions.
- Statewide: roughly **5%** vector (~759 of 16,044). The size heuristic mis-flags some
  small raster sheets (e.g. AMC plot maps), so any use of it must verify by parsing.

**Therefore:** for the two districts this app is actually about, there is no text layer to
search, no polygons to hit-test, and no coordinates to place anything. Survey-number
search and shared-edge adjacency cannot be built from these PDFs. This is a measured fact
over 881 files, not an inference.

### What the sheets *do* contain (they are genuinely good)

Every survey number printed in place, all bordering villages named, roads classified
(National Highway / State Highway / Major & Other District Road / Village Road), rivers,
and Gamtal shaded orange. For reading a village's layout, opening the real sheet delivers
most of the value the interactive version would have.

---

## 4. The pipeline (`packages/maps/`)

Plain Node ESM, no dependencies, Node 26. Tests: `node --test 'packages/maps/test/*.test.mjs'`
(the bare-directory form fails with MODULE_NOT_FOUND on this Node).

| File | Does |
|---|---|
| `lib/session.mjs` | CSRF + cookie, concurrency gate, back-off, `tpMapData` extraction |
| `lib/drive.mjs` | escaped Drive link → file id + view/download URLs |
| `scrape-catalog.mjs` | the crawl → `out/catalog.json` (7.7 MB, all types) |
| `classify-format.mjs` | vector vs raster via Drive `Content-Length` + verification |
| `build-app-catalog.mjs` | `out/catalog.json` → the 788 KB bundled `villages.json` |
| `lib/pdf.mjs`, `lib/geom.mjs` | PDF streams / plane geometry — still useful |
| `lib/content.mjs`, `lib/geo.mjs` | **vector-only; dead for Kheda/Anand.** Keep for the ~5% |

### Refreshing the catalogue

```bash
node packages/maps/scrape-catalog.mjs                       # ~12 min, all districts
EJAMIN_VILLAGE_DISTRICTS='Kheda,Anand' node packages/maps/scrape-catalog.mjs
node packages/maps/build-app-catalog.mjs                    # rewrites the app asset
```

`classify-format.mjs` takes `CLASSIFY_DISTRICTS` and writes `out/format-report.json`.

`out/catalog.json` is 7.7 MB; only the derived 788 KB `villages.json` needs to be in the
APK. `out/pdf-cache/` is gitignored.

---

## 5. Superseded documents

These were written before the raster discovery and describe a design that **cannot be
built from this data**. They are kept for the endpoint research and the geometry
approach, which stay valid for the ~5% vector sheets:

- `docs/specs/2026-08-10-maps-village-cadastre-design.md`
- `docs/plans/2026-08-10-maps-1-pipeline.md` (Tasks 1–3 shipped; 4–10 vector-only)
- `docs/plans/2026-08-10-maps-2-android.md` (never started)

---

## 6. Other leads, briefly

- **Bhu-Naksha** (`bhunaksha.gujarat.gov.in`) — the obvious source of real plot geometry
  by survey number. **Unreachable from this machine**: TCP 80 and 443 both fail
  immediately over forced IPv4 and by raw IP, while `anyror.gujarat.gov.in` returns 200
  as a control. Down, or blocked locally. **Never verified — check from a phone first.**
- ISRO/Bhuvan, LGD village boundaries, OpenStreetMap: unexplored.

All of these, with effort and risk, are in
`docs/plans/2026-08-11-maps-future-possibilities.md`.
