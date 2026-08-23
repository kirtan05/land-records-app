# Maps: future possibilities

Options for going beyond "open the official sheet", written after measuring what the
eJamin data actually is. Read `docs/MAPS.md` first — it holds the measured facts these
options are built on.

**The constraint everything below works around:** all 881 Kheda + Anand village sheets
are rasterized ArcMap exports. No text layer, no parcel paths, no georeferencing. Search
and adjoining cannot come from those PDFs.

Nothing here is committed work. Each option states what it unlocks, what it costs, and
how it can fail — so the decision is a real one rather than a hopeful one.

---

## Ranked shortlist

| # | Option | Unlocks | Effort | Risk | Verify first |
|---|---|---|---|---|---|
| 1 | In-app sheet viewer | pan/zoom, offline, no Drive detour | S | Low | — |
| 2 | Bhu-Naksha | real geometry, search, adjoining | M–L | **Unknown** | Is it reachable? |
| 3 | OCR the raster | survey-number search | M | Medium | Accuracy on a real sheet |
| 4 | Vector-sheet opt-in (~5%) | full original vision, where available | S–M | Low | Any Kheda/Anand? (today: none) |
| 5 | Parcels from the image | adjoining, tap-to-select | L | High | Does flood-fill hold up? |
| 6 | Bhuvan / ISRO / LGD | satellite context, boundaries | M | Medium | Licence + resolution |
| 7 | Better exports at source | everything, properly | XS effort, XL latency | High | — |

Each is independently shippable. 1 is worth doing regardless of what follows.

---

## 1. In-app sheet viewer

**Today:** tapping the glyph hands a Drive URL to the browser. That leaves the app, can
prompt for a Google account, and needs a connection every time.

**Instead:** download the PDF once into `Documents/LandRecords/maps/sheets/`, render with
`android.graphics.pdf.PdfRenderer`, show it with pan/zoom in the Cadastre chrome. The
sheet then works offline, permanently, and stays inside the app.

- Sheets are 2–5 MB — no tile pyramid needed.
- A0 at full scale is ~14k px wide: render a downscaled bitmap for the overview and
  re-render the visible region on zoom. Do not decode the whole page at full scale.
- Keep "Open in Drive" as a fallback for anything that fails to render.
- `pdfbox-android` is already a dependency, but `PdfRenderer` is the right tool for
  rendering; PDFBox is for the streaming merges.

**Effort:** small. **Risk:** low — worst case falls back to today's behaviour.
**This is the recommended next step**, and it is a prerequisite for 3 and 5 (both need
an in-app canvas to draw results onto).

---

## 2. Bhu-Naksha — the real fix, if it exists

`bhunaksha.gujarat.gov.in` is the state's cadastral map service. It serves **plot
geometry by survey number** — which is precisely what the rasterized PDFs threw away.
If it covers Kheda and Anand, it restores everything: search, true shared-edge
adjoining, tap-to-select, and real coordinates.

**Blocked on a fact nobody has checked.** From this machine TCP 80 and 443 both fail
instantly (forced IPv4 and by raw IP), while `anyror.gujarat.gov.in` returns 200 as a
control. So it is down, or blocked here. **Before any work: open it on a phone.**

If reachable, investigate in this order:
1. Does the cascade reach Kheda/Anand villages at all?
2. What does a plot request return — SVG, JSON geometry, or a rendered image? Only the
   first two are useful; a rendered image puts us back where we started.
3. Is there a stable plot-id ↔ survey-number mapping?
4. It is a `gujarat.gov.in` host → **AnyRoR politeness rules apply**: serial, delays, no
   parallel crawling.

**Effort:** medium to large, entirely unknown until step 1. **Risk:** high — may be
unreachable, may not cover these districts, may only render images. But the payoff is
the whole original vision done properly, so it is worth the hour it takes to find out.

---

## 3. OCR the raster sheets → survey-number search

Rasterized sheets are clean printed line art, not handwriting or camera scans — close to
the best case for OCR.

**Pipeline (offline, on the dev box — never on the phone):**
1. Render each village PDF at high DPI (`pdftoppm`).
2. Tesseract with a digits-plus-`/PA` character whitelist, tuned for small isolated
   labels; the sheets have no prose to help.
3. Emit `surveyNo → (x, y)` in page space plus a confidence.
4. **Discard low-confidence reads rather than guessing** — a wrong location is worse than
   no result, because it would send someone to the wrong field.
5. Ship the index the same way `villages.json` ships, or host it beside the releases.

**Gives:** type a survey number, the map pans and zooms to it and drops a marker.
**Does not give:** parcel outlines, adjoining, or tap-to-select — there is no geometry,
only points.

