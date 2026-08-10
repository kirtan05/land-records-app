# Maps: village cadastre sheets from eJamin Gujarat

Design, 2026-08-10. Adds a **Maps** feature to the Land Records Android app:
a catalogue of every map sheet eJamin Gujarat publishes, and — for Kheda and
Anand — an interactive vector cadastre map with survey-number search, automatic
adjoining-parcel detection and manual colour-marking.

Spec convention follows `docs/specs/2026-06-11-ircms-scraper-design.md`.

---

## 1. What was verified before designing

These are measured facts, not assumptions. Everything below depends on them.

### 1.1 The eJamin endpoints

`https://ejamingujarat.com/` serves a CSRF token and session cookie. All map
lookups are one endpoint:

```
POST https://ejamingujarat.com/villageMapGet
Headers: X-Requested-With: XMLHttpRequest
Body (multipart): _token=<csrf>, id=<id>, type=<district|taluka|village|tpTitle|districtGdcr|districtDp|...>
```

- `type=district`, `id=3` → `{"status":1,"data":[{"id":29,"name":"Anand"},…]}` (talukas)
- `type=taluka`, `id=29` → villages `[{"id":811,"name":"Adas"},…]`
- `type=village`, `id=1` → the sheet row, including
  `"link":"https:\/\/drive.google.com\/file\/d\/1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB\/view"`

TP Map data for **all** districts is additionally embedded in the homepage HTML
as a `let tpMapData = {…}` literal, carrying `link`, `gdsr_link` and
`f_form_link` per scheme. It is parsed from the same single GET.

**The district id space is per map type.** Kheda is id `14` in one tab and `18`
in another; Anand is `3` and `20`. Ids MUST be stored and used keyed by type.
Sharing an id across types silently returns another district's data.

Drive files download unauthenticated via
`https://drive.google.com/uc?export=download&id=<fileId>` (verified: HTTP 200,
`application/octet-stream`).

### 1.2 The PDFs

Sampled `Badarkha` (Ahmedabad/Dholka), file id `1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB`:

- **Esri ArcMap 10.4.0.5524** vector PDF, 1 page, A0 (3370.51 × 2384.25 pt), 376 KB.
- **Not a scan.** 8,395 `Tj` text operators. `pdftotext` yields survey numbers
  directly (`81 85 78 86 84 87 … 74/P …`) plus header text
  (`District:- Ahmedabad`, `Taluka … Village :- Badarkha`) and neighbouring
  village names (`Bhat`, `Kavitha`) and status notes (`Not Promulgated`).
- **Real parcel geometry.** Content stream carries closed vector paths
  (`… m … l … h W* n`), so parcels are polygons, not an image.
- **Georeferenced.** `/Viewport` entries with `/Measure`, `/GEO`, `/GPTS`,
  `/LPTS` and `/GCS` — a lat/long registration exists for the map frame.
- `/OCProperties` lists 11 optional-content groups (layers).

Consequences: survey-number search is a text lookup (no OCR); adjacency is a
shared-edge test on real polygons (not label proximity); every parcel can be
given real-world coordinates.

---

## 2. Approach

Decided during brainstorming:

| Decision | Choice |
|---|---|
| Where the index is built | **Precomputed on the dev box**, shipped — not extracted on-device |
| What marking means | **Auto-suggest the neighbour ring, user confirms**, plus free manual marking |
| How the map is drawn | **Vector-first** on a Compose `Canvas`; original PDF always one tap away |
| Distribution | Catalogue **in the APK**; per-village indexes **fetched and cached** |

Rejected: on-device PDF text extraction (Android `PdfRenderer` cannot read text;
bundling PDFBox-Android or pdf.js adds weight and risk on A0 pages, and offers no
chance to QA per-village quirks). Rejected: pre-rendered raster tile pyramids
(~10–40 MB per village at readable DPI). Rejected: link-only (no search, no
marking).

### Scope

- **Catalogue: all seven sheet types, all districts** — TP Map, Village Map,
  Live TP Map, GDCR, DP, F-Form, RERA Project Location Map. Nearly free to
  collect and makes every district at least link-usable.
- **Deep polygon index: Kheda and Anand village maps only.**

Explicitly out of scope: satellite/basemap overlay, GPS tracking, geometry
editing or export, deep indexes for other districts.

---

## 3. Components

Three units with separate lifecycles and no shared state beyond a documented
JSON contract.

### 3.1 `tools/ejamin/` — the pipeline (Node, on the dev box)

Sits beside `anyror/`, same idiom (plain `.mjs`, no framework).

**`scrape-catalog.mjs`**
- One GET of the homepage → CSRF token, session cookie, `tpMapData` literal.
- Walks every type × district × taluka × village through `villageMapGet`.
- Normalises the backslash-escaped Drive URLs; extracts the Drive file id.
- Serial requests with a small delay between them, per the project's standing
  politeness rules for government sites.
