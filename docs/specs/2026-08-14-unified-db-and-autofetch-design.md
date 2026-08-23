# One database everywhere + background auto-fetch

**Status:** design approved in conversation 2026-08-14; not yet built.
**Supersedes/absorbs:** `docs/plans/2026-08-11-unified-place-identity.md` — that plan
argued a place must be an id, not a name. This spec accepts that and extends it to every
row in the system, because merging two databases needs identity for *everything*, not
just places.

---

## Why

Two machines scrape the same land records: this laptop (node scrapers → `output/`) and
the Android app (Room + a visible `Documents/LandRecords` tree). Today they share no
identity at all — every id is a local autoincrement or an implicit file path — so the
same survey fetched twice is two unrelated records with no way to know they are the same.
A Google Drive / S3 sync layer on top of that would multiply the data, not merge it.

The goal: **exporting either database and importing it into the other is a no-op when the
content is the same, and a clean union when it is not.**

---

## 1. Identity

### 1.1 Place — codes, never names

```
place_id = "gj:" + dist2 + ":" + tal2 + ":" + vil3        e.g. "gj:15:03:029"
```

These are the AnyRoR/iRCMS cascade codes — the same codes on both sites (verified:
`packages/core/scrape.mjs:33` POST params vs the `ContentPlaceHolder1_ddlVillage` option values).
Zero-padding is part of the identity: `"029" != "29"`.

Names become attributes, never keys:

```sql
place_name(place_id, script, source, name)
```

so `ભરોડા` (anyror), `Bharoda` (ircms `dtv`) and `BHARODA` (vf712) coexist. This is what
permanently kills the Nadiad bug — `નડિયાદ ગ્રામ્ય` and `નડીઆદ ગ્રામ્ય` become two rows
in `place_name` pointing at one `place_id`, instead of two competing keys.

**Provisional places.** Legacy data without codes (the 188 existing `output/` dirs, and
app rows whose crosswalk is ambiguous) gets a name-derived deterministic id:

```
place_id = "gj?:" + hex12(sha256("name|" + lower(district|taluka|village)))
```

Deterministic, so two machines importing the same legacy data still collide correctly;
visibly provisional, so a later `place_merge` row rewrites it to the real coded id once,
everywhere, by uid.

### 1.2 Survey — one tokenizer on both sides

The two implementations disagree today. Desktop `surveyToken` folds Gujarati digits and
`પ`→`p`; Room's `normalized` (`LandRecordsRepository.kt:246`) only uppercases and swaps
`/`→`_`, leaving `૮` as `૮`. So `૮૪૫/અ` tokenizes to two different strings and could never
merge. One canonical tokenizer ships to both:

```
"~~" → " " · Gujarati digits → ASCII · "પૈકી"/"પ" → "p" ·
"-" → "/" unless between digits · [\s/|\\]+ → "/" · trim "/" · "p/(?=\d)" → "p" ·
uppercase · "/" → "_" · transliterate [^A-Z0-9_+-] · collapse "_+" → "_" · trim "_"

survey_uid = place_id + "/" + token          e.g. "gj:15:03:029/221_P"
```

The last two rules change identity for real existing directories (`100_1_`, `101__3`) —
deliberately, they are the same survey as their collapsed forms. `survey_alias` preserves
every original raw form, including the exact `<option value>` string (`"226/p૧ ~~ "`,
trailing space and all) that `selectOption` needs verbatim.

> **CORRECTED 2026-08-14, during implementation.** This section originally ended with
> *"strip `[^A-Z0-9_]`"*. Measured against the 15,293 real dropdown values bundled in
> `assets/surveys/`, that **fused 250 different surveys onto shared tokens** — in Bharoda,
> all eight of `40/1/અ … 40/1ડ` collapsed onto `40_1`, and `1678` collapsed with `1678/અ`.
> Suffix letters are therefore **transliterated, not stripped** (`845/અ` → `845_A`), and
> `+` / `-` are **kept** (`364+365+366` is a combined survey, `690-696` a range). Result:
> 0 fusions across all 8 bundled villages. Rationale, the mapping table, and the one
> deliberate remaining split (`99` vs `99.`) are in `tools/identity/README.md`;
> `node tools/identity/probe-tokenizer.mjs` reproduces the measurement.