**Verify before committing:** OCR one real sheet (Bharoda is a good candidate) and hand-check
30 numbers. Below ~90% the feature will mislead more than it helps. Watch for `/P` part
suffixes, numbers rotated to fit narrow plots, and labels touching boundary lines.

**Effort:** medium. **Risk:** medium — accuracy is unknown until measured. Depends on 1.

---

## 4. Opt-in support for the ~5% vector sheets

About 759 of 16,044 statewide sheets are vector + georeferenced. For those, the original
design works exactly as written, and most of the code already exists (`lib/content.mjs`,
`lib/geo.mjs`, `lib/geom.mjs`, plus the plan in `docs/plans/2026-08-10-maps-1-pipeline.md`).

**Today this delivers nothing for us: Kheda and Anand are 0/881 vector.** Its value is
conditional — worth doing only if a re-scrape shows those districts re-exported as
vector, which is plausible since the state is clearly still re-exporting in batches.

**Cheap standing check:** `classify-format.mjs` over Kheda + Anand takes a few minutes
and needs no downloads (Drive `Content-Length` via a Range request). Re-run it
occasionally; if vector sheets appear, this option becomes the best one on the list.

**Effort:** small to medium (mostly already written). **Risk:** low, but currently
zero payoff.

---

## 5. Recover parcels from the image

The maps are black boundary lines on white cells. Classical image processing can, in
principle, recover polygons: threshold, remove text, close small gaps in lines, then
flood-fill or connected-component each white cell, and vectorise the boundaries. Pair
with option 3's OCR to attach a survey number to each recovered cell.

**Would give** true shared-edge adjoining and tap-to-select — the last pieces of the
original vision.

**Why it is ranked low:** the failure modes are severe and quiet. A single-pixel gap in a
boundary merges two plots into one. Numbers touching a line split a plot in two. Both
produce a map that looks right and is wrong, which is worse than having nothing —
particularly for adjoining, where the whole point is knowing who borders you.

If attempted: build the same honesty gate the original plan had — anything that fails a
sanity check (implausible parcel count, cells that swallow half the sheet, unassigned
numbers) is demoted to sheet-only rather than shipped.

**Effort:** large. **Risk:** high. Needs 1 and 3 first.

---

## 6. Bhuvan / ISRO, LGD, OpenStreetMap

Different value: **context**, not cadastre. None of these carry survey-number plot
boundaries, so none of them substitute for 2 or 5.

- **Bhuvan (ISRO/NRSC)** — satellite imagery and thematic layers, some WMS/WMTS. Could
  give a satellite backdrop and "where am I" via GPS. Check licensing for redistribution
  inside an app.
- **LGD (Local Government Directory)** — authoritative district/taluka/village codes.
  Genuinely useful independent of maps: it would give the app a **stable place id**,
  which is exactly what `docs/plans/2026-08-11-unified-place-identity.md` wants, and
  would have prevented the Gujarati/English duplicate-village bug outright.
- **OpenStreetMap** — village boundaries and roads, freely licensed, quality varies
  in rural Gujarat.

**Effort:** medium. **Risk:** medium (licence, coverage). LGD is the highest-value piece
here and is worth pursuing on its own merits.

---

## 7. Ask for better exports

The vector data demonstrably exists — the state exported it correctly for Dholka in
August 2024 and rasterized it three weeks earlier for Bharoda. The Data Source block
printed on every sheet names the Settlement Commissioner & Land Records, the Inspector
General of Registration, and the Roads & Buildings Department.

A request for vector exports (or the underlying shapefiles) for Kheda and Anand costs
almost nothing to make and would solve everything at the source.

**Effort:** trivial. **Latency:** months, realistically. **Risk:** likely no reply. Worth
sending precisely because it costs so little; not worth waiting for.

---

## Suggested order

1. **Ship the in-app viewer (1).** Useful on its own, needed by everything else.
2. **Spend an hour on Bhu-Naksha (2)** — starting with "does it load on a phone". It is
   the only option that restores the full vision, so its answer reorders this list.
3. If 2 fails, **prototype OCR (3)** on one sheet and measure accuracy before committing.
4. Keep the **format check (4)** as an occasional cheap re-run.
5. Treat **5** as research, not a plan.
6. Pursue **LGD (6)** independently — it fixes place identity, not just maps.

## Open items carried from the current work

- **GDCR scrapes 0 rows** — wrong `selectClass` in `scrape-catalog.mjs`. Small fix,
  unrelated to any option above (`docs/MAPS.md` §2).
- `packages/maps/` is committed but has never been code-reviewed.
- `lib/geo.mjs` carries a test whose expected value is wrong: `pageToLatLng` with the
  identity matrix yields `[3,3]`, not `[4,3]`. The code is right, the test is not.
