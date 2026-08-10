# Plan — one place identity across every source (kill dedupe at the root)

**Status:** proposed, not started. **Why now:** we have paid for this three times.

---

## The problem

A place is currently identified by **its name as text**, and every source spells it
differently:

| Source | District | Taluka | Village |
|---|---|---|---|
| AnyRoR cascade | `આણંદ` | `ઉમરેઠ` | `ભરોડા` |
| iRCMS cascade | `ANAND` (code 15) | `Umreth` (03) | `Bharoda` (code) |
| Jantri PDFs | `ANAND` | `UMRETH` | `BHARODA` |
| Village maps | `Anand` | `Umreth` | `Bharoda` |
| The app's own rows | either script, depending on how it was added | | |

Because the name **is** the key, the same real village becomes two library cards, two
folder trees, and two sets of documents. We have already built three separate mechanisms
to paper over this:

- `PlaceNames.canonical` — cross-script resolution, deliberately refusing to guess.
- `PlaceRelocator` — physically **moves two directory trees** to merge two spellings.
- `build_crosswalk.py` — a 1:1 assignment solver for jantri ↔ iRCMS.

Each is correct. Together they are a standing tax: every new source needs another one,
and every one of them can be wrong in a way that corrupts real land records.

## The fix

**Give a place a stable ID and store that. Names become presentation only.**

The iRCMS cascade already provides the identifier, and it is the same code space AnyRoR
uses (verified in `assets/cascade/districts.json`):

```
place_id = "GJ-<district>-<taluka>-<village>"      e.g. GJ-15-03-041
```

Taluka and village codes are only unique **within their parent**, so the triple is the
key — never the village code alone. (This exact mistake cost a debugging round in the
jantri crosswalk: grouping by village code merged unrelated villages.)

### Target shape

```
places(place_id PK, district_code, taluka_code, village_code,
       district_en, district_gu, taluka_en, taluka_gu, village_en, village_gu,
       lgd_code NULL)                     -- one row per real village, shipped as an asset

properties(id, place_id -> places, …)     -- no name columns at all
```

Every source then attaches to `place_id` instead of re-deriving identity:

- **jantri** — `villages.place_id` (the crosswalk already resolves iRCMS codes; it stops
  being a runtime concern and becomes a build-time column)
- **village maps** — keyed by `place_id`
- **iRCMS / AnyRoR fetches** — the cascade selection *is* the id; stop round-tripping
  through a display name
- **storage tree** — still human-browsable (`Anand/Umreth/Bharoda`), but the DB row owns
  the id, so renaming a folder can never split a record again

### Why this removes dedupe rather than improving it

Dedupe exists because identity is only recoverable *after* the fact, by comparing names.
Resolution moves to the **one point where the user picks a place** — the cascade, where
the code is right there. Two spellings can no longer produce two rows, so there is
nothing to merge later. `PlaceNames`/`PlaceRelocator` shrink to a one-time migration and
then get deleted.

## Migration (existing installs hold real records — this must not lose any)

1. Ship `places` as an asset (all 33 districts from the iRCMS cascade; `fetch_cascade.py`
   already does this, currently used for 3 districts).
2. Add `place_id` to `properties`, nullable. Room migration, no data touched.
3. On first launch, resolve every existing row via `PlaceNames.canonical`. It refuses to
   guess, which is what we want: **unresolved rows keep working on names** and are
   reported, rather than being merged on a hunch.
4. Merge rows that resolve to the same `place_id` — reusing `PlaceRelocator` for the file
   trees, once.
5. Only once the field is populated for everything, make it non-null and drop the name
   columns.

## Payoffs beyond dedupe

- Shipping the other 24 jantri districts becomes a build-time join, not 24 crosswalks.
- A village's maps, rates, cases and records finally share one key, so "everything about
  this village" is one query.
- Cross-source disagreements become visible instead of silent: two sources pointing at
  the same `place_id` with different names is a data-quality signal we can surface.

## Risks

- **Merging two genuinely different villages corrupts land records.** Every step must
  keep `canonical`'s "don't know beats a guess" rule. Prefer leaving a row unmigrated.
- iRCMS codes are ours to depend on only while iRCMS keeps them stable. LGD codes are the
  more durable public identifier; carry `lgd_code` alongside from the start so we can
  re-base later without another migration.
- The 2013 district reorganisation means historical documents (jantri, old cases) may name
  a *former* parent district. `places` should record the ASR-2011-era parent so those
  joins stay possible.
