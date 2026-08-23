# The identity layer

Implements §1 of `docs/specs/2026-08-14-unified-db-and-autofetch-design.md`: the strings that
let two machines recognise that they scraped the same real record.

Three files, one contract:

| file | what |
|---|---|
| `packages/core/identity.mjs` | desktop implementation (node scrapers) |
| `apps/android/app/src/main/java/com/landrecords/app/data/identity/Identity.kt` | app implementation (pure Kotlin, no Android deps) |
| `tools/identity/vectors.json` | **the fixture both are held to** |

```bash
node tools/identity/test.mjs                      # desktop half
cd apps/android && ./gradlew :app:testDebugUnitTest    # app half
```

Neither test keeps its own copy of the expectations — that is the whole point. If the two
implementations drift, one of these goes red now, instead of the two databases silently
failing to merge months later. Add a vector **first**, then make both sides pass it.

`vectors.json` is declared as a Gradle test input, so editing only the fixture still re-runs
the Kotlin test. (Verified by breaking a vector and watching it fail.)

---

## Two deliberate deviations from the spec as written

The spec's §1.2 tokenizer ends with *"strip `[^A-Z0-9_]`"*. Measured against the **15,293
real AnyRoR dropdown values** bundled in `apps/android/app/src/main/assets/surveys/`, that rule
**fused 250 genuinely different surveys onto shared tokens** — silently, and irreversibly
once merged. Reproduce with:

```bash
node tools/identity/probe-tokenizer.mjs
```

The worst cases were in Bharoda (`gj:15:03:029`), which is dad's own data:

```
40_1  <-  40/1/અ  40/1/ક  40/1/ફ  40/1/બ  40/1/હ  40/1ઈ  40/1ગ  40/1ડ
1678  <-  1678  |  1678/અ
```

Eight separate parcels with separate owners on one key, and a survey fused with its own
sub-parcel. That is precisely the "never invent land data" failure in `CLAUDE.md`, so two
rules changed:

**1. Gujarati letters are transliterated, not stripped.** `845/અ` → `845_A`, `845/બ` → `845_B`.
The mapping (`GU_LETTERS`) is a fixed table in which every entry is distinct from every
other, so no two letters can share a token. Anything *not* in the table falls through to a
codepoint escape — `ઢ` → `U0AA2` — which is ugly on purpose: it is visible in a folder name,
it is identical on both machines, and it cannot silently fuse two surveys the way stripping
did.

**2. `+` and `-` are kept.** `364+365+366` is one *combined* survey and is not the same
parcel as `364`; `690-696` is a *range*. A `-` is treated as a separator (like `/`) only
when it is **not** between two digits, because there it is a suffix delimiter: `233-અ`
→ `233_B`-style, same shape as `233/અ`.

With both changes: **0 fused surveys across all 15,293 values in all 8 bundled villages.**

### The one thing left deliberately split

`99` and `99.` are *both* real dropdown options in the same village (`16_10_085`). They are
almost certainly the same parcel with a stray keystroke, but "almost certainly" is not good
enough to merge two land records, so they tokenize apart: `99` and `99U002E`. A wrong split
is visible and recoverable — the user sees two entries, and `survey_alias` holds both raw
forms. A wrong fusion is neither.

---

## Consequences for existing data

Re-tokenizing the 182 real `output/` directories: **0 collisions**, 9 renames.

```
100_1_ -> 100_1        532_2__P2 -> 532_2_P2      Bhalej_174_P1 -> BHALEJ_174_P1
101__3 -> 101_3        565_3_    -> 565_3         Salun_125     -> SALUN_125
113_   -> 113          247_3_    -> 247_3         Valetva_41    -> VALETVA_41
```

The last three are village-prefixed directory names rather than bare survey tokens; the
migration has to split the village off rather than tokenize the whole string.

The tokenizer is **idempotent** (`token(token(x)) == token(x)`, asserted for every vector on
both sides) — required, because the spec's migration must be re-runnable.

---

## Rules that are not negotiable

- **Nothing that varies between two scrapes may enter a uid.** No timestamps, file paths,
  DOM row ordinals, `sr_no`, `case_index`, or VF-7/12 `selectIndex`. They are payload columns.
- **Parts are joined with `\x1f`**, so `("a","bc")` and `("ab","c")` are different rows.
- **NULL is not the empty string** in a content hash (`\x00` vs `""`), or clearing a column
  would look like no change at all.
- **Floats are refused** in a content hash — no two languages agree on their text form, and
  nothing in this schema is legitimately a float.
- **A deed is keyed on `place + office + doc_year + doc_no`, never on the survey**, because
  one deed touches several surveys and several parties.