- Emits `catalog.json`:
  ```json
  {
    "generatedAt": "2026-08-10",
    "sheets": [{
      "type": "VILLAGE_MAP",
      "districtId": 3, "districtName": "Anand",
      "talukaId": 29, "talukaName": "Anand",
      "villageId": 811, "villageName": "Adas",
      "driveFileId": "1r-hk…",
      "viewUrl": "https://drive.google.com/file/d/1r-hk…/view",
      "downloadUrl": "https://drive.google.com/uc?export=download&id=1r-hk…"
    }]
  }
  ```
  `talukaId`/`villageId` are `null` for types that terminate earlier (e.g. GDCR
  and DP resolve at district or taluka level).

**`build-index.mjs`** — for Kheda + Anand village-map sheets only:
1. Download the PDF (cached locally; skip if checksum unchanged).
2. Inflate content streams; parse closed subpaths → parcel polygons in page space.
3. Parse `Tj` runs with the current text matrix → labels with page coords.
4. Parse `/Viewport` `/Measure` `/GPTS` + `/LPTS` → page↔lat/long affine transform.
5. Assign each label to the polygon containing its anchor point.
6. Classify labels: survey number (digits, optionally `/P`, `/A`, Gujarati
   numerals) vs feature (road, canal, neighbouring village, note).
7. Compute adjacency: polygons sharing an edge within a tolerance.
8. Emit `village-<villageId>.index.json`:
   ```json
   {
     "villageId": 811, "villageName": "Adas",
     "districtName": "Anand", "talukaName": "Anand",
     "pageSize": [3370.51, 2384.25],
     "geo": { "matrix": [a,b,c,d,e,f], "crs": "EPSG:4326" },
     "parcels": [{ "id": 12, "surveyNo": "221/P", "poly": [[x,y],…], "adj": [13,14,40] }],
     "features": [{ "kind": "ROAD", "label": "…", "poly": [[x,y],…] }],
     "quality": "GOOD"
   }
   ```
9. Writes `manifest.json` — one row per village with SHA-256 and byte size.

**`qa-index.mjs`** — demotes a village to `quality: "LINK_ONLY"` when any of:
no geo transform, fewer than 5 polygons, more than 30% of numeric labels
unassigned to a polygon. A demoted village ships without a map rather than
shipping a map that lies. The failing reason is recorded in the manifest.

### 3.2 Distribution

- `catalog.json` is bundled in the APK (`assets/`). It is small and makes every
  district usable offline from first launch.
- Village indexes are published to `kirtan05/land-records-releases` beside
  `update.json`, fetched on first open of that village, then cached at
  `Documents/LandRecords/maps/village-<id>.index.json` — visible in the same
  user-facing tree as the rest of the app's storage, and offline permanently
  after.
- Downloaded PDFs cache to `Documents/LandRecords/maps/sheets/`.

### 3.3 App

**`data/maps/`** — follows the app's existing split: Room holds only
Property/Survey/Record; **per-item marks live in JSON manifests under
`filesDir`** (`CasesStore`, `VfScansStore`). Maps follows that convention, so
**no Room entities and no database migration are added.**

- `MapCatalog` — reads the bundled `assets/maps/catalog.json` once, exposes
  district → taluka → village queries in memory.
- `MapIndexStore` — fetch + SHA-256 verify + cache of `village-<id>.index.json`
  under `Documents/LandRecords/maps/`; PDF download/cache under
  `.../maps/sheets/`.
- `MapMarksStore` — the `VfScansStore` analogue. One manifest per village at
  `filesDir/maps/<villageId>/marks.json`, rows of
  `{surveyNo, mark, note, source ∈ {AUTO, CONFIRMED, MANUAL}, updatedAt}`.
  `mark` is a `MarkColor` id, so map marks reuse the existing 6-colour palette
  and `MarkDot` control unchanged.

**`ui/maps/geometry/`** — pure Kotlin, no Compose imports, JVM-testable:
`MapIndex` model, `GeoTransform` (page ↔ lat/long), `HitTest` (point in polygon),
`Adjacency` (neighbour ring for a parcel), `SurveyNo` (parse/normalise Gujarati
and Latin numerals, `/P` parts, equality).

**`ui/maps/`** — `MapsBrowseScreen`, `VillageMapScreen`, `SheetLinkScreen`, and a
`ParcelCanvas` composable. Registered as a new destination in `AppNav.kt`.

Boundary rule: `geometry/` knows nothing about Compose or Room; `ui/` never
parses a PDF; the pipeline never knows about Android.

---

## 4. Screens

