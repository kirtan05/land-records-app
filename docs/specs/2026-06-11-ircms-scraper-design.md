# iRCMS Survey-Case Scraper — Design

Date: 2026-06-11
Target: https://ircms.gujarat.gov.in/ViewSurveyList
Scope: District **ANAND**, Taluka **Umreth**, Village **Bharoda** (fixed).

## Goal
For a list of survey numbers, save every case's detail page as a PDF, and for each
DISPOSED case also download its order PDF — all named consistently, with a flat-file
"database" (CSV + per-case JSON) capturing full metadata, and resume support.

## Inputs
- **Handwritten JPG** of survey numbers → transcribed by Claude → normalized → matched
  against the dropdown catalog. Uncertain/﻿ambiguous matches are flagged for user
  confirmation before scraping.
- **survey-catalog.json/.csv** (already built from recon): 1,535 valid Bharoda survey
  numbers → exact dropdown `value` + normalized key.

## Recon findings (confirmed)
- Form field ids: `sel_district` (ANAND=`15`), `sel_taluka` (Umreth=`03`),
  `sel_village` (Bharoda=`029`), `sel_survey_no`, captcha input `txt_captcha`,
  submit `#btnViewSurveyList` (name=`view`, type=button → JS/AJAX).
- Laravel app: hidden `_token` (CSRF) + `casekey` hidden field used to open a case.
- CAPTCHA is an inline **SVG** image (`alt="Captcha"`). Treated as human-solved.
- Survey option values carry a trailing ` ~~ ` marker and mixed Latin/Gujarati glyphs;
  must be selected by the exact `value` string from the catalog.

## Encoding / matching (built)
`packages/core/normalize.mjs`: Gujarati digits ૦–૯→0–9, `પ`(paiki)→`p`, collapse spaces/slashes
to `/`, strip ` ~~`. Verified: `221 p` → `221/p` → dropdown `"221/p ~~ "`. No catalog
collisions across 1,535 entries.

## CAPTCHA strategy
Headed Chrome (user sees it). **One CAPTCHA per survey number.** Script fills the four
dropdowns, then prompts in the terminal ("solve CAPTCHA for 221/p, press Enter"); user
types it into the visible browser and presses Enter; script clicks View and proceeds.
All cases + orders for that survey number are then saved unattended.

## Per-survey flow
1. Skip if already completed (check `output/index.csv` / per-survey state) — resume.
2. Select district/taluka/village/survey-no (exact catalog value).
3. Human solves CAPTCHA → click View → wait for results table.
4. Read the results list → N cases. For each case:
   a. Open the case (click row / set `casekey`).
   b. Extract full metadata: case no, registration no, status, court, party details,
      lower-court details, revenue survey details, proceedings history, order details.
   c. Save case PDF → `output/<SurveyNo>/Bharoda_SurveyNo_<S>_Case<N>.pdf`.
   d. Write `output/<SurveyNo>/case<N>.json` (full metadata).
   e. If status is DISPOSED and an order file is listed → download it →
      `output/<SurveyNo>/Bharoda_SurveyNo_<S>_Case<N>_Order.pdf`.
   f. Go back to results for the next case.
5. Append rows to `output/index.csv`; mark survey number done.

## Storage ("small database", flat files)
- `output/index.csv` — one row per case (survey, case#, registration, status, court,
  parties, filing/disposal dates, case_pdf, order_pdf, order_downloaded).
- `output/<SurveyNo>/case<N>.json` — complete structured record per case.
- `output/_state.json` — per-survey progress for resume.

## PDF generation
Chrome print-to-PDF via Playwright (`page.pdf()` / CDP `Page.printToPDF`), A4,
background graphics on. **Risk:** if `page.pdf()` is unreliable in headed mode, fall
back to capturing session cookies and rendering each detail URL in a short-lived
headless context for the PDF. Decided during the 221/p test.

## Components (files)
- `packages/core/normalize.mjs` — survey-number normalization + catalog lookup (done).
- `packages/core/ircms.mjs` — browser driver: navigate, cascade-select, captcha-wait, search,
  list cases, open case, extract metadata, save PDF, download order.
- `packages/core/store.mjs` — CSV + JSON + state writers; resume check.
- `run.mjs` — CLI: load matched survey list, loop, orchestrate, log progress.
- `match-input.mjs` — transcribed JPG list → catalog matches + ambiguity report.

## Error handling
- Per-survey try/catch → record error in state, continue to next (never lose progress).
- CAPTCHA wrong / no results → re-prompt once, then mark survey "no-cases"/"error".
- All PDFs/JSON written atomically; index.csv appended after each case.

## Test plan
1. Headed test on **221/p** only: confirm results parsing, case extraction, PDF output,
   and one order download. Inspect the generated PDF + JSON together.
2. On approval, run the full matched list.

## Open questions before full run
- Exact results-table + case-detail DOM (captured during the 221/p headed test).
- Order-file download mechanism (link vs JS) — confirmed during 221/p test.
