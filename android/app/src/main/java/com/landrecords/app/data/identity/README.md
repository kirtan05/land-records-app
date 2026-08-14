# What is built, and what is not

Implementation of `docs/specs/2026-08-14-unified-db-and-autofetch-design.md`, 2026-08-14.

## Built and verified

| § | what | where | verified by |
|---|---|---|---|
| 1 | canonical tokenizer, place ids, uids, content hash | `data/identity/Identity.kt`, `src/identity.mjs` | 206 node checks + 14 JVM tests on one fixture |
| 1–3 | synced schema (16 tables), fixed content-hash column order | `data/sync/SyncSchema.kt`, `src/sync-schema.mjs` | column order asserted table-by-table across both languages |
| 3 | merge engine: LWW + content-hash short-circuit + tiebreak | `data/sync/MergeEngine.kt`, `src/merge.mjs` | 10 shared decision vectors + 10 JVM property tests |
| 3 | storage, export/import bundle, fingerprint | `data/sync/SyncDb.kt`, `src/sync-db.mjs` | 21 end-to-end checks on real SQLite |
| 2 | old-survey ranking + the 5-candidate rule | `data/sync/OldSurveyMatcher.kt`, `src/old-survey-match.mjs` | 6 shared cases + 6 JVM tests |
| 4 | content-addressed blob store + visible tree as projection | `data/storage/BlobStore.kt` | compiles; all four stores routed through it |
| 5 | `data-id` capture and dedupe | `web/IrcmsInjection.kt`, `ui/fetch/IrcmsFetchScreen.kt`, `data/storage/CasesStore.kt` | — |
| 5 | re-runnable migration over the live app database | `data/sync/LegacyMigration.kt` | — (see gaps) |
| 5 | `output/` converter, doubling as the idempotence test | `tools/identity/convert-output.mjs` | **idempotent on the real 182-dir corpus, 0 problems** |
| 6 | durable fetch queue, pacer/backoff, foreground service | `fetch/` | 6 JVM tests on the escalation curve |
| 6 | headless AnyRoR + iRCMS drivers, offscreen WebView host | `fetch/AnyRorDriver.kt`, `IrcmsDriver.kt`, `HeadlessWebView.kt` | compiles; not yet run live |
| 6 | filing into BOTH the visible tree and the synced tables | `fetch/FetchFiler.kt` | compiles |
| 2 | curation screen | `ui/fetch/OldSurveyLinksScreen.kt` | compiles; routed, no entry point |

The Room migration is **additive** (v2 → v3): the legacy `properties`/`surveys`/`records`
tables are untouched and keep driving the UI, so dad's existing data is not at risk from the
schema change itself.

## Not built — known gaps

1. **Nothing has been run against the live sites yet.** Every driver below compiles and every
   piece of pure logic is unit-tested, but `AnyRorDriver` and `IrcmsDriver` have not made a real
   request from this build. The first background run needs watching — in particular whether
   AnyRoR's own JS behaves under background timer throttling, which the 2026-08-14 render
   measurement did NOT cover (it used a static local page).

2. **`LegacyMigration` has never been run against a real device database.** Settings now has
   **"Check database migration (dry run)"** — run that on dad's phone and read the report
   (especially the provisional-place count) before **"Run database migration"**, which takes a
   backup first. Both are manual on purpose; nothing migrates on app start.

3. **The old-survey curation screen is routed but has no entry point.** `OldSurveyLinksScreen`
   is built and reachable via `Routes.oldLinks(surveyId)`; where its button sits on the survey
   card is a design decision, so it is deliberately not wired into `SurveyDetailScreen`.

4. **Auto-matching does not yet propose candidates.** `OldSurveyMatcher.plan()` implements the
   ranking and the 5-candidate rule and is tested, but no fetch path calls it yet, so
   `survey_link` only ever holds rows the user created.

5. **The human-spotlight fallback is UI-only.** When the CNN loses three captchas in a row the
   queue records a failure the user can retry from; the headless driver cannot show a spotlight,
   by definition. Retrying from the survey opens the existing on-screen flow.

6. **VF-7/12 multi-scan capture is not in the headless driver.** `AnyRorDriver` fetches the
   VF-7/12 page and files one PDF; the per-old-survey scan fan-out still lives in
   `Vf712FetchScreen`/`VfScansStore`.

## The rule that matters most

`tools/identity/probe-tokenizer.mjs` must print **0 fused**. It measures the tokenizer against
15,293 real AnyRoR dropdown values. A non-zero number means two different people's parcels
share a key — see `tools/identity/README.md` for how that was found and fixed.