All screens follow `design_handoff_land_records_ui/README.md`: Cadastre
direction, comfy density, ochre accent `#B4531B` (dark `#E58A55`), no elevation,
1dp borders only.

### 4.1 Maps browse

Fourth destination alongside Library. Cascade District → Taluka → Village,
using the same cascade vocabulary as the existing fetch flow.

Each village is a **parcel tile**: 1dp border, radius 12dp, 1dp dashed inset at
5dp. It carries a stamp-strip variant whose slots show which sheet types exist
for that village. Villages with a deep index carry an accent badge; link-only
villages are shown plainly as link-only — not dressed up as interactive.

Survey numbers, counts and all-caps labels in IBM Plex Mono; headings and body in
Space Grotesk; Gujarati falls back to Noto Sans Gujarati in every family.

### 4.2 Village map

A Compose `Canvas` with pan and zoom. Polygons stroked in `line`; survey labels
in IBM Plex Mono, scaled with zoom and hidden below a legibility threshold.
Both themes are first-class.

Top bar: survey-number search field, layer toggle, **Open full sheet**.

- **Search by survey number.** Entering `221/P` fits the camera to that parcel
  and fills it `accentSoft` with an `accent` stroke. Gujarati numerals are
  accepted and preserved; a Latin helper line renders beneath. Land data is
  never translated.
- **Adjoining.** The shared-edge neighbour ring renders as dashed outlines, and
  a bottom sheet lists those survey numbers plus any road, canal or boundary
  feature touching the parcel. Each starts as `AUTO`; tapping one promotes it to
  `CONFIRMED` and persists it.
- **Manual marking.** Long-press any parcel to colour-mark it, using the same
  colour vocabulary as v0.8.7 scans and cases, with an optional note. Marks
  persist and surface on the property detail screen when the survey number
  matches a held record.
- **Open full sheet.** Always available. Renders the cached PDF with
  `PdfRenderer`; falls back to the Drive link. This is also the entire UI for
  link-only villages and for the other six sheet types.

App chrome respects the language setting (gu | both | en) via `L(gu, en)`.
Unknown metadata renders `—`. All motion collapses under reduced-motion —
camera animations become instant jumps.

---

## 5. Error handling

Every failure degrades one tier; none dead-ends.

| Failure | Behaviour |
|---|---|
| No deep index for the village | Show the full sheet instead |
| Index fetch fails | Full sheet; retry available; the village stays browsable |
| Checksum mismatch on a cached index | Delete the cache, refetch once, then fall back to the sheet |
| PDF not cached | Open the Drive link |
| No network | Cached index and cached PDF still work; uncached villages say so plainly |
| Survey number not found in the index | Say it is not on this sheet — never guess a location |
| Extraction could not place a label | That survey number is simply not searchable |

No land data is invented anywhere in the pipeline or the UI.

---

## 6. Testing

**JVM unit tests (`ui/maps/geometry/`)**
- `GeoTransform` against a known lat/long fixture derived from the Badarkha sheet.
- `HitTest` point-in-polygon, including concave parcels and points on an edge.
- `Adjacency` on a hand-built grid with a known neighbour ring.
- `SurveyNo` parsing: Gujarati numerals, Latin numerals, `/P` and `/A` parts,
  whitespace variants, equality across scripts.

**Pipeline tests (`tools/ejamin/`)**
- Run against two checked-in real PDFs (one clean, one known-awkward); assert
  parcel count, that a specific survey number lands in a specific polygon, and
  that `qa-index.mjs` demotes a deliberately broken fixture.
- Catalogue parser test over a saved homepage snapshot, asserting the per-type
  district id spaces stay separate.

**Android**
- The module currently has **no test source set at all**. The first Android task
  creates `app/src/test/java/` and adds the JUnit `testImplementation`
  dependency; all Kotlin tests below are plain JVM tests (no emulator).
- `MapCatalog` parse test over a checked-in catalogue fixture.
- `MapMarksStore` round-trip and corrupt-manifest recovery, using a temp dir.

---

## 7. Build order

1. `scrape-catalog.mjs` + catalogue parser test → `catalog.json` for all types
   and districts.
2. `build-index.mjs` geometry and text extraction → indexes for one village,
   verified by hand against the printed sheet.
3. `qa-index.mjs`, then a full Kheda + Anand run; publish the manifest.
4. `data/maps/` — `MapCatalog`, `MapIndexStore`, `MapMarksStore` (no Room work).
5. `ui/maps/geometry/` with its unit tests.
6. `MapsBrowseScreen` + `SheetLinkScreen` — link-only value lands here, whole
   feature is already useful.
7. `ParcelCanvas` + `VillageMapScreen`: render, then search, then adjoining,
   then marking.

Each step is independently shippable; the feature is useful from step 6.
