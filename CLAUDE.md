# Building the Land Records UI

The approved design lives in `design_handoff_land_records_ui/`. Read
`design_handoff_land_records_ui/README.md` before writing any UI code — it is the
visual source of truth required by `docs/APP_SPEC.md` §11, and it names exact
colours, type sizes, radii, spacing, copy and flows.

Rules for this app's UI:
- Direction is "Cadastre" at **comfy** density. Ochre accent `#B4531B` (dark `#E58A55`),
  cool-green neutrals, **no elevation** — 1dp borders only.
- Every survey is drawn as a **parcel tile**: 1dp border, radius 12dp, plus a 1dp
  dashed inset at 5dp. Every survey/record set carries the 4-slot **stamp strip**
  (I · V · D · C), filled when held, hollow when missing.
- Survey numbers, counts and all-caps labels are **IBM Plex Mono**; headings and
  body are **Space Grotesk**; Gujarati falls back to **Noto Sans Gujarati** in every
  family (mixed strings box out otherwise).
- App chrome respects the **language setting** (gu | both | en) via `L(gu, en)`;
  land data is never translated — Gujarati numerals stay, with a Latin helper line.
- Never invent land data. Unknown metadata renders `—`.
- Both themes are first-class. All motion collapses under reduced-motion.
- **Both CAPTCHAs are auto-solved; the human spotlight is only the fallback.**
  iRCMS: deterministic SVG `<text>`-node parse. AnyRoR: a CNN over the captcha PNG
  (`tools/captcha/anyror_cnn_real.onnx`, 98.7% on a held-out test split, 14/14 accepted live
  on 2026-08-14) — see `tools/captcha/README.md`. The captcha is baked into the page as a
  data URI, so read `img#ContentPlaceHolder1_i_captcha_1`; never re-fetch it (that rotates it
  and invalidates the one on screen). After 2 rejections, fall back to the human spotlight:
  pre-fill and lock the cascade fields, dim the rest of the page, spotlight only the code
  box and Get Record Detail.
  (Until 2026-08-14 the AnyRoR captcha was human-only — the model did not exist yet.)

Drop-in starting points: `design_handoff_land_records_ui/compose/{Color,Type,Dimens}.kt`
and `strings-additions.xml`. These are implemented in `android/app/src/main/java/com/landrecords/app/ui/`
(theme/, components/, and one package per screen).

## Building the app (`android/`)

