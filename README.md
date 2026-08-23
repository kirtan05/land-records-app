# Land Records

A calm, organized library for Gujarat land records — and the Android app that keeps it
in a non-technical parent's hands.

Gujarat publishes land records through two government portals, [AnyRoR][anyror] and
[iRCMS][ircms]. Both are ASP.NET WebForms sites behind a CAPTCHA, and neither offers bulk
export. The family workflow before this project was ad-hoc: fetch a PDF, drop it in
WhatsApp, lose it in the scroll. This repository replaces that with a durable library —
records fetched once, identified canonically, stored in a visible folder tree, and
readable on a phone.

> **Scope note.** This is a personal family tool for records the family owns. It is not a
> general-purpose scraper: the runners are deliberately slow, headed, and single-session,
> and they abort on the first WAF block page rather than retrying.

[anyror]: https://anyror.gujarat.gov.in
[ircms]: https://ircms.gujarat.gov.in

---

## Contents

- [Repository layout](#repository-layout)
- [Quick start](#quick-start)
- [The two halves](#the-two-halves)
- [How a record becomes a PDF](#how-a-record-becomes-a-pdf)
- [Identity: one database everywhere](#identity-one-database-everywhere)
- [CAPTCHA solving](#captcha-solving)
- [Datasets and generated data](#datasets-and-generated-data)
- [Testing](#testing)
- [Releasing](#releasing)
- [Conventions](#conventions)

---

## Repository layout

A single npm workspace. The Android app and the Node/Python toolkit are one project:
they share the identity rules, the fetch contract, and the generated data assets.

```
land-records/
├── apps/
│   └── android/          Jetpack Compose app — the product
├── packages/
│   ├── core/             Identity, normalization, merge, sync schema  (mirrored in Kotlin)
│   ├── anyror/           AnyRoR scrape + PDF render reference implementation
│   ├── captcha/          Both CAPTCHA solvers + the live scrape runners
│   ├── jantri/           Jantri (ASR-2011) land-rate dataset pipeline
│   ├── maps/             eJamin village-map catalogue pipeline
│   ├── whatsapp/         Baileys delivery of finished PDFs
│   └── legacy/           Historical iRCMS runners and report builders (reference only)
├── tools/
│   ├── identity/         The cross-language test fixture and its harness
│   └── release/          Signed APK build + publish
├── data/
│   ├── catalog/          Survey catalogues and matched input lists
│   └── jantri/           Rate-dataset sources and crosswalk
├── assets/anyror-css/    Cached AnyRoR stylesheets, for offline re-rendering
├── design/               The approved visual design package
└── docs/                 Spec, project notes, plans and designs
```

| Package | What it is |
|---|---|
| [`apps/android`](apps/android) | Kotlin + Compose client. Room database, WebView fetch engine, and a visible `Documents/LandRecords/…` file tree. **This is the product.** |
| [`packages/core`](packages/core) | The canonical strings — survey tokenizer, place ids, uids, content hashes, merge engine. Every rule is implemented twice; this is the JavaScript half. |
| [`packages/anyror`](packages/anyror) | Playwright reference implementation of the AnyRoR cascade, plus `format.mjs`, the shared DOM-surgery and print-CSS layer every renderer calls. |
| [`packages/captcha`](packages/captcha) | The iRCMS SVG parse, the AnyRoR CNN, and the runners that scrape a whole village with them. |
| [`packages/jantri`](packages/jantri) | Government ASR-2011 rate PDFs → per-district CSVs → SQLite → a compact app asset. |
| [`packages/maps`](packages/maps) | Crawls the eJamin catalogue into the bundled `villages.json` used by the village-map glyph. |
| [`packages/whatsapp`](packages/whatsapp) | Sends finished PDFs to a family group. Being replaced by the app's share sheet. |
| [`packages/legacy`](packages/legacy) | The original root-level runners. Kept for provenance; superseded by `packages/captcha`. |

---

## Quick start

Requires **Node ≥ 20**, **Python 3**, a **JDK 17**, and the Android SDK.

```bash
git clone <repo> land-records && cd land-records
npm install
```

Verify the checkout is sound before changing anything:

```bash
npm run verify          # identity + sync tests, converter idempotence, tokenizer probe
npm run app:test        # the Kotlin half of the same fixture (41 tests)
```

Build and install the app:

```bash
npm run app:build
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

`apps/android/local.properties` (git-ignored) points at the SDK:
`sdk.dir=/home/<you>/Android/Sdk`.

### Paths are relative

No script hardcodes a home directory. Every runner resolves the repository root from its
own location via [`packages/core/repo-root.mjs`](packages/core/repo-root.mjs) (JS) or
[`tools/repo_root.py`](tools/repo_root.py) (Python), so a clone works from anywhere. Set
`IRMSC_ROOT` to override.

---

## The two halves

**The toolkit** (`packages/*`) is the reference implementation. It runs on a laptop
against a headed Chrome, proves out each fetch mechanism, and produces the generated data
assets the app ships with.

**The app** (`apps/android`) ports those mechanisms onto the phone. Its WebView engine
implements the same cascade element ids; its Kotlin identity layer implements the same
uid rules; its CNN is the same trained model exported to a flat weights blob.

They are one project because a rule changed in one half is a bug unless it changes in the
other. The shared fixture in `tools/identity/vectors.json` is what holds them together.

---

## How a record becomes a PDF

AnyRoR serves every record type from one WebForms page driven by a cascade of dropdowns:

```
record type → district → taluka → village → survey number → CAPTCHA → Get Record Detail
```

Record types live on `#ContentPlaceHolder1_drpLandRecord`:

| Value | Record | Notes |
|---|---|---|
| `8` | Integrated Survey Details | Also the source of **deeds** — there is no separate deed page |
| `11` | Old scanned VF-7/12 | Served as PDFs through `PDFView1.aspx` |
| `6` | Old VF-6 entries | Village-wide, addressed by entry number |

The fetch and the render are always **two passes**. A runner saves the raw detail HTML and
extracted JSON under `output/<Token>/`; a second, offline pass replays that HTML from a
`file://` URL and prints the PDF. Both passes call `applyCleanFormat()` from
[`packages/anyror/format.mjs`](packages/anyror/format.mjs), so live and offline output are
identical — and re-rendering never touches the government site.

```bash
node packages/anyror/render-anyror-offline.mjs          # re-render every saved survey
node packages/anyror/render-anyror-offline.mjs 221_P    # just one
```

Two mechanisms in that pipeline are load-bearing and easy to break:

- **VF-7/12 PDFs must be intercepted** with `ctx.route(/PDFView1\.aspx/)`. Navigate
  normally and Chrome's built-in PDF viewer consumes the response body.
- **`format.mjs` overrides bootstrap's `@media print { color: #000 !important }`.** Red
  entry numbers mark an old hand-written નોંધ that has a scan behind it; without the
  override they print black and the distinction is silently lost.

Full-village runs go through `packages/captcha`, which adds the automatic solver:

```bash
node packages/captcha/runners/run-village-ircms.mjs                              # whole village, resumable
node packages/captcha/runners/run-anyror-auto.mjs --n=5                          # 5 surveys, CNN-solved
node packages/captcha/runners/run-bhalej-full.mjs --surveys=174/p1,174/p3,239    # record + every entry scan
```

---

## Identity: one database everywhere

The laptop and the app share an identity layer, so the same record scraped twice is one
row rather than two. Design: [`docs/specs/2026-08-14-unified-db-and-autofetch-design.md`](docs/specs/2026-08-14-unified-db-and-autofetch-design.md).

```
uid(kind, ...parts) = kind + "_" + sha256(kind ␟ parts…)[:24]
```

Parts are joined by the unit separator `\x1f`, so `("a","bc")` and `("ab","c")` differ.

Four rules govern every change here:

1. **Nothing that varies between two scrapes may enter a uid.** No timestamps, file paths,
   DOM ordinals, `sr_no`, `case_index`, or `selectIndex`. Those are payload columns.
2. **Never strip characters out of a survey number.** The spec originally said to strip
   `[^A-Z0-9_]`; measured, that fused 250 different surveys — eight distinct Bharoda
   parcels collapsed onto `40_1`. Gujarati suffixes are transliterated (`845/અ` → `845_A`)
   and `+`/`-` are kept. `probe-tokenizer` must print **0 fused**; anything else is a
   data-corruption bug.
3. **`mark` and `survey_link` are user-authored.** Scrapers may never write them.
   Auto-matching may only propose `survey_link` rows with `state='candidate'`, and a
   rejection is stored — so it can never re-propose the same wrong link forever.
4. **Deletions are tombstones**, never physical. A physical delete resurrects on the next
   merge.

Every rule is implemented twice — [`packages/core/identity.mjs`](packages/core/identity.mjs)
and `apps/android/.../data/identity/Identity.kt` — and both halves are held to one fixture,
[`tools/identity/vectors.json`](tools/identity/vectors.json). **Change a rule in one place
and you must change it in the other, add a vector, and run both suites.**

---

## CAPTCHA solving

Both portals are solved automatically. The human fallback is the exception, not the path.

| | iRCMS | AnyRoR |
|---|---|---|
| Delivery | SVG with the answer in `<text>` nodes | 190×80 PNG baked into the page as a data URI |
| Method | Deterministic parse — sort nodes by `x`, join | 6-head × 10-class CNN, trained on real hand-tagged captchas |
| Accuracy | Exact, no OCR | **98.69%** on the held-out test split |
| Fallback | — | After 2 rejections, hand off to the human spotlight |

The AnyRoR model ships twice: `anyror_cnn_real.onnx` for the laptop, and a BN-folded flat
float32 blob `anyror_cnn_real.weights` for the app's pure-Kotlin forward pass — no ONNX
Runtime and no native libraries on the device.

The model is trained **only on real captchas harvested from the live site and tagged by
hand** — 2,701 of them. A synthetic-lookalike generator was tried first and scored ~14% on
real input; it was abandoned and deleted. The five pipeline stages are
`fetch → tag → train → eval → export`:

```bash
cd packages/captcha && unzip samples.zip          # the tagged corpus is not in git
.venv/bin/python pipeline/infer.py --dir samples/anyror --eval --split test
```

Two constraints that cost real data when violated:

- **Never re-fetch the AnyRoR captcha image.** It is already in the DOM as a data URI;
  read `img#ContentPlaceHolder1_i_captcha_1`. Re-fetching rotates the code server-side and
  invalidates the one on screen.
- **One captcha, one round.** Both sites regenerate the code on every postback and on
  every failure. Never solve twice against the same page state.

Both sites WAF-block headless Chrome, so every runner drives a headed persistent profile
with jittered pacing and aborts on the first FortiWeb block page.

---

## Datasets and generated data

Bulk data is **not** committed. Everything below is regenerable, and the captcha training
set is distributed as a zip rather than in git history.

| Data | Size | How to get it |
|---|---|---|
| Captcha samples (2,701 hand-tagged) | 9.6 MB zipped | `packages/captcha/samples.zip` — `unzip` it in place. **Not regenerable**: the tagging is manual. |
| Jantri rate PDFs | ~141 MB | `packages/jantri/fetch.sh` |
| Jantri parsed CSVs + SQLite | — | `python3 packages/jantri/parse.py && python3 packages/jantri/build_db.py` |
| eJamin map catalogue | 15 MB | `node packages/maps/scrape-catalog.mjs` |
| App village asset | 788 KB | `node packages/maps/build-app-catalog.mjs` |
| Personal record seed | 125 MB | Never in git. Ships in the private first-install APK only. |

**Jantri rates are a stamp-duty floor, never a market valuation**, and are labelled that
way everywhere they appear. They are the ASR-2011 government rate with the 15/04/2023
doubling applied — the `*_2023` columns are derived here as `2011 × 2`, not read from any
PDF, because the 2023 notification is an untextual scan. Read
[`data/jantri/README.md`](data/jantri/README.md) before touching the parser; three source
quirks in it each caused silent data loss.

Village maps are rasterized exports — all 881 Kheda + Anand sheets have no text layer, no
parcel geometry and no georeferencing, so survey-number search and adjoining detection are
**not** buildable from them. See [`docs/MAPS.md`](docs/MAPS.md).

---

## Testing

```bash
npm run verify        # everything below, in order
```

| Command | What it holds |
|---|---|
| `npm run test:identity` | Tokenizer, uids, merge engine, old-survey rules — 206 checks |
| `npm run test:sync` | Export/import round-trip on real SQLite — 21 checks, 16 tables |
| `npm run test:maps` | eJamin geometry and catalogue helpers |
| `npm run check:convert` | Converter idempotence over `output/` |
| `npm run check:tokenizer` | Tokenizer against 15,293 real survey values — must print **0 fused** |
| `npm run app:test` | The Kotlin half of the shared fixture — 41 tests |

Known failing: one pre-existing assertion in `packages/maps/test/geo.test.mjs`
(`pageToLatLng`, expects `[4,3]`, gets `[3,3]`). Tracked in [`TODO.md`](TODO.md).

---

## Releasing

```bash
tools/release/release.sh --dry-run "notes"   # build + verify, publish nothing
tools/release/release.sh "notes"             # build → verify → publish → prune
```

Bump `versionCode`/`versionName` in `apps/android/app/build.gradle.kts` first. The script
builds with `-Pslim` (which drops the personal seed), refuses to publish an APK containing
any seed file, and **compares the built APK's signing certificate against the published
one**.

> **Never bypass the signature check.** Android only permits an in-place update when the
> certificate matches. A mismatch strands every existing install — uninstall and data
> loss, with no recovery path.

Don't verify a publish through `raw.githubusercontent.com` or `releases/latest/download`;
both are CDN-cached and serve the previous release for minutes. Use `gh api`.

---

## Conventions

- **Never invent land data.** Unknown metadata renders `—`, and `area × rate` appears only
  when the area is actually known.
- **Land data is never translated.** App chrome respects the language setting
  (`gu | both | en`); Gujarati numerals in the records stay, with a Latin helper line.
- **Where a unit is customary rather than official**, print its basis beside the number —
  the vigha varies by region, so `16.4 vigha (@20 guntha)`.
- **The design package is the visual source of truth.** Read
  [`design/README.md`](design/README.md) before writing UI code. Do not port its prototype
  HTML — rebuild in Compose against the listed tokens.
- **The Android toolchain is pinned to a verified combination.** Kotlin is held at 2.3.21
  because Room's kapt processor only reads class metadata to 2.3.0. Do not bump blindly;
  see [`CLAUDE.md`](CLAUDE.md).

Working agreements for agents and contributors live in [`CLAUDE.md`](CLAUDE.md). Open work
is in [`TODO.md`](TODO.md).
