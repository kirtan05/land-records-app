# R2 sync + compute offload

**Status:** design approved in conversation 2026-08-23; not yet built.
**Builds on:** `docs/specs/2026-08-14-unified-db-and-autofetch-design.md` — that spec gave
every row a stable identity so two databases could merge. This one uses that identity to
put the two databases in the same place, and to move the scraping off dad's phone.
**Prior art to copy, not reinvent:** `~/Desktop/projects/spine` — a deployed Worker on the
same zone with D1, auth, cron, a canary route, and 57 tests against real D1 in workerd.

---

## Why

Three problems, one design:

1. **There is no sync.** The laptop scrapes into `output/`, the app scrapes into Room. The
   identity layer can merge them, but nothing carries the bytes between the machines.
2. **Dad sees AnyRoR.** Adding a survey opens a WebView, a cascade, sometimes a captcha.
   He should tap a survey number and get a record. The site is an implementation detail.
3. **The app carries its own history.** Two Room migrations, a legacy-ingest pass, and a
   legacy entity layer that duplicates the synced schema. All of it exists to preserve data
   that is about to be re-fetched from scratch anyway.

The target: dad's phone is a **reader with a queue**. It holds the library, it accepts a
survey number, and it displays one of three words — Ready, Getting it, Waiting. Everything
else happens somewhere he cannot see.

---

## 1. The resolution ladder

One function answers *"get me survey X"*. Everything in this document exists to serve it.

```
Library (local Room)   ──hit──▶ done, offline, instant
        │ miss
R2, via the D1 manifest ─hit──▶ download rows + blobs ──▶ done
        │ miss
POST /land/jobs — row goes PENDING both locally and in D1
        ├─ laptop claims ──▶ scrapes ──▶ uploads to R2 ──▶ phone syncs ──▶ done
        └─ unclaimed after T ──▶ phone fetches headlessly, silently
                                 └─ fails ──▶ stays PENDING, retried
```

`T` is a claim window, not a guess about whether the laptop is on. The claim is a row in
D1, so the phone distinguishes *nobody home* from *working on it* by reading state rather
than by inferring from silence.

**The queue is a table, not a message.** This is the one structural change from the earlier
Drive/ntfy sketch, and it removes a race: with pub/sub, two announcements could both look
unclaimed, and a claim was advisory. Here the claim is an atomic conditional update
(§2.3), so exactly one worker can hold a job. It also makes the queue *enumerable* — you
can ask the laptop what is outstanding, and a stuck job is queryable instead of invisible.

The phone still holds its own `PENDING` rows and retries independently, so a Worker outage
degrades to "no offload today", never to lost work.

---

## 2. Three pieces, one job each

```
R2                       = data      /blobs/<sha256>  +  /db/<deviceId>/<lamport>.ndjson.gz
Worker (kirtanjain.com)  = control   jobs, claims, manifest, device auth
D1                       = state     the queue, its history, and what R2 holds
```

### 2.1 Why R2

Measured against the current corpus (496 MB, less the ~85 MB §3 excludes as derived):

| | Free tier | This app |
|---|---|---|
| Storage | 10 GB-month | ~410 MB — **4%**, 24× headroom |
| Class A (PUT, LIST) | 1M/month | ~1,150 one-time, then per-survey |
| Class B (GET) | 10M/month | ~1,150 for a full phone restore |
| Egress | **free** | a full 410 MB restore costs nothing |

Free egress is the decisive property, not the storage allowance. Every full restore to
dad's phone moves the whole corpus; on metered object storage that is a recurring charge,
here it is structurally zero. The cutover in §7 — uninstall, reinstall, download everything
— stays free to repeat as often as needed.

**Never call R2 `LIST`.** It is a Class A op and the only cost here that would scale with
*sync frequency* rather than with new data. D1 already knows what exists, so R2 is
addressed purely by key: GET and PUT, never enumeration. This is a capability the earlier
Drive design did not have, because it had no control plane to hold a manifest.

### 2.2 Transport interface

R2 is reached over its S3-compatible API, so no caller knows which bucket vendor it is:

```kotlin
interface SyncRemote {
    suspend fun get(key: String): ByteArray
    suspend fun put(key: String, bytes: ByteArray, ifAbsent: Boolean = false): Boolean
    suspend fun delete(key: String)
}
```

`list` is deliberately **absent** — see §2.1. Restoring it would silently reintroduce the
one unbounded cost in the design, so its absence is load-bearing, not an oversight.

Per the identity rule that governs this codebase, `SyncRemote` is implemented twice —
`data/sync/remote/` in Kotlin, `packages/core/remote/` in Node — pointed at one bucket and
held to shared fixtures.

Blobs are written if-absent: re-uploading a PDF already in the store costs one HEAD.
Batches are append-only and namespaced per device, so two machines writing at the same
moment cannot clobber each other; reconciliation happens on read through the existing
`MergeEngine`. `<lamport>` is a per-device monotonic counter, never a wall clock — the two
machines' clocks disagree and the ordering must survive that.

### 2.3 The Worker

