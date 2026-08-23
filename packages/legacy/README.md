# `packages/legacy/` — historical runners

Reference implementations kept for provenance. They are **not** part of the
supported path; the Android app and `packages/captcha/` supersede them. They are
retained because they encode hard-won details (exact cascade IDs, the
placeholder-weeding rule, mixed Gujarati/Latin font handling) that the on-device
port was built to match.

| Path | What it is |
|---|---|
| `ircms/` | The original iRCMS case-scraping runners, driven against a live Chrome over CDP. `session-start.mjs` opens the visible browser; `run.mjs` / `run-fast.mjs` orchestrate; `verify-all.mjs` validates the output tree. |
| `reports/` | The Python PDF/Excel builders — `build_anyror_pdf.py`, `build_reports.py`, `build_vf712_combined.py`. |

All of these resolve paths through `packages/core/repo-root.mjs` / `tools/repo_root.py`,
so they run from any clone. Most expect `output/` to be populated and a Chrome
profile to exist; neither is in version control.
