# Land Records — Gujarat land-records toolkit + Android app

A calm, organized **library** for Gujarat land records (AnyRoR + iRCMS). The goal: stop dumping PDFs into
WhatsApp and give the family — including a non‑technical parent — a trustworthy home for the files, with a
one‑tap path to fetch new records. See **[`docs/APP_SPEC.md`](docs/APP_SPEC.md)** for the master spec.

## Repository map

| Path | What it is |
|---|---|
| **`android/`** | The native Android app (Kotlin + Jetpack Compose). Self‑contained Gradle project. **This is the product.** |
| **`docs/`** | Master spec, project notes, plan, and the design brief. Start with `docs/APP_SPEC.md`. |
| **`anyror/`** | Proven AnyRoR **WebView‑manipulation** logic — the cascade selection, VF‑7/12 fetch, deed recon, and the tuned PDF `format.mjs` CSS. The app's fetch engine ports from here. |
| **`src/`** | iRCMS scraper/store logic (`scrape.mjs`, `store.mjs`). |
| **`wa/`** | WhatsApp (Baileys) delivery — optional; being replaced by the app's share sheet. |
| **`*.mjs`, `*.py`** | The historical runners and PDF/Excel builders (`run-anyror.mjs`, `run-vf712.mjs`, `build_vf712_combined.py`, `build_reports.py`, …). Reference implementations for the on‑device port. |
| `survey-catalog.{json,csv}` | Full Bharoda survey list (used for the location/survey pickers). |
| `output/` | *(git‑ignored)* already‑generated PDFs + raw HTML sources — the **seed data** for the library. |

The proven automation is kept as the reference the app is built to match — the exact cascade IDs, the
placeholder‑weeding rule, the render‑from‑HTML approach, and the mixed Gujarati/Latin font handling all
carry over. See `docs/PROJECT_NOTES.md` for the hard‑won gotchas.

## The Android app (`android/`)

**Stack:** Kotlin · Jetpack Compose · embedded WebView (cookies + JS injection + print‑to‑PDF) · Room
(metadata/search) · files in a visible `Documents/LandRecords/…` tree · sideloaded debug APK.

**Build stack:** Gradle 9.5 · AGP 9.1.1 · Kotlin 2.3.21 · Room 2.8.4 · compileSdk 37 · minSdk 26.
(Kotlin is held at 2.3.x because Room's kapt processor can't yet read Kotlin 2.4 class metadata, and
no KSP build matches AGP 9.1.1's bundled Kotlin — see `android/gradle.properties`.)

```bash
cd android
./gradlew assembleDebug        # build the debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`android/local.properties` (git‑ignored) points at the SDK: `sdk.dir=/home/<you>/Android/Sdk`.

## Status

Design‑first per `docs/APP_SPEC.md` §11: the visual design is being produced as an interactive mockup
(see `docs/DESIGN_BRIEF_FOR_CLAUDE.md`), then translated into the Compose UI. The scaffold below is the
build foundation (data model, storage, navigation, screen shells) that the approved design fills in.
