# Jantri (ASR-2011) — Gujarat rural agricultural land rates

Machine-readable extraction of the Gujarat government's **Annual Statement of Rates
(ASR) 2011 Final** for *rural agricultural* land — the "jantri" rate used as the
minimum valuation for stamp duty.

Source: `https://garvi.gujarat.gov.in/PDF/RURAL/<file>.pdf` (Superintendent of Stamps,
Gandhinagar). Resolution dated **18/04/2011** of the Revenue Department.

**All 26 district PDFs, 39,864 pages, are parsed with zero unaccounted pages.**

| | |
|---|---|
| Districts | 26 |
| Villages | 17,865 (district+taluka+village) |
| Table rows | 459,614 |
| Rate cells | 1,074,451 |
| Survey-number entries | 7,523,646 |

---

## Read this before showing a number to a user

1. **These are 2011 rates.** Gujarat doubled jantri rates with effect from
   **15 April 2023**. The `*_2023` columns are `2011 × 2` — **derived here, not
   read from any PDF**. The 2023 notification (`PDF/GR_Jantry_2023.PDF`) is a
   13-page scan with no text layer, so it cannot be parsed; only the multiplier is
   applied. A further **Jantri-2024** revision was still in its objection process
   as of late 2024 and is *not* reflected here.
2. **Jantri is a floor for stamp duty, not a market valuation.** Never present it
   as "what the land is worth".
3. **Agricultural land only** (`ખેતી જમીન`). Non-agricultural/urban rates live in a
   different document set that is not covered here.
4. **26 districts, not today's 33.** The ASR predates the 2013 reorganisation, so
   Botad, Aravalli, Morbi, Gir Somnath, Devbhumi Dwarka, Mahisagar and Chhota
   Udepur do not exist as districts — their villages sit under the parent district
   of the time (e.g. Botad villages are under Bhavnagar/Ahmedabad).
5. **A survey number can carry more than one rate** (~1% of cases) — typically when
   part of it fronts a highway. Show "multiple rates apply" rather than picking one.

---

## Files

```
data/jantri/
  README.md          this file
  sources.txt        district -> remote PDF basename (the naming is inconsistent)
  villages.csv       17,865 villages + join keys        [tracked in git, 1.4 MB]
  pdf/               26 source PDFs                     [gitignored, 141 MB]
  out/               per-district CSVs                  [gitignored, 617 MB]
  jantri.sqlite      combined queryable database        [gitignored, 476 MB]
packages/jantri/
  fetch.sh           download the PDFs
  parse.py           PDF -> CSV
  build_db.py        CSV -> SQLite
  lookup.py          survey number -> rate (reference implementation)
```

Everything except `villages.csv` is gitignored and fully regenerable:

```bash
packages/jantri/fetch.sh          # ~141 MB download
python3 packages/jantri/parse.py  # ~2 min
python3 packages/jantri/build_db.py
python3 packages/jantri/lookup.py KHEDA VALETVA 5
```

---

## Schema (`jantri.sqlite`)

Location strings are stored once in `villages`; the other tables reference
`village_id` (the survey index has 7.5M rows — repeating the text there cost 1.4 GB).

```
villages(village_id PK, district, taluka, village, village_key, n_rows, n_surveys)
rates(row_id, village_id, page, land_type, road_class,
      rate_per_acre_2011, rate_per_sqm_2011,
      rate_per_acre_2023, rate_per_sqm_2023, survey_numbers_raw)
survey_index(village_id, row_id, survey_base, survey_suffix, is_paiki)
land_types(code, gu, en)     road_classes(code, gu, en)     meta(key, value)
```

`row_id` = one printed table row. A row may price up to four land types, so it can
appear as up to four rows in `rates`, **all sharing one survey list**.

`land_type` ∈ `dry` (બિનપિયત, non-irrigated) · `irrigated` (પિયત) ·
`waste` (બિનખેડાણપાત્ર ખરાબા) · `mineral` (ખનિજ તત્વોવાળી).

`road_class` ∈ `SAMANYA` (સામાન્ય, general) ·
`DISTRICT_ROAD` (જીલ્લા મુખ્ય/અન્ય જીલ્લા માર્ગ ઉપર) ·
`HIGHWAY` (રાષ્ટ્રીય/રાજ્ય ધોરીમાર્ગ ઉપર). This is the rightmost column of the PDF
and it is what makes two neighbouring survey numbers differ by 2×.

`rate_per_sqm` is taken from the parenthesised figure printed in the PDF, and is
`rate_per_acre / 4046.856422` in every one of the 1,074,451 cells (verified).

### Lookup

```sql
SELECT r.land_type, r.road_class, r.rate_per_sqm_2023, r.rate_per_acre_2023
  FROM survey_index s
  JOIN villages v ON v.village_id = s.village_id
  JOIN rates    r ON r.row_id     = s.row_id
 WHERE v.district = 'KHEDA' AND v.village_key = 'VALETVA' AND s.survey_base = 5;
```

---

## Shipping it in the app (`data/jantri/app/`)

`packages/jantri/build_app_db.py` emits one compact SQLite per district for offline,
in-app lookup. **476 MB → 124 MB raw / 49 MB gzipped for all 26 districts**, so a
single district is 1–4 MB compressed:

| | raw | gzipped |
|---|---|---|
| Anand | 3.8 MB | 1.5 MB |
| Kheda | 5.9 MB | 2.4 MB |
| Kachchh (largest) | 12.9 MB | 4.8 MB |
| **All Gujarat** | **124 MB** | **49 MB** |