### 1.3 Everything below survey

```
uid(kind, parts…) = prefix + "_" + hex(sha256(kind + "\x1f" + parts.join("\x1f")))[0:24]
```

| kind | prefix | parts |
|---|---|---|
| record set | `rs` | survey_uid, record_type |
| iRCMS case | `ic` | survey_uid, "ircms", **data_id** |
| iRCMS order | `io` | case_uid, ordinal |
| VF-7/12 scan | `vs` | survey_uid, "vf712", period, thok, block, oldSurvey |
| VF-6 entry | `en` | survey_uid, "entry", entry_number |
| deed | `dd` | **place_id**, "deed", office, doc_year, doc_no |
| deed party | `dp` | deed_uid, party_type, party_name, ordinal |
| deed↔survey link | `dl` | deed_uid, survey_uid |
| blob | `bl` | sha256(bytes), untruncated |

Deliberately absent from every uid: timestamps, file paths, DOM row ordinals, `sr_no`,
`case_index`, VF-7/12 `selectIndex`. All of those vary between two scrapes of the same
real record. They are kept as payload columns, never as identity.

**A deed's identity is `office + doc_year + doc_no`, not the survey.** Confirmed live at
Bhalej 174/પૈકી1: one document (438 of 2022) produced three rows because it touches three
parties, and the same deed can touch several surveys. Parties and survey links hang off it.

---

## 2. Old survey numbers — user knowledge, not scraped data

VF-7/12 uses a different dropdown (`ddlOldScannedSno`) whose numbers do not map 1:1 onto
current survey numbers. Survey `174/p1` may correspond to `174`, `174/1`, `174/2`, … We
auto-match candidates, download what we can, and **the user decides which links to keep,
remove, or add**.

```sql
survey_link(uid, current_survey_uid, old_token, state, source, updated_at)
  state: candidate  -- proposed by auto-matching
         confirmed  -- user kept it
         rejected   -- user removed it
         manual     -- user added it, no auto-match involved
  uid = hash("sl", current_survey_uid, old_token)
```

Three rules:

1. **`rejected` must be stored.** Storing only what the user keeps means auto-matching
   re-proposes the same wrong candidates on every fetch, forever. A rejection is knowledge.
2. **The uid is the pair**, so the same decision made on the laptop and the phone is one
   row that merges to one row, and a rejection propagates to both.
3. **Scrapers never write this table.** Auto-matching may only insert `candidate` rows,
   and a `candidate` may never overwrite `confirmed`/`rejected`/`manual`.

This is the only data in the system that cannot be recovered by re-fetching, so it gets
the same isolation as `mark`. Same flow on desktop and app.

**How many candidates to fetch before asking.** Candidates are fetched *before* the user
curates, so he judges actual documents rather than bare numbers. But a wide match must not
silently become twenty captchas:

- ≤ 5 candidates → fetch all of them, then ask.
- \> 5 candidates → fetch the first 5, then ask before fetching the rest.

Either way he always has at least 5 real documents in front of him when he decides.
Ranking for "first 5": exact token match first, then nearest numerically.

On `rejected`, tombstone the **link**, not the blob. The bytes are already downloaded,
content-addressing makes keeping them nearly free, and if he changes his mind there is
nothing to re-fetch.

---

## 3. Merge semantics

```
content_hash(row) = sha256(join(canonical(col) for col in SYNCED_COLS[table], "\x1f"))
```

`SYNCED_COLS` excludes `uid`, `content_hash`, `updated_at`, `origin` and locally-derived
junk; it *includes* `deleted`. Column order is a fixed list, not SQLite's order, so both
implementations agree.

```
for each incoming row R:
    L = local row with same uid
    if L is null:                          INSERT R
    elif L.content_hash == R.content_hash: no-op          <- idempotence
    elif R.updated_at > L.updated_at:      UPDATE to R
    elif R.updated_at < L.updated_at:      keep L
    else:                                  keep greater content_hash   <- deterministic tiebreak
```