Routes added to the existing `kirtanjain.com` zone, under a prefix that cannot collide with
spine's `/users/*`, `/syncs/*`, `/api/*`:

| Route | Who | Does |
|---|---|---|
| `POST /land/jobs` | phone | enqueue a survey; idempotent on `survey_uid` |
| `POST /land/jobs/claim` | laptop | atomically take the oldest unclaimed job |
| `POST /land/jobs/:id/done` | laptop | mark fetched, record the batch lamport |
| `GET /land/manifest?since=` | both | what R2 holds — the thing that replaces LIST |
| `GET /land/jobs?state=` | you | inspect the queue |

The claim is one statement, and its atomicity is the whole point:

```sql
UPDATE job SET claimed_by = ?1, claim_expires = ?2
 WHERE id = (SELECT id FROM job
              WHERE state = 'pending'
                AND (claimed_by IS NULL OR claim_expires < ?3)
              ORDER BY created_at LIMIT 1)
 RETURNING id, survey_uid;
```

An expired claim is reclaimable, so a laptop that dies mid-job releases its work by
timeout rather than by anyone noticing.

R2 is bound directly to the Worker, but **bulk bytes do not flow through it** — the Worker
issues presigned URLs and the phone and laptop talk to R2 directly. Proxying 410 MB through
a Worker would burn request time and CPU for no benefit.

### 2.4 Auth

Copy spine's stored-credential pattern — HMAC-SHA-256 with an `AUTH_PEPPER` secret, plus
`timingSafeEqualHex`, and compute the HMAC *before* the row lookup so an unknown device
costs the same as a wrong key. That shape is already reasoned through and tested.

**Do not copy spine's wire format.** Its `X-AUTH-USER`/`X-AUTH-KEY` unsalted MD5 is
inherited from the kosync protocol, not chosen — nothing constrains us here, so this uses a
random per-device bearer token.

Per-device tokens replace what would otherwise have been one shared cloud credential baked
into the APK. A token is revocable individually, scoped to this API alone, and grants no
standing access to any storage bucket — the only R2 reach it confers is a presigned URL for
a specific key. This is the single biggest security improvement over the Drive design,
which required a storage credential with folder-wide read/write to sit inside the APK.

The token still ships in the APK (no pairing step, by decision). Its blast radius is one
revocable row.

---

## 3. Store the minimum; derive the rest

**R2 stores what came off the wire and what a human authored. Everything else is a function
of those, computed on device.**

Measured: `*_ALL*.pdf` merges alone are **82.6 MB of 496 MB — 17%** — and every byte is a
concatenation of PDFs sitting beside it.

| Synced | Derived on device |
|---|---|
| Source PDFs | `*_ALL*.pdf` / `*_ALL_CASES.pdf` merges (`web/PdfMerge.kt`) |
| `entry_*.png` scans — the only copy, nothing derives them | `.render_*.html` scaffolds |
| Captured `anyror_*.html` — the page a parse came from | The `Documents/LandRecords` tree — already a projection |
| Parsed rows (~10 MB, the actual record data) | Search index, stamp-strip state |
| Marks, survey links, tombstones (user-authored) | Jantri `area × rate` |

Beyond space, this removes a class of sync bug: **a derived artifact has no stable
identity.** Regenerate a merged PDF and its sha256 moves, so it would churn the blob store
forever while meaning nothing. Only immutable source bytes are content-addressed — which is
what `BlobStore` was built for.

`web/PdfMerge.kt` is therefore retained even though the fetch UI that called it is deleted.

---

## 4. The wipe

The app is reinstalled from empty. Nothing migrates, because a full corpus is fetched on the
laptop and uploaded before dad uninstalls.

### 4.1 Deleted

| Path | Lines | Why |
|---|---|---|
| `data/sync/LegacyMigration.kt` | 377 | migrates data that will not exist |
| `AppDatabase.MIGRATION_1_2`, `MIGRATION_2_3` | — | no v1 or v2 database will ever be opened |
| `ui/fetch/` (12 screens) | ~4,600 | dad must never reach AnyRoR or iRCMS |
| `data/db/Entities.kt`, `Daos.kt` (legacy trio) | ~225 | the second schema — see §4.2 |

### 4.2 One schema, not two

The app currently carries **two** schemas: the legacy Room entities
(`PropertyEntity` / `SurveyEntity` / `RecordEntity`) and the synced tables from
`SyncSchema`. "One unified database" means the synced schema becomes the only schema.

`AppDatabase` restarts at **version 1** and creates the synced tables through
`SyncDb.createTables`. Destructive fallback is set, because on a fresh install there is
nothing to destroy.

The synced tables are raw SQL, not Room entities, so the entity/DAO layer has nothing left
to describe — and a Room `@Database` with an empty `entities` list does not compile. Room is
therefore demoted to **the SQLite open helper only**: it keeps the connection, threading and
`SupportSQLiteDatabase` handle that `SyncDb` already writes through, and all reads go
through hand-written queries in the repository.

