# Jantri land rates on the survey screen — shipped v0.10.0 (2026-08-11)

Every survey number in **Anand and Kheda** now shows the government (jantri) rate for that
parcel, and — where the app knows the area — the total government value.

Jantri is the **stamp-duty floor**, not a market price. That framing is deliberate and
should survive any redesign of this card.

---

## What the user sees

On Survey detail, above the record cards:

```
JANTRI RATE                                    ASR-2011 × 2
પિયત            On national / state highway
₹690 per sq.m                    ₹27,92,340 per acre
= ₹70,35,008  (₹70.4 lakh)
```

- One row per land type the source prices (`બિનપિયત` / `પિયત` / `ખરાબા` / `ખનિજ`).
- The road classification is what makes two neighbouring survey numbers differ by 2×,
  so it is shown, never averaged away.
- Rates **already include** the 15/04/2023 doubling; the basis is stated once beside the
  title rather than as a second row of numbers.
- `= area × rate` appears **only when the area is known** — never estimated.
- ~1% of survey numbers sit in more than one priced row (part of the parcel fronts a
  highway). Those show "more than one rate applies" instead of one number being picked.
- A survey number that is not in the jantri renders **nothing at all**.

Tapping the area chip cycles **ha-a-m² → acre → vigha → guntha → m²**.
The vigha is customary and regional (16 guntha in Saurashtra, 20 usually, 24–25 in
places), so the chip prints its basis: `16.4 vigha (@20 guntha)`. `GUNTHA_PER_BIGHA` in
`LandArea.kt` is the single place to change it. **Open question for a land dealer: is 20
right for Charotar?**

## Code

| | |
|---|---|
| `data/jantri/JantriRates.kt` | asset-DB lookup; finds a village by its Gujarati **or** English name |
| `data/jantri/LandArea.kt` | ha-a-m² parsing + unit conversion (the only place that maths lives) |
| `ui/survey/JantriCard.kt` | the card |
| `assets/jantri/jantri.sqlite` | 9.6 MB, Anand + Kheda (4.3 MB compressed in the APK) |

## Data pipeline

Fully documented in **`data/jantri/README.md`** — read that before touching the parser.
Summary: all 26 district PDFs from `garvi.gujarat.gov.in/PDF/RURAL/`, 39,864 pages,
1,074,451 rate cells, 7.5M survey entries, 17,865 villages.

```bash
packages/jantri/fetch.sh              # 141 MB of PDFs
python3 packages/jantri/parse.py      # -> CSV  (~2 min)
python3 packages/jantri/build_db.py   # -> data/jantri/jantri.sqlite (full, 476 MB)
python3 packages/jantri/build_crosswalk.py   # jantri <-> iRCMS villages
python3 packages/jantri/build_asset.py       # -> the app asset (Anand + Kheda)
python3 packages/jantri/lookup.py KHEDA VALETVA 5
```

Everything except `villages.csv`, `crosswalk.csv` and the app asset is gitignored and
regenerable.

### Three source quirks that each caused silent data loss

Kept here because they will look like bugs to the next reader:

1. **Continuation pages.** A long survey list overflows onto following pages where the
   rate is *not* reprinted (245 pages). Those numbers belong to the preceding row.
2. **Multi-rate rows.** One row can price several land types at the same y. Every cell
   must inherit the row's survey list, or ~15% of rows lose it entirely.
3. **Kachchh uses a different font encoding** — the whole byte stream is shifted by
   **+29** (`$65` = `ASR`, `3DLNL` = `Paiki`). Undecoded it yielded 71 of 2,844 pages.
   Its words also arrive merged, so header labels are matched by substring.

### Verification (re-run these if the parser changes)

- 312 randomly sampled pages across all 26 districts reproduce the PDF's **exact multiset
  of rates** — 312/312.
- Page accounting is exact: 39,619 rated + 245 continuation = 39,864 = every page.
- All 1,074,451 per-sq.m figures equal per-acre ÷ 4046.856422.
- The compact app DB reproduces the full one on 7,800 random survey lookups.

## Village crosswalk

959/963 (99.6%). The iRCMS cascade carries English **and** Gujarati with official codes,
so this is a join, not transliteration. Two things it has to handle:

- **Names repeat across talukas**, so each name-group is solved as a **1:1 assignment**
  (exact taluka first, then 2013-successor taluka, then leftovers) rather than greedily —
  otherwise Kapadvanj's Kosam gets Galteshwar's rate.
- **The ASR predates the 2013 reorganisation.** Jantri's Kheda still contains Balasinor
  and Virpur, now Mahisagar; the cascade has Fagvel/Galteshwar/Vaso carved out since. So
  matching is district-wide, and Kheda also searches Mahisagar.

Parenthesised aliases are matched on the **full** name first — `Khadol (Haldari)` and
`Khadol (Umeta)` are different villages.

## Known gaps

- **Towns are not covered.** 32 AnyRoR villages get no rate: Anand City, Karamsad,
  Borsad, Petlad, Umreth, Khambhat, Nadiad City wards, Chaklashi, Bakrol, Ode… all
  towns. This is the **rural agricultural** jantri; urban/NA land is a separate document
  set whose filenames are not guessable (all probed URLs 404) and would need the same
  dropdown-postback reverse engineering as the cascade.
- **Only Anand + Kheda ship.** The other 24 districts are parsed and on disk; shipping
  one costs 1–4 MB compressed. Blocked only on building their crosswalks.
- **4 villages have no iRCMS counterpart** (Ramol, Deva Vanta, Petli, Run) — verified
  against live iRCMS, which genuinely lists only 22 Sojitra villages. They still work by
  their jantri name; they just have no Gujarati name.
- **Rates are 2011 + a policy ×2.** A Jantri-2024 revision was still in its objection
  process; the 2023 GR is a scan with no text layer, so only the multiplier is applied.
