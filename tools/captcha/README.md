# CAPTCHA automation (`tools/captcha/`)

Two different captchas, two different solvers.

## iRCMS — deterministic, no OCR (solved)

`ircms.gujarat.gov.in` serves its captcha as an SVG (`POST /return_captcha` →
`{captcha_svg}`) whose answer sits in plain `<text>` nodes. Sort by `x`, join → the code.
100 harvested samples confirm: always 6 chars, charset `[0-9a-f]` (an md5 fragment).

- `ircms-solve.mjs` — `searchWithAutoCaptcha(page)`: solve → fill → direct search, with
  re-solve on rotation. Used by the runners and ported to the app
  (`IrcmsInjection.autoSolveCaptchaJs`).
- `sample-ircms.mjs` — harvests self-labeled samples (the SVG *is* the label).
- `run-village-ircms.mjs` — full-village Bharoda runner (no human). Resumable via
  `output/_state.json`; log at `output/village-run.log`.
  `node tools/captcha/run-village-ircms.mjs [--only=221/p] [--from=400]`

## AnyRoR — raster PNG on patterned backgrounds (CNN)

`anyror.gujarat.gov.in` bakes a 190×80 PNG into the page (6 digits, per-char
font/size/rotation/jitter, dark ink on a light patterned background: dots/stripes/
crosshatch/zigzag). Off-the-shelf fails (eval on 99 human-tagged real samples):

| approach | exact |
|---|---|
| tesseract (6 preprocessing variants) | ≤ 16% |
| ddddocr (generic captcha CRNN) | 44% |
| **small CNN trained on synthetic lookalikes** | see below |

Pipeline (the `nladuo/captcha-break` recipe):
1. `sample-anyror.mjs` — gentle headed harvest (WAF-safe: ~5s jittered postbacks).
2. `tag-anyror.py` — keyboard tagger (`--port`, exactly 6 digits) → `samples/anyror/labels.csv`.
3. `gen-anyror-like.py` — synthetic generator replicating the style; labels by construction.
4. `train_cnn.py` — 6-head × 10-class CNN (PyTorch CPU). Trains on 60k synthetic,
   validates on the **real 99** (held out), exports `anyror_cnn.onnx`.
5. `solve-anyror.py` — ONNX inference, prints the code + joint confidence to stderr.

If real-set accuracy stays low, tune generator fidelity; if CPU training is too slow,
the same script runs on a vast.ai GPU unchanged (torch CUDA wheel).

DeepSeek-OCR was considered and deferred: a 3B document-VLM is the wrong shape for
fixed 6-digit captchas (GPU-only, ~seconds/image, captcha patterns out of distribution);
it's the last-resort fallback.

## Gotchas

- **Both sites WAF-block headless Chrome.** All runners are headed (DISPLAY=:1).
- AnyRoR regenerates the captcha per postback; iRCMS per `return_captcha` call and on
  failure. Solve→submit must use one round; on "invalid captcha", refresh then re-solve.
- `tesseract` needs `TESSDATA_PREFIX=tools/captcha/tessdata` (set inside `ocr-eval.py`).