Last-write-wins with a content-hash short-circuit. These are scraped facts with a monotone
information gradient — a later scrape sees the same or newer state — so per-field CRDT
merging buys nothing. The short-circuit is what makes re-merging the same export a pure
no-op, and stops clock skew from flip-flopping identical content. Writers set
`updated_at = max(now, local_max + 1)` so a slow clock cannot lose all of a device's writes.

**User-authored fields are isolated.** `mark` and `survey_link` live outside the scraper
write path entirely; scrapers `UPDATE` only scraped columns. Otherwise a re-fetch silently
destroys a choice the user made.

**Deletions are tombstones**, never physical: `deleted = 1` with a fresh `updated_at`.
Physical deletes resurrect on the next merge. Parents tombstone children explicitly — no
SQL cascade. Blobs are never deleted by sync; local reachability GC handles them.

---

## 4. Files: content-addressed, visible tree is a projection

Blobs are stored by `sha256(bytes)`, so the same PDF fetched on both machines is one row
with zero conflict — this alone removes the biggest merge hazard today (the app's
`Integrated Record.pdf` and the desktop's `Bharoda_SurveyNo_221_P_ALL.pdf`: same bytes,
different names).

**`Documents/LandRecords/<District>/<Taluka>/<Village>/Survey <N>/…` stays exactly as it
is** — same folders, same filenames dad already knows. It becomes a projection over the
blob store, regenerable at any time. Nothing user-facing changes.

This also fixes a live data-loss risk: today every store (`CasesStore`, `VfScansStore`,
`EntriesStore`, `DeedsStore`) deletes its manifest and files then rewrites on re-fetch.
Under the new model a re-fetch tombstones *links*, not bytes, so merging with an older
database can never lose a scan that only one machine ever had.

---

## 5. Migration (the app is shipping, with real data on dad's phone)

One-time and breaking: every stored key changes once. Ordering:

1. **Back up first** — copy the Room DB and the visible tree before touching anything.
2. Re-derive every survey token with the canonical tokenizer; write `survey_alias` rows
   for the originals.
3. Resolve places to codes where the crosswalk is unambiguous; everything else becomes
   `gj?:` provisional and is resolved later without blocking.
4. Ingest existing files into the blob store by hash; rebuild the visible tree as a
   projection.
5. Verify: row counts, every existing PDF still reachable, no survey orphaned.

The migration must be **re-runnable** — running it twice produces the same database. If
anything is wrong, re-fetching is the ultimate safety net, but it must not be the plan.

Desktop side: the node scrapers are **rewritten to write SQLite directly**. A converter
over the existing `output/` tree is still needed for the current corpus, and doubles as
the idempotence test (running it twice must produce a byte-identical DB). The converter
alone is not enough — it cannot recover what the scrapers never wrote: cascade codes and
`data_id`.

**`data_id` must be captured by the app.** It is iRCMS's own case id, read off the row
button's `data-id` attribute (`packages/core/scrape.mjs:17`), and the desktop already dedupes on it
(`scrape.mjs:144`). The app currently keys cases on `caseNo|parties|office|dtv` — a string
built from display text that drifts with spacing and spelling. Until the app reads
`data-id`, app-scraped and desktop-scraped cases for the same survey will not collide and
will duplicate. One line of JS in `IrcmsInjection`.

---

## 6. Background auto-fetch

When a property is added, everything is fetched automatically: integrated + VF-6 entries +
deeds (one AnyRoR page yields all three), VF-7/12, and iRCMS cases.

**`FetchService`** — a foreground service hosting offscreen WebViews. Required: Android
kills a background process mid-fetch otherwise. The WebView is created against the service
context and laid out manually, so the existing injections work unchanged.

**`fetch_queue`** — one row per (survey × record_type), `pending → running → done/failed`,
uid = `hash("fq", survey_uid, record_type)`. Survives reboot and app death; resumes.

**Concurrency** — bounded by a hard constraint: `CookieManager` is process-global, so every
WebView on `anyror.gujarat.gov.in` shares one `ASP.NET_SessionId`, and the cascade is
WebForms `__VIEWSTATE`. Two concurrent AnyRoR cascades corrupt each other — a *correctness*
failure, not a rate-limit one. Therefore:

