# irmsc — Gujarat Land Records Toolkit (Project Notes)

Automates retrieval of Gujarat land records for family land and packages them into clean PDFs +
an Excel index, with optional WhatsApp delivery. Built incrementally; this file is the map.

## What it does (working today)

| Source | What we pull | Key scripts |
|---|---|---|
| **iRCMS** (ircms.gujarat.gov.in) | RTS/revenue **case** detail + **order** PDFs per survey | `run-fast.mjs`, `src/scrape.mjs`, `src/store.mjs` |
| **AnyRoR — Integrated Survey Record** (record type 8) | Full 7/12: ownership, mutations, crop, area, SRO deeds, RTS cases | `anyror/run-anyror.mjs` → `anyror/render-anyror-offline.mjs` + `anyror/format.mjs` |
| **AnyRoR — Old Scanned VF-7/12** (record type 11) | Historical scanned 7/12 by period | `anyror/run-vf712.mjs`, `build_vf712_combined.py` |
| **Reports** | Per-survey combined PDF (clickable index), master Excel, zip | `build_reports.py`, `build_anyror_pdf.py`, `build-zip.mjs` |
| **Delivery** | WhatsApp (Baileys) — group or self | `wa/login.mjs`, `wa/send*.mjs`, `wa/check.mjs` |
| **Jantri (ASR-2011)** (garvi.gujarat.gov.in) | Government land rate per survey number, all 26 districts | `tools/jantri/*` → `data/jantri/README.md` |

Delivered so far for **Bharoda (Anand / Umreth), 9 surveys**: iRCMS cases + orders, AnyRoR integrated
records (redone clean), VF-7/12 (132 scans), all folded into `Bharoda_iRCMS_Cases.zip` + master Excel.

## Output layout

```
output/<TOKEN>/                     TOKEN = survey upper-cased, '/'→'_'  (221/p → 221_P)
  AnyRoR_SurveyNo_<TOKEN>_LandRecord.pdf   integrated record (final, from saved HTML)
  anyror_<TOKEN>.html / .json              raw page (re-render source) / extracted data
  oldvf712/                                VF712_<TOKEN>_<period>_rNN.pdf + VF712_<TOKEN>_ALL.pdf
  Case01/ Case02/ …                        iRCMS case + order + merged
  Bharoda_SurveyNo_<TOKEN>_ALL.pdf         iRCMS combined (index + bookmarks)
output/_state.json  _anyror_state.json  _vf712_state.json   progress/state
output/iRCMS_Bharoda_Master.xlsx    Summary (iRCMS + AnyRoR + VF-7/12 columns) + All Cases
```

## Hard-won lessons (read before touching AnyRoR)

1. **AnyRoR WAF IP-block.** Aggressive patterns get the IP blocked (`ERR_CONNECTION_CLOSED`, curl→`000`):
   curl polling loops, the browser's 3× `goto` retry bursts, and **headless Chrome** (fingerprinted &
   rejected even when curl on the same IP is 200). Fix: **one gentle *headed* load** on a **residential**
   IP (home IP rotation or **phone hotspot**). It re-trips instantly if bursted. **Do not** use a VPN —
   Surfshark specifically breaks Claude's connection to the machine (and don't guess why). iRCMS is fine.
2. **Headed needs a display.** The Bash env often lacks one; launch Chrome with env copied from a running
   GUI process: `DISPLAY=:1 WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/1000 XAUTHORITY=/run/user/1000/xauth_* DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus`.
3. **CAPTCHA is human, always.** Only "Get Record Detail" needs it; the cascade doesn't. When headed +
   user present, they solve it in the window. When remote, screenshot it to their WhatsApp (`wa/send-image-self.mjs`).
4. **Rebuild AnyRoR PDFs from the saved HTML, not the JSON.** The integrated page's mutation-entry cells
   contain nested sub-tables; flat text extraction turns them to gibberish. `render-anyror-offline.mjs`
   renders the real DOM (clean, faithful). The JSON is only for the Excel summary fields.
5. **VF-7/12 placeholder weeding.** Real old scans are images (no text layer). A "record dilapidated /
   not scanned" placeholder is a generated *text* PDF — so weed any VF-7/12 PDF with an extractable text
   layer, and re-check the grid's "PDF સ્ટેટસ" (which lies — says "Ok" even for placeholders).
6. **PDF bytes vs Chrome's viewer.** Chrome's built-in PDF/TIFF viewer eats the network response body.
   Capture bytes via `ctx.route(...)` + `route.fetch()` instead of `response.body()`.
7. **Mixed Gujarati+Latin text** in fpdf2: set `guj` as primary font + `noto` fallback (`set_fallback_fonts`),
   or Latin glyphs render as boxes.

## The Android app (now the main product)

Lives in `android/`. Build + release facts are in `CLAUDE.md`; the design source of truth is
`design_handoff_land_records_ui/`.

- **Shipping a public update is one command:** `tools/release/release.sh "<notes>"`
  (`--dry-run` to build and verify only). It builds slim (`-Pslim` drops the 125 MB personal
  seed), refuses to publish an APK containing any seed file, **verifies the signing certificate
  against the currently published APK**, then creates the release, rewrites `update.json` and
  prunes to the newest 3. See [`app-release-update-pipeline`] in memory for the why.
- **The signing key is load-bearing.** Every release is signed with `~/.android/debug.keystore`
  (pinned in `app/build.gradle.kts`). Android only allows an in-place update when the
  certificate matches — lose that keystore and **no existing install can ever update again**.
  Backup: `~/Desktop/projects/land-records-signing-key-BACKUP.jks`.
- Verifying a publish: `raw.githubusercontent.com` and `releases/latest/download` are
  **CDN-cached** and serve the previous release for minutes. Check via `gh api` instead.

## Planned

- **[One place identity](plans/2026-08-11-unified-place-identity.md)** — store a stable
  `place_id` instead of a place's *name*, so the same village can never become two cards.
  Deletes the dedupe machinery (`PlaceNames`, `PlaceRelocator`, per-source crosswalks)
  rather than adding to it. This is the highest-leverage cleanup on the list.
- **[WhatsApp problem reports](plans/2026-08-11-whatsapp-problem-reports.md)** — a report
  should arrive by itself instead of needing the user to compose an email.
- **Jantri: remaining 24 districts** (parsed and on disk; needs their crosswalks) and
  **town/urban rates** (a separate document set, filenames not yet discovered). See
  [the jantri spec](specs/2026-08-11-jantri-land-rates.md).

## Pending

**Registered deeds** (Integrated Details → Sub-registrar "View Deed" → TIFF → convert to PDF) for
**Valetva / Survey 41 (Kheda / Nadiad Gramya)** and **Sundalpura (Anand / Umreth): 906, 845/અ, 851, 901/p, 902.**
Blocked on the AnyRoR IP block. `anyror/deed-step1.mjs` (headed) reaches the detail page, dumps the
"View Deed" links, and keeps the browser open on CDP `:9222` for a downloader. The deed→TIFF capture
mechanism is not yet confirmed (recon needed on the live detail page, on a clean residential IP).

## Next-session checklist for the deeds

1. Confirm user is on a **fresh residential IP** (phone hotspot) and present.
2. Run `deed-step1.mjs` **headed** with the display env (above) — **one** load, no retries.
3. User solves the CAPTCHA in the window → reach `InfoSurveyNoDetail.aspx`.
4. Inspect the dumped deed links; trigger one "View Deed" and capture how the TIFF is served
   (`route.fetch` on the deed URL, or a download). Convert with `tiff2pdf`.
5. Save under `output/<TOKEN>/deeds/`, add to Excel/zip, share on request.
