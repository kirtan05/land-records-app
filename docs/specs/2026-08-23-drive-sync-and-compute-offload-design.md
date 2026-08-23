# Drive sync + compute offload

**Status:** design approved in conversation 2026-08-23; not yet built.
**Builds on:** `docs/specs/2026-08-14-unified-db-and-autofetch-design.md` — that spec gave
every row a stable identity so two databases could merge. This one uses that identity to
put the two databases in the same place, and to move the scraping off dad's phone.

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
Drive manifest         ──hit──▶ download rows + blobs ──▶ done
        │ miss
Mark row PENDING locally, announce on ntfy
        ├─ laptop claims ──▶ it scrapes ──▶ appends to Drive ──▶ phone syncs ──▶ done
        └─ no claim within T ──▶ phone fetches headlessly, silently
                                 └─ fails ──▶ stays PENDING, re-announced next tick
```

`T` is a claim window, not a guess about whether the laptop is on. The laptop publishes
`claim` the moment it takes a job, so the phone distinguishes *nobody home* from *working
on it*. A laptop that dies mid-job lets the claim expire; the phone re-announces.

**The phone owns the queue.** A pending survey is a row in the phone's own database, not a
file in the cloud. ntfy and Drive only carry announcements and results. This is what makes
the whole thing self-healing: no queue to garbage-collect, no orphaned job files, and
message loss is survivable because the phone re-announces every still-`PENDING` row on each
sync tick. ntfy.sh's ~12h retention therefore does not bound anything.

`PENDING` rows sync upward in the ordinary db batches, so the laptop can also see dad's
outstanding queue in the merged database without any separate channel.

---

## 2. Two channels, one job each

```
Drive  = data      /db/<deviceId>/<lamport>.ndjson.gz   +   /blobs/<sha256>
ntfy   = control   job · claim · synced
```

Neither does the other's work. Drive never holds a job; ntfy never holds a record.

### 2.1 Drive layout

```
/db/<deviceId>/<lamport>.ndjson.gz    append-only change batches, never rewritten
/blobs/<sha256>                        content-addressed, immutable, write-if-absent
```

Batches are append-only and namespaced per device, so two machines writing at the same
moment can never clobber each other — reconciliation happens on read, through the existing
`MergeEngine`. `<lamport>` is a per-device monotonic counter, not a wall clock: it must be
comparable across devices whose clocks disagree.

Blobs are written if-absent. Re-uploading a PDF already in the store costs one HEAD.

### 2.2 Transport interface

S3 is a planned second backend, so no caller may know it is talking to Drive:

```kotlin
interface SyncRemote {
    suspend fun list(prefix: String): List<RemoteEntry>       // key, size, etag, mtime
    suspend fun get(key: String): ByteArray
    suspend fun put(key: String, bytes: ByteArray, ifAbsent: Boolean = false): Boolean
    suspend fun delete(key: String)
}
```

`DriveRemote` ships now. `S3Remote` later is one new file plus a settings row. The four
verbs above are the entire surface both backends must provide — chosen because S3 offers
exactly these and nothing richer, so Drive-only conveniences (folder ids, change feeds,
revisions) must never leak into the interface.

Per the identity rule that governs this codebase, **`SyncRemote` is implemented twice** —
`data/sync/remote/` in Kotlin and `packages/core/remote/` in Node — pointed at the same
layout and held to shared fixtures.

### 2.3 ntfy control channel

One topic, three message types:

| Message | Published by | Meaning |
|---|---|---|
| `job <surveyUid>` | phone | this survey is PENDING and unfetched |
| `claim <surveyUid> <deviceId> <expiry>` | laptop | taken; phone must not self-fetch |
| `synced <deviceId> <lamport>` | either | new batch available, pull now |

The laptop catches up with `?since=<lastSeen>` on startup. Messages are advisory in both
directions: every one of them is recoverable from state the phone already holds.

### 2.4 Credentials

Your Google account owns the sync folder and shares it with a service account. The app
authenticates as that service account — **dad never sees a sign-in screen**, and the files
count against your quota, not his.

The service-account key is baked into the APK. It is scoped to one folder containing
nothing but land records, and is revocable from Drive in one action. This is a deliberate
trade of credential secrecy for zero friction, consistent with the existing decision to
sign private releases with the debug keystore.

---

## 3. Store the minimum; derive the rest

**Drive stores what came off the wire and what a human authored. Everything else is a
function of those, computed on device.**

Measured against the current 496 MB `output/` corpus, `*_ALL*.pdf` merges alone are
**82.6 MB — 17%**, and every byte of it is a concatenation of PDFs sitting beside it.

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

The app is reinstalled from empty. Nothing migrates, because a full corpus is fetched on
the laptop and pushed to Drive before dad uninstalls.

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
`SupportSQLiteDatabase` handle that `SyncDb` already writes through, and all reads go through
hand-written queries in the repository.

> **Possible payoff, to be confirmed during implementation, not assumed.** Room's kapt
> processor is the sole reason this build holds Kotlin at 2.3.21 and sets
> `android.builtInKotlin=false` (CLAUDE.md). If no `@Entity`/`@Dao` remains, kapt may become
> unnecessary — which would unpin the toolchain. Do not attempt this as part of the same
> change: land the schema work first, verify, then evaluate the build change separately.
> The toolchain is pinned to this machine's cached artifacts and a blind bump breaks it.

Consequently `data/LandRecordsRepository.kt` (648 lines) is rewritten to read the synced
tables, and its four remaining consumers follow: `ui/library/LibraryViewModel.kt`,
`ui/survey/SurveyDetailScreen.kt`, `ui/survey/SurveyDetailViewModel.kt`,
`ui/marked/MarkColor.kt`. Its other consumers — `FetchFiler`, `FetchService`,
`Vf712Curation`, `FetchStatusScreen`, `VfScansScreen` — are either deleted or reduced to
queue-driven code in §5.

This is the largest single piece of work in the design and the one most likely to be
underestimated: the repository is the seam between the UI and storage, and every screen
that survives depends on it.

### 4.3 Retained

`data/identity/`, `MergeEngine`, `SyncDb`, `SyncSchema`, `BlobStore` — the merge layer this
design stands on. `tools/identity/vectors.json` continues to gate both implementations, and
every command in the CLAUDE.md verification list must still pass unchanged.

`fetch/`, `web/`, `captcha/` are retained but **demoted**: they are no longer reachable from
any screen, only from the queue.

Net: roughly 5,000 lines removed, ~900 added.

---

## 5. The phone's fallback

When no claim arrives, the phone fetches with the existing headless engine
(`HeadlessWebView`, `AnyRorDriver`, `CaptchaCnn`) and **shows dad nothing**. The human
captcha spotlight is not reachable — a survey the CNN cannot solve stays `PENDING` and is
retried, and you clear it from the laptop.

This is a real change in posture: the spotlight existed so a fetch could always complete.
It now cannot, and that is accepted, because a stuck survey is a delay while a captcha
screen in front of dad is a failure of the whole premise.

---

## 6. The laptop watcher

`packages/sync/watchd.mjs`:

1. Subscribe to ntfy; on startup replay `?since=<lastSeen>`.
2. On `job`, publish `claim`, then run the existing `run-anyror.mjs` / `run-vf712.mjs` path
   headlessly.
3. Convert through `packages/core/identity.mjs`; append one db batch and any new blobs.
4. Publish `synced`.

It uses your IP and the CNN solver, under the existing AnyRoR politeness pacing — the WAF
rules in `packages/anyror/` are unchanged and still apply. If the laptop is off, jobs simply
wait; §1 guarantees they are re-announced.

---

## 7. Cutover

1. Fetch the full corpus on the laptop.
2. Push one complete snapshot to Drive (batches + blobs, derived artifacts excluded per §3).
3. Dad uninstalls.
4. He installs the new APK. First launch is a pure download — no migration code exists to
   go wrong, because there is none.

Step 1 must complete before step 3. The uninstall is the point of no return: it destroys
anything on the phone that was never pushed, and there is no importer left to recover it.

---

## 8. Testing

- **Identity is unchanged and must stay so.** Every command in the CLAUDE.md verification
  list passes, `probe-tokenizer` still prints **0 fused**.
- **`SyncRemote` conformance** — one suite run against both `DriveRemote` and an in-memory
  fake, and later against `S3Remote` unmodified. This suite is what keeps the S3 door open.
- **Ladder tests** — hit at each level, and each fallback: laptop claims and succeeds;
  claims and dies (claim expiry); never claims (phone self-fetch); phone self-fetch fails
  (stays PENDING, re-announced).
- **Concurrent append** — laptop and phone write batches simultaneously; the merged result
  is identical on both, and re-importing is a no-op.
- **Derivation** — a survey downloaded from Drive with no `_ALL` blob produces the same
  merged PDF page count on device as the laptop's copy.
- **Message loss** — drop every ntfy message; the job still completes via re-announce.

---

## 9. Open, deliberately

- **Blob eviction on the phone.** ~410 MB syncs today and grows. Nothing here evicts blobs,
  so eventually the phone fills. Deferred until it is a real problem; the fix is bounded
  because blobs are content-addressed and re-downloadable.
- **Drive quota.** Files count against your account. Not a constraint at this size.