Consequently `data/LandRecordsRepository.kt` (648 lines) is rewritten against the synced
tables, and its four surviving consumers follow: `ui/library/LibraryViewModel.kt`,
`ui/survey/SurveyDetailScreen.kt`, `ui/survey/SurveyDetailViewModel.kt`,
`ui/marked/MarkColor.kt`. Its other consumers — `FetchFiler`, `FetchService`,
`Vf712Curation`, `FetchStatusScreen`, `VfScansScreen` — are either deleted or reduced to
queue-driven code in §5.

This is the largest single piece of work in the design and the one most likely to be
underestimated: the repository is the seam between the UI and storage, and every surviving
screen depends on it.

> **Possible payoff, to be confirmed during implementation, not assumed.** Room's kapt
> processor is the sole reason this build holds Kotlin at 2.3.21 and sets
> `android.builtInKotlin=false` (CLAUDE.md). If no `@Entity`/`@Dao` remains, kapt may become
> unnecessary — which would unpin the toolchain. Do not attempt this in the same change:
> land the schema work, verify, then evaluate the build change separately. The toolchain is
> pinned to this machine's cached artifacts and a blind bump breaks it.

### 4.3 Retained

`data/identity/`, `MergeEngine`, `SyncDb`, `SyncSchema`, `BlobStore` — the merge layer this
design stands on. `tools/identity/vectors.json` continues to gate both implementations, and
every command in the CLAUDE.md verification list must still pass unchanged.

`fetch/`, `web/`, `captcha/` are retained but **demoted**: no longer reachable from any
screen, only from the queue.

Net: roughly 5,000 lines removed from the app, ~900 added, plus a new Worker.

---

## 5. The phone's fallback

When no claim appears within `T`, the phone fetches with the existing headless engine
(`HeadlessWebView`, `AnyRorDriver`, `CaptchaCnn`) and **shows dad nothing**. The human
captcha spotlight is not reachable — a survey the CNN cannot solve stays `PENDING` and is
retried, and you clear it from the laptop.

This is a real change in posture: the spotlight existed so a fetch could always complete. It
now cannot, and that is accepted, because a stuck survey is a delay while a captcha screen
in front of dad is a failure of the whole premise.

---

## 6. The laptop watcher

`packages/sync/watchd.mjs`:

1. Poll `POST /land/jobs/claim`; back off when it returns nothing.
2. On a claim, run the existing `run-anyror.mjs` / `run-vf712.mjs` path headlessly.
3. Convert through `packages/core/identity.mjs`; upload new blobs and one db batch to R2.
4. `POST /land/jobs/:id/done` with the batch lamport.

It uses your IP and the CNN solver, under the existing AnyRoR politeness pacing — the WAF
rules in `packages/anyror/` are unchanged and still apply. If the laptop is off, jobs wait;
§1 guarantees the phone proceeds without it.

---

## 7. Cutover

1. Fetch the full corpus on the laptop.
2. Upload one complete snapshot to R2 (blobs + batches, derived artifacts excluded per §3).
3. Verify the manifest — a device restore must resolve every blob it names.
4. Dad uninstalls.
5. He installs the new APK. First launch is a pure download — no migration code exists to go
   wrong, because there is none.

Steps 1–3 must complete before step 4. **The uninstall is the point of no return:** it
destroys anything on the phone never uploaded, and there is no importer left to recover it.
Step 3 exists because free egress makes verification cheap — re-download and check, rather
than trust.

Carry over spine's canary practice (`/spine-canary/*`): prove the new routes serve correctly
on the zone before any device is pointed at them. Dad's records now depend on infrastructure
you deploy, so a bad deploy is a failure mode the Drive design did not have.

---

## 8. Testing

- **Identity is unchanged and must stay so.** Every command in the CLAUDE.md verification
  list passes; `probe-tokenizer` still prints **0 fused**.
- **Worker tests against real D1 in workerd**, following spine's harness.
- **Claim atomicity** — N concurrent claimers, exactly one wins; an expired claim is
  reclaimable; a completed job is never re-served.
- **`SyncRemote` conformance** — one suite against R2 and an in-memory fake. It must contain
  no `list`.
- **Ladder tests** — hit at each level, and each fallback: laptop claims and succeeds;
  claims and dies (expiry); never claims (phone self-fetch); phone self-fetch fails (stays
  PENDING).
- **Concurrent append** — laptop and phone write batches simultaneously; the merged result is
  identical on both, and re-importing is a no-op.
- **Derivation** — a survey restored from R2 with no `_ALL` blob produces the same merged PDF
  page count on device as the laptop's copy.
- **Worker outage** — with every `/land/*` route failing, the phone still fetches and still
  serves its library.

---

## 9. Open, deliberately

- **Blob eviction on the phone.** ~410 MB syncs today and grows; nothing here evicts, so the
  phone eventually fills. Deferred until real; the fix is bounded because blobs are
  content-addressed and re-downloadable at zero egress cost.
- **Workers plan.** Free is 100k requests/day, ample for a job queue, but spine's cron and D1
  use may already put the zone on Paid. Confirm on the dashboard; it changes nothing here.
- **R2 growth.** 4% of free storage today. At 24× the current corpus this needs revisiting.