The saving is structural, not generic compression:

- only `acre_2011` is stored — per-sq.m is `round(acre / 4046.856422)` and the 2023
  rate is `× 2`, both exact for all 1,074,451 cells;
- the 7.5M-entry survey index becomes ~1.4M `(lo..hi)` ranges plus ~0.9M suffixed
  entries, since survey numbers within a row are mostly consecutive;
- `survey_numbers_raw`, `page` and label text are provenance, and stay in the full
  `jantri.sqlite` rather than shipping.

Labels are integer codes (`land_type` 0–3, `road_class` 0–3) resolved in the UI, so
Gujarati/English text is not duplicated per row.

**Verified lossless:** 7,800 random survey lookups across all 26 districts return
results identical to the full database.

This fits the existing slim-update-APK pipeline: ship the user's district(s) on
demand rather than bundling all of Gujarat.

```sql
-- plain survey number
SELECT r.land_type, r.road_class, r.acre_2011 FROM sranges s
  JOIN rates r ON r.row_id = s.row_id
 WHERE s.village_id = ? AND ? BETWEEN s.lo AND s.hi;
-- suffixed (857/Paiki, 824/A)
SELECT r.land_type, r.road_class, r.acre_2011 FROM ssub s
  JOIN rates r ON r.row_id = s.row_id
 WHERE s.village_id = ? AND s.base = ? AND s.suffix = ?;
```

---

## Matching against AnyRoR data

`village_key` is the join key: uppercase, non-alphanumerics stripped
(`AMARPUR(VARUDI)` → `AMARPURVARUDI`, `ADRAJ MOTI` → `ADRAJMOTI`).

Two things to handle:

- **`village_key` is not unique within a district.** 1,119 keys repeat across
  talukas, so **always join on `(district, taluka, village_key)`**. Village name
  alone will silently pick the wrong village.
- **The PDFs name places in Latin transliteration; AnyRoR uses Gujarati.** That
  mapping is *not* built here. Transliteration is inconsistent across government
  sources, so it needs a reviewed crosswalk rather than a naive transliterator —
  the LGD (Local Government Directory) village-code list, which carries both
  scripts plus a stable census code, is the sane basis for it.

### Survey number normalisation

`survey_base` is the integer; `survey_suffix` is `''`, `PAIKI`, or a subdivision
(`1`, `2`, `A`, `B`, …). Source forms handled:

| In the PDF | Parsed as |
|---|---|
| `857` | base 857, suffix `''` |
| `857/Paiki`, `857/PAIKI`, `857/aiki` | base 857, suffix `PAIKI`, `is_paiki=1` |
| `824/A`, `1053/B`, `1784/2` | base, suffix `A` / `B` / `2` |
| `-/1130TO1131` | expanded to 1130, 1131 |

The `aiki`/`AIKI` variants are glyph-mapping dirt in the source, normalised to
`PAIKI`. Ranges are expanded, capped at 2,000 members per range.

---

## How the extraction works, and what is fragile

The PDFs have a real text layer (no OCR). Gujarati is set in a **legacy non-Unicode
font**, so it extracts as mojibake — but every field that matters (survey numbers,
rates, district/taluka/village) is plain ASCII. Only three Gujarati strings carry
meaning (the road classes); they are a closed set, hand-mapped to Unicode in
`parse.py`.

Columns are assigned by the **x-coordinate** of each word's centre. The table
geometry is byte-identical on all 39,864 pages (`binpiyat` 230.3, `piyat` 316.3,
`kharaba` 375.7, `khanij` 444.0), so this is reliable rather than heuristic. The
header's *vertical* position does shift between districts, so the header/body split
is calibrated per page off the column-header row instead of a fixed y.

Three source quirks the parser handles — each caused real data loss before it did:

- **Continuation pages.** A long survey list overflows onto following pages where
  the rate is not reprinted. Those numbers are carried onto the preceding row
  (245 pages, plus 420 partial spills).
- **Multi-rate rows.** A row can price several land types at the same y. Each cell
  must inherit the row's survey list, or ~15% of rows lose it.
- **Kachchh uses a different font encoding** — the whole byte stream is shifted by
  **+29** (`$65` = `ASR`, `3DLNL` = `Paiki`). It is un-shifted on load, after which
  the page is identical to every other district. Kachchh also emits several words
  per token, so header labels are matched by substring, not equality.

### Verification performed

- 312 randomly sampled pages across all 26 districts reproduce the PDF's **exact
  multiset of rates** for that page — 312/312.
- Page accounting is exact: 39,619 pages with rates + 245 continuation pages =
  39,864 = every page of every PDF. Nothing is silently skipped.
- Anand independently cross-checks: 10,962 parsed rows == 10,962 road-class cells
  counted straight from `pdftotext` output; 12,869 rate cells likewise.
- Every one of the 1,074,451 per-sq.m figures equals per-acre ÷ 4046.856422.

### Known gaps

- **Kachchh: 166 rows (0.19%) have an unrecognised road class and 2,144 (2.4%) an
  empty one**, left as `''`. Every other district is 100% classified. Treat a blank
  `road_class` as unknown, not as `SAMANYA`.
- Village/taluka names are as transliterated in the source and are **not**
  reconciled against LGD or AnyRoR (see above).
- The `DANG` district is named `DANG` in the data but its PDF is `DANGS.pdf`.
