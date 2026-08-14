# Land Records App — Master Spec & Build Handoff

> **New chat: start here.** Read this end-to-end, then read `docs/PROJECT_NOTES.md` (what already works +
> the hard-won gotchas) and `docs/ANDROID_APP_PLAN.md` (locked decisions). **Do NOT write app code first.**
> First produce the **design plans** (see §11), get the user's approval on the look & the workflows, then
> build to match. The bar is a **polished, production-level app any non-technical person (the user's dad)
> can use** — not a dev tool.

---

## 1. What this is & who it's for

A native Android app that fetches Gujarat land records (AnyRoR + iRCMS) and is, above all, a **calm,
organized library** for them. Today those PDFs get dumped into WhatsApp; that must stop. The app becomes
the home for the files; WhatsApp is just a *share* target. Primary users: the user and their **father**
(non-technical). It must feel trustworthy, obvious, and quiet.

## 2. Product principles

- **Show only what's needed.** No dashboards of knobs. Most of the time the user is *browsing saved
  records*; occasionally *getting a new one*. Design for those two, hide everything else.
- **Library-first.** Opening the app lands on the library, not a form.
- **One human step, clearly framed.** The only unavoidable manual bit is solving the CAPTCHA — present it
  cleanly, everything around it automated.
- **Trustworthy & legible.** Bilingual (Gujarati + English) where the data is; real land data, real names —
  treat it with care. Clean typography, generous spacing, no clutter.
- **Offline-first.** Saved records open instantly with no network. Fetching is the only online action.
- **Yours, not locked in.** Files live in a visible `Documents/LandRecords/…` folder, backup-able/exportable.
- **Personal scale.** For the family's own land. Not a bulk-harvest tool.

## 3. The core insight (why an app fixes what scripts couldn't)

The app loads AnyRoR inside an embedded **WebView**:
- runs on the **phone's mobile-data IP** → residential, so AnyRoR's WAF doesn't block it (the desktop
  scripts kept getting the home IP blocked);
- a WebView **is a real browser** → no headless fingerprint to reject;
- the user solves the **CAPTCHA right in the view** → exactly what the portal expects.
The app injects the cascade automation (already proven in `anyror/*.mjs`) around that one human tap.

## 4. Workflows (decide these — this is the product)

