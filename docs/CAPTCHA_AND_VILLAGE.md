# CAPTCHA automation + full-village scraping

Status 2026-08-23. Code lives in `packages/captcha/` (see its README for the file-by-file map);
this page is the why + the numbers. App-side wiring: `IrcmsInjection.autoSolveCaptchaJs`.

## The two captchas are different species

| | iRCMS (`ircms.gujarat.gov.in`) | AnyRoR (`anyror.gujarat.gov.in`) |
|---|---|---|
| Format | SVG in JSON (`POST /return_captcha`), answer in plain `<text>` nodes | 190×80 PNG baked into the page, 6 digits on patterned backgrounds |
| Solver | **Deterministic parse** — sort `<text>` by x, join. 100%, no OCR | **6-head CNN** trained on real hand-tagged captchas |
| Samples | 201, self-labeled (the SVG is the label). Charset `[0-9a-f]`, len 6 | 3,075 harvested, **2,701 hand-tagged** (`pipeline/tag.py`). Digits only, len 6 |
| Rotation | Per `return_captcha` call; solve→submit must be one round | Per postback; same rule |

Both sites serve a FortiWeb block page to **headless** Chrome — every runner is headed
(`DISPLAY=:1`). AnyRoR additionally IP-blocks bursts: harvest/post gently (≥4–6s jittered).

## AnyRoR: what each approach scored on the real, hand-tagged set

| approach | exact-match |
|---|---|
| tesseract raw / otsu / blackhat / bg-diff / clean / psm8 | 0–16% |
| ddddocr (generic captcha CRNN, CPU) | 44% |
| 6-head CNN trained on **60k synthetic** lookalikes | **~14% — abandoned** |
| 6-head CNN trained on **real hand-tagged** data (shipped) | **98.69%** on the held-out test split |

The synthetic route failed outright: replicating the captcha's style was not enough to
replicate its distribution, and the synth-trained model did worse than off-the-shelf
ddddocr. Everything shipped is trained on captchas harvested from the live site and
tagged by hand. The generator and its 60k images have been deleted.

Preprocessing: **the CNN trains on plain data** — grayscale → resize 190×80→160×64 → ÷255.
Nothing else. Thresholding/blackhat/median-bg-subtraction were evaluated *only for tesseract*
(classical OCR needs clean binary input). For a CNN, heavy preprocessing destroys stroke
information and bakes in assumptions; given real training data the network learns to ignore
the background patterns by itself.

DeepSeek-OCR was considered and deferred: a 3B document-VLM is the wrong shape for fixed
6-digit captchas (GPU-only, seconds per image, captcha patterns out of distribution).
vast.ai is the fallback if CPU training stalls (same `pipeline/train.py`, CUDA wheel).

## Full-village iRCMS run (Bharoda)

`node packages/captcha/runners/run-village-ircms.mjs [--only=S] [--from=N]` — loops all 1,535 catalog
surveys: cascade → auto-solve → direct search → the proven scrape (per-case PDFs + order
PDFs + case JSON + index.csv). Resumable via `output/_state.json`; log `output/village-run.log`.
WAF-aware (aborts on block page; re-run resumes).

## Size per village (for the S3 decision)

Measured on the 12 real Bharoda surveys in `output/`:

| component | MB/survey | note |
|---|---|---|
| VF-7/12 scans | 7.9 | dominant; per-period image PDFs |
| iRCMS order PDFs | 3.1 | only on disposed cases |
| iRCMS case PDFs | 0.2 | ~1.25 MB per case+order pair |
| AnyRoR integrated | 0.45 | PDF + raw HTML + JSON |
| other JSON/HTML | 3.5 | re-render sources |
| **total** | **~15** | |

Naive extrapolation: 1,535 × 15 MB ≈ **23 GB/village**. The sample is biased toward
case-heavy family surveys, so the true average lands lower — the live run produces the
real distribution. S3 Standard at ~$0.023/GB-month → roughly **$0.5/village-month**;
storage is not the constraint either way.