- AnyRoR cascades are **serialized**.
- The download fan-out **is** parallel (the app already does this: `Semaphore(4)` on entry
  images, `FetchScreen.kt:273`). This is where the time actually goes.
- **AnyRoR and iRCMS run concurrently** — separate sites, separate sessions. Already proven
  in `IrcmsBatchScreen`, which runs two WebViews at once.
- True parallel AnyRoR cascades would need separate processes
  (`WebView.setDataDirectorySuffix`). Not in v1.

**Backoff** — there is none anywhere in the app today. A shared pacer with exponential
backoff on any block/429, respected by every fetcher, and the queue parks rather than
hammering. AnyRoR's WAF IP-blocks bursts (see the memory note); mobile IPs are less
exposed, but the backoff is what makes that safe rather than lucky.

**CAPTCHA** — auto-solved by `CaptchaCnn` (pure Kotlin, 98.67% on-device, ~200 ms). On
rejection, retry up to 2× with a fresh captcha, then fall back to the human spotlight.
Confidence is **not** a usable gate — a wrong read at confidence 0.9665 was observed
on-device — but a wrong captcha is self-correcting: the site rejects it and we retry, so it
costs a round-trip, not data integrity.

**Status** — files appear in the library as they land, plus a per-survey status line on the
property (`fetching…` / `12 of 34` / `failed — retry`). Silent-on-failure was rejected: a
blocked fetch would otherwise look like a broken app.

### Backgrounding — MEASURED 2026-08-14, not assumed

**An offscreen WebView in a foreground service renders identically whether the app is
foreground, backgrounded, or the screen is off.** So the fetch survives the user switching
apps or pocketing the phone, and there is no need to defer PDF rendering.

Measured on SM-X730 (API 36), `WebView(serviceContext)` never attached to a window,
`layout(0, 0, 1080, 1920)`, fixed local asset page:

| state | print PDF bytes | non-white px |
|---|---|---|
| foreground (importance 100) | 43,474 | 33,466 |
| backgrounded via HOME (importance 125) | 43,474 | 33,466 |
| screen OFF | 43,474 | 33,466 |
| screen OFF, +15 s | 43,474 | 33,466 |

Byte- and pixel-identical in every state, via both the real `PrintPdf` path and the
`WebViewCapture` canvas paginator. Blankness was disproven by re-opening each PDF with
`PdfRenderer` and counting non-white pixels — file size alone is not evidence.

Method note: this ran as a **real foreground service in the app's own process**, not as an
instrumented test, because instrumentation gets elevated process priority and would give an
untrustworthy pass. Probe preserved on branch `wvbg-offscreen-render-probe` (`a61edeb`),
DO-NOT-MERGE — it declares an exported service and `release.sh` builds `assembleDebug`.

Two things this does NOT prove, still open:
- **Doze.** The probe held a partial wake lock, as a real fetch service would. Whether deep
  Doze eventually suspends the service is a separate question; the queue's resume-after-death
  design covers it either way.
- **AnyRoR's own JS under background timer throttling.** The probe used a static local page.
  WebForms postbacks are event-driven rather than timer-driven, so this should be fine, but
  it needs watching on the first real background run.

---

## 7. Honest flags

- Place codes must exist on both sides; the app has none today and the crosswalk refuses to
  guess on ambiguity. Expect a manual pass for a handful of villages. `gj?:` exists so that
  pass can happen later without blocking.
- The canonical tokenizer changes existing app tokens and desktop directory names. Aliases
  preserve the originals; nothing is lost, but every stored key changes once.
- `disposal_date` / `disposal_type` / `case_status` / `no_appellant` / `court_no` are
  corrupt at rest on the desktop (regex bleed, `packages/core/scrape.mjs:100-105`), in three places.
  Fix the scraper *and* re-derive on import, or the new schema inherits the bug with a
  nicer column type.
- Deed *files* are dead server-side ("Document Record Not Found"); deeds are metadata-only.
- AnyRoR `sections[].rows` stays opaque JSON. Typing those eight grids is the obvious next
  increment; doing it now would block the merge on eight parsers.