**A. Browse the library (the default).**
Home = the saved records. Navigate the hierarchy or jump via search. Open a survey → see its records
(Integrated, VF-7/12, Deeds, iRCMS) as cards with metadata (area, tenure, land-use, "as of" date, #docs).
Tap a record → in-app PDF viewer. Long-press / share icon → Android share sheet.

**B. Add / manage a property.**
A property = State ▸ District ▸ Taluka ▸ Village + a **list of survey numbers**. Add once; it's the saved
target for fetching. Editable. (Seed data: Anand/Umreth/Bharoda already has records; Valetva/41 and
Sundalpura 906, 845/અ, 851, 901/p, 902 are queued.)

**C. Get a new record / refresh (the online action).**
From a survey (or a "Get records" button), pick the record type. The app opens the AnyRoR WebView
**pre-filled** (record type + district/taluka/village/survey auto-selected). The user **solves the CAPTCHA
and taps Get Record Detail**. The app then captures the result, builds the PDF **on-device**, saves the
**raw HTML + extracted data** alongside it, and files everything in the library. Progress is shown; the
user isn't left guessing.

**D. Re-export / re-style without re-fetching (important — the user called this out).**
Every fetched record stores its **raw HTML** (and, for VF-7/12/deeds, the raw scan files). So the app can:
- **Re-render the PDF** with an updated layout (font, columns, spacing) — no CAPTCHA, no network. This is
  exactly how the desktop tool fixed the AnyRoR PDFs (render from saved HTML, never from lossy JSON).
- **Export** the raw HTML / source files if the user ever wants them.
A "Re-generate PDF" action on each record applies the current layout to the stored source.

**E. Share.**
Any file or a whole survey folder → Android share sheet (WhatsApp, Drive, email…). Never the storage,
always optional.

## 5. Screens (only what's needed)

1. **Library (home)** — hierarchy browse + search + recents. Clean cards.
2. **Survey detail** — the survey's records as cards; per-record: view / re-generate / share; a "Get more"
   action for missing types.
3. **Fetch (WebView)** — the AnyRoR page, pre-filled, with a slim banner telling the user the one thing to
   do ("Type the code shown and tap Get Record Detail"). Capture happens automatically after.
4. **Add/Edit property** — state/district/taluka/village pickers + survey-number list editor.
5. **Settings** — storage location, layout/theme, backup/export, about. Minimal.

## 6. Data model & storage

- **Hierarchy:** Gujarat ▸ District ▸ Taluka ▸ Village ▸ Survey ▸ record type. Kept generic so other
  states slot in later.
- **Files:** `Documents/LandRecords/<District>/<Taluka>/<Village>/Survey <No>/` containing
  `Integrated Record.pdf`, `VF-7-12.pdf`, `Deeds/…pdf`, `iRCMS Cases/…pdf`, plus a hidden `.source/`
  holding the **raw HTML + JSON + raw scans** for re-render/export.
- **Metadata DB (Room):** properties, surveys, records, per-record metadata (area, tenure, land-use,
  as-of, #docs, fetched-at, source paths) for fast browse/search without opening files.

## 7. Record types — capture & render (reuse the proven desktop logic)

- **Integrated Survey Record** (AnyRoR record type 8): after the detail page loads, inject the
  `anyror/format.mjs` cleanup CSS into the WebView and **print-to-PDF** (Android PrintManager/PdfDocument).
  Save raw HTML for re-render. This reproduces the tuned layout (9.4pt, content-balanced columns, compact
  header, empty sections dropped, darker borders).
- **Old Scanned VF-7/12** (type 11): JS-fetch each `PDFView1.aspx?detail=…` via the WebView's cookies →
  weed placeholders (real scans are images; a placeholder is a text PDF → drop any with a text layer) →
  combine newest→oldest with year-label pages.
- **Deeds** (Sub-registrar section of the SAME type-8 detail page): captured in the Integrated pass, not
  fetched separately — the detail page must be reached through the **desktop** entry
  (`LandRecordRural.aspx/1000`); the mobile entry omits the deed grid. The rows are read as data
  (`deeds.json`) and surface as a pill on the Integrated card; the deed table also prints inside the
  Integrated PDF. Each "View Deed" postback is still replayed for the scan — usually "Document Record
  Not Found"; when one does arrive, a CCITT **TIFF** is embedded → PDF via pdfbox's `CCITTFactory`.
- **iRCMS** (separate site): case + order PDFs; its CAPTCHA reuses across surveys, so batch-friendly.

See `docs/PROJECT_NOTES.md` §"Hard-won lessons" and the memory files for the exact mechanisms and traps
(PDF-viewer eats response bodies → fetch bytes yourself; Gujarati+Latin fonts need a fallback; etc.).

## 8. Technical architecture

Kotlin + Jetpack Compose · embedded **WebView** (full cookie + JS-injection + print-to-PDF control) ·
**Room** for metadata/search · a PDF lib (merge/re-render) · a TIFF decoder. The AnyRoR cascade/extraction
JS lives as a **remotely-updatable config** (a versioned JS/JSON blob the app can refresh) so when the
portal changes its markup, selectors are fixed **without shipping a new APK**. Storage via
`MediaStore`/SAF into the visible `Documents/LandRecords` tree.

## 9. Non-negotiables carried over (don't relearn the hard way)

- **Never hammer AnyRoR** — but the WebView-on-phone design already avoids the WAF block. No polling, no
  retry bursts.
- **CAPTCHA is always human.** Never auto-solve.
- **Render PDFs from saved HTML**, never from lossy extracted JSON (nested cells → gibberish).
- **Weed VF-7/12 placeholders** by text-layer presence.
- **TIFF/PDF**: capture bytes via fetch, not the viewer.
- **Fonts**: Gujarati primary + Latin fallback (Noto), or Latin renders as boxes.
Full detail: `docs/PROJECT_NOTES.md`, and memory at `~/.claude/projects/.../memory/`.

## 10. Build order (each slice testable on-device before the next)

0. Scaffold (Gradle, Compose shell, permissions, `Documents/LandRecords` storage, Room).
1. **Library** — browse/view/share, seeded with the PDFs we already generated (works with zero network).
2. **WebView engine** — pre-filled cascade + CAPTCHA hand-off; validate on the **Pixel** (real mobile IP).
3. **Integrated** → print-to-PDF + raw-HTML save + re-render.
4. **VF-7/12** → fetch + weed + combine.
5. **Deeds** → TIFF→PDF.
6. **iRCMS** → cases.
7. Polish — search, metadata cards, backup/export.

**Testing:** `adb install` to the user's **tab first**, then **Pixel**; only after both, it goes to dad.
Steps 2–6 need the user on **phone hotspot** (residential IP) to actually load AnyRoR.

## 11. Design kickoff — produce Claude design plans BEFORE coding

The user explicitly wants an initial **Claude-made design** to react to and build on, aiming for a
**polished, production-level, anyone-can-use** app. So, in the new chat, before any Kotlin:

1. Load the **frontend-design** and **artifact-design** skills.
2. Produce an **interactive HTML mockup (Artifact)** of the key screens — **Library (home)**, **Survey
   detail**, **Fetch/WebView step**, **Add property** — reflecting the workflows in §4–5. Bilingual
   (Gujarati + English) content, calm and trustworthy, obviously usable by a non-technical parent, "show
   only what's needed."
3. Offer **1–2 distinct visual directions** for the user to pick from; iterate to a chosen, refined look.
4. Only after the user approves the design + the workflows, translate it into the Compose UI and build per
   §10. Keep the approved mockup as the visual source of truth.

## 12. What already exists to reuse

- Proven automation & logic: `anyror/run-anyror.mjs`, `render-anyror-offline.mjs`, `format.mjs` (the PDF
  CSS), `run-vf712.mjs`, `build_vf712_combined.py`, iRCMS `src/*.mjs`, `wa/*` (WhatsApp, optional).
- Already-generated PDFs under `output/<TOKEN>/` — perfect **seed data** for the library so save/browse is
  demoable on day one.
- Memory files (`~/.claude/projects/.../memory/`) and `docs/PROJECT_NOTES.md` for every gotcha.
- **Jantri rates** — `data/jantri/README.md` (the data) and
  `docs/specs/2026-08-11-jantri-land-rates.md` (the feature). All 26 districts are parsed and
  verified on disk; Anand + Kheda ship in the APK.
- **Releasing** — `tools/release/release.sh`, one command, with a signing-certificate check
  that must never be bypassed.

## 13. Decided direction (not yet built)

- **A place is an id, not a name** — `docs/plans/2026-08-11-unified-place-identity.md`.
  Every source spells a village differently, so identity-by-name keeps producing duplicate
  cards and duplicate folder trees. Store the iRCMS district+taluka+village code triple and
  resolve at the one point the user picks a place. Dedupe then has nothing to do.
- **Problem reports over WhatsApp** — `docs/plans/2026-08-11-whatsapp-problem-reports.md`.
  Diagnostics must never carry land data.
