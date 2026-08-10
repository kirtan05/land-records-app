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
- The CAPTCHA is always human. Pre-fill and lock the cascade fields, dim the rest
  of the page, spotlight only the code box and Get Record Detail.

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
