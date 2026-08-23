# CAPTCHA automation

Two portals, two CAPTCHAs, two different solvers. Both are solved automatically; the
human fallback is the exception, not the path.

| | iRCMS | AnyRoR |
|---|---|---|
| Delivery | SVG, answer in `<text>` nodes | 190×80 PNG, baked into the page as a data URI |
| Charset | 6 chars, `[0-9a-f]` (an md5 fragment) | 6 digits |
| Method | Deterministic parse — sort by `x`, join | 6-head × 10-class CNN |
| Accuracy | Exact. No OCR involved. | **98.69%** on the held-out test split |
| Training data | None needed — the SVG *is* the label | 2,701 real captchas, hand-tagged |

---

## Layout

```
packages/captcha/
├── pipeline/     fetch → tag → train → eval → export      (the AnyRoR CNN)
├── solvers/      ircms.mjs — the deterministic iRCMS solver
├── model/        trained artefacts (.pt, .onnx, .weights, .split.json)
├── samples/      the hand-tagged corpus  (not in git — see below)
├── runners/      scripts that drive a live site end-to-end
├── pdf/          offline PDF assembly from fetched records
├── probes/       one-off diagnostics kept for reference
└── runs/         run logs and caches (not in git)
```

---

## The AnyRoR pipeline

Trained **only on real captchas harvested from the live site and tagged by hand.** Each
stage writes the input for the next.

### 1 · Fetch

```bash
node packages/captcha/pipeline/fetch-anyror.mjs [--n=100] [--delay=5000]
```

Gentle headed harvest — roughly 5 s jittered postbacks, so ~100 samples takes ~9 minutes.
Writes PNGs into `samples/anyror/`. There is no rush here: a fast harvest trips the WAF and
costs you the session.

### 2 · Tag

```bash
packages/captcha/.venv/bin/python packages/captcha/pipeline/tag.py [--port 8765]
```

Opens a keyboard tagger in the browser. Type 6 digits per image; it writes
`samples/anyror/labels.csv` incrementally, so it is safe to stop and resume.

### 3 · Train

```bash
packages/captcha/.venv/bin/python packages/captcha/pipeline/train.py \
    --augment --epochs 60 --bs 64
```

Splits the labelled set 80/10/10 and writes `model/anyror_cnn_real.pt`, a matching
`.onnx`, and `model/anyror_cnn_real.split.json`.

**The split is recorded by filename, not by seed.** `labels.csv` keeps growing as you tag,
so a seed alone would silently shuffle yesterday's test images into today's training set
and quietly inflate every number after it.

Model selection happens on val. The test split is scored exactly once, at the end.

### 4 · Eval

```bash
# the honest number — data the model has never seen
packages/captcha/.venv/bin/python packages/captcha/pipeline/infer.py \
    --dir samples/anyror --eval --split test
```

Current model, 2,701 hand-tagged samples:

| Split | Exact | Wrong at confidence > 0.9 |
|---|---|---|
| **test** (229, held out) | **98.69%** | 1 |
| val (229) | 98.69% | 0 |
| all labelled (2,701) | 99.11% | 2 |

Throughput is ~1,000 img/s on CPU, decode included — the solve is never the bottleneck in
a scrape.

Quote the **test** number. The all-labelled figure includes the training set and is not a
measure of generalization.

### 5 · Export for the app

```bash
packages/captcha/.venv/bin/python packages/captcha/pipeline/export_weights.py
```

Folds each conv+BN pair into a single conv and writes a flat float32 blob,
`anyror_cnn_real.weights`. The Android app runs a **pure-Kotlin forward pass** over it —
no ONNX Runtime, no native libraries on the device. Copy the blob to
`apps/android/app/src/main/assets/`, which is the one tracked copy of the model.

### Inference

```bash
packages/captcha/.venv/bin/python packages/captcha/pipeline/infer.py <png>...
packages/captcha/pipeline/infer.py --dir samples/anyror --csv out.csv
```

Prints the code, and the joint confidence to stderr. The runners call this directly.

---

## What was tried and abandoned

**Synthetic lookalikes did not work.** A generator replicating the captcha style (pattern
type, colour, per-char rotation and jitter) produced 60k labelled-by-construction images.
A model trained on them scored **~14% on real captchas** — the synthetic distribution was
simply too far from the real one. It was dropped in favour of tagging real samples by
hand, which is where 98.69% came from. The generator, its 60k images and the
synth-trained checkpoint have all been deleted.

Do not reintroduce synthetic data without first measuring it against `samples/anyror`.

Off-the-shelf OCR also fails on this captcha, measured on the same real set:

| Approach | Exact |
|---|---|
| tesseract (6 preprocessing variants) | ≤ 16% |
| ddddocr (generic captcha CRNN) | 44% |
| **this CNN, trained on real tagged data** | **98.69%** |

DeepSeek-OCR was considered and deferred: a 3B document-VLM is the wrong shape for a fixed
6-digit captcha — GPU-only, seconds per image, and captcha patterns are out of its
distribution.

---

## The sample corpus

`samples/` holds 3,075 AnyRoR PNGs (2,701 tagged) and 201 self-labelled iRCMS samples.

**It is not in git**, and it is not regenerable — the tagging is hours of manual work. It
is kept compressed as `samples.zip` (9.6 MB) beside this README, with a second copy in
`../irmsc-personal-archive/datasets/captcha-dataset.zip`.

```bash
cd packages/captcha && unzip samples.zip     # restore before training or eval
```

`model/*.pt` and `model/*.onnx` are ignored too. The one model artefact in git is the
`.weights` blob under the app's `assets/`, because it cannot be rebuilt without this
corpus and the app's solver is dead without it.

---

## Runners

```bash
node packages/captcha/runners/run-village-ircms.mjs              # whole village, resumable
node packages/captcha/runners/run-anyror-auto.mjs --n=5          # CNN-solved integrated fetch
node packages/captcha/runners/run-bhalej-full.mjs --surveys=174/p1,239
node packages/captcha/pdf/combine-survey-pdf.mjs Bhalej_174_P1   # staple one survey's PDFs
```

`run-village-ircms.mjs` is resumable through `output/_state.json`; its log is at
`output/village-run.log`.

---

## Gotchas

- **Both sites WAF-block headless Chrome.** Every runner drives a headed persistent profile
  (`DISPLAY=:1`) and aborts on the first FortiWeb block page rather than retrying into a ban.
- **Never re-fetch the AnyRoR captcha image.** It is already in the DOM as a data URI —
  read `img#ContentPlaceHolder1_i_captcha_1`. Re-fetching rotates the code server-side and
  invalidates the one on screen.
- **One captcha, one round.** AnyRoR regenerates on every postback; iRCMS on every
  `return_captcha` call and on every failure. On "invalid captcha", refresh *then* re-solve —
  never solve twice against the same page state.
- **Never reload the form while waiting on a solve.** ViewState preserves the cascade
  across a wrong-captcha postback; a reload throws the whole selection away.