```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Build stack is pinned to this machine's cached toolchain — **do not bump blindly**:
- Gradle 9.5 · AGP 9.1.1 · **Kotlin 2.3.21** · Room 2.8.4 · compileSdk 37 · minSdk 26.
- AGP 9 ships built-in Kotlin but is incompatible with the `kapt` plugin (needed for
  Room) and no KSP build matches, so `android/gradle.properties` sets
  `android.builtInKotlin=false` + `android.newDsl=false` and we apply the classic
  `kotlin.android` + `kotlin.kapt` + `kotlin.plugin.compose` plugins.
- Kotlin is held at **2.3.21** (not 2.4.x): Room's kapt processor reads class metadata
  only up to 2.3.0; Kotlin 2.4 emits 2.4.0 and the build fails.
- `material-icons-extended` is pinned to **1.7.8** (its last release; it stopped
  publishing at compose-ui 1.11).
- Fonts are bundled in `res/font/` (Space Grotesk variable; IBM Plex Mono + Noto Sans
  Gujarati static). Noto is used as the fallback in every `FontFamily`.

Foundation that is NOT UI (leave working): `data/` (Room + repository, seeded with the
real Bharoda/Sundalpura/Valetva data), `data/storage/` (the visible `Documents/LandRecords`
tree), and `web/AnyRor.kt` (the fetch contract the WebView engine ports from `anyror/*.mjs`).

## Releasing (`tools/release/release.sh`)

```bash
tools/release/release.sh --dry-run "notes"   # build + verify, publish nothing
tools/release/release.sh "notes"             # build → verify → publish → prune
```

Bump `versionCode`/`versionName` in `app/build.gradle.kts` first. The script builds with
`-Pslim` (drops the 125 MB personal seed), refuses to publish an APK containing any seed
file, **compares the built APK's signing certificate against the published one**, then
creates the release, rewrites `update.json` and prunes to the newest 3.

- **Never bypass the signature check.** Every release is signed with
  `~/.android/debug.keystore` (pinned in `build.gradle.kts`). Android only permits an
  in-place update when the certificate matches — a mismatch strands every existing install
  (uninstall + data loss). Backup: `~/Desktop/projects/land-records-signing-key-BACKUP.jks`.
- Don't verify a publish through `raw.githubusercontent.com` or `releases/latest/download`
  — both are CDN-cached and serve the previous release for minutes. Use `gh api`.
- AGP packages incrementally, so the script removes the APK output dir first; otherwise a
  slim build over a seeded one leaves the old bytes as dead space (34 MB inside 162 MB).

## One database everywhere (`docs/specs/2026-08-14-unified-db-and-autofetch-design.md`)

The laptop and the app share an identity layer so the same record scraped twice is one row.
**Every rule is implemented twice — `src/identity.mjs` and `data/identity/Identity.kt` — and
both are held to one fixture, `tools/identity/vectors.json`.** Change a rule in one place and
you must change it in the other, add a vector, and run both:

```bash
node tools/identity/test.mjs          # tokenizer, uids, merge engine, §2 old-survey rules
node tools/identity/test-sync.mjs     # export/import round-trip on real SQLite
node tools/identity/convert-output.mjs --check   # converter idempotence over output/
node tools/identity/probe-tokenizer.mjs          # tokenizer vs 15,293 real survey values
cd android && ./gradlew :app:testDebugUnitTest   # the Kotlin half of the same fixture
```

- **Never strip characters out of a survey number.** The spec originally said to strip
  `[^A-Z0-9_]`; measured, that fused 250 different surveys (eight Bharoda parcels onto `40_1`).
  Gujarati letters are transliterated (`845/અ` → `845_A`) and `+`/`-` are kept. `probe-tokenizer`
  must print **0 fused** — treat any other number as a data-corruption bug.
- **Nothing that varies between two scrapes may enter a uid** — no timestamps, file paths, DOM
  ordinals, `sr_no`, `case_index`, `selectIndex`. Those are payload columns.
- **`mark` and `survey_link` are user-authored**: scrapers may never write them (auto-matching
  may only propose `survey_link` *candidates*). A rejection is knowledge — it is stored, so
  auto-matching cannot re-propose the same wrong link forever.
- **Deletions are tombstones**, never physical: a physical delete resurrects on the next merge.
- **Files are content-addressed** in `BlobStore`; `Documents/LandRecords/…` is a *projection*
  with the same names dad already knows. A re-fetch tombstones links, never bytes.

## Land data rules beyond the UI

- **Jantri rates** (`data/jantri/`, `tools/jantri/`) are the ASR-2011 government rate with
  the 15/04/2023 doubling applied — a **stamp-duty floor, never a market valuation**, and
  labelled that way. Read `data/jantri/README.md` before touching the parser; three source
  quirks in it each caused silent data loss.
- Unknown stays unknown: a survey number with no jantri entry renders nothing, and
  `area × rate` appears only when the area is actually known.
- Where a unit is customary rather than official (the vigha varies by region), print its
  basis beside the number (`16.4 vigha (@20 guntha)`).

Direction already decided, not yet built: `docs/plans/2026-08-11-unified-place-identity.md`
(a place is an id, not a name — deletes the dedupe machinery) and
`docs/plans/2026-08-11-whatsapp-problem-reports.md`.

## Village maps

Read `docs/MAPS.md` before any map work. The short version: village-map PDFs come from
eJamin (a **private** site — the AnyRoR politeness rules don't apply, crawl it wide), and
**all 881 Kheda + Anand sheets are rasterized exports** — no text layer, no parcel
geometry, no georeferencing, so survey-number search and adjoining detection are *not*
buildable from them. Only ~5% of sheets statewide are vector. The three
`2026-08-10-maps-*` docs predate that discovery and carry SUPERSEDED banners.

Shipped: `assets/maps/villages.json` (788 KB, 16,044 villages) + a map glyph on the
village card that opens the official sheet. Regenerate with
`node tools/ejamin/scrape-catalog.mjs && node tools/ejamin/build-app-catalog.mjs`.
Next steps: `docs/plans/2026-08-11-maps-future-possibilities.md`.
