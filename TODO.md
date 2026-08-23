# TODO

Open work, ordered by whether it can lose data.

Status: `[ ]` open · `[~]` in progress · `[x]` done

---

## Correctness — can lose or corrupt data

- [ ] **`packages/maps/test/geo.test.mjs` — `pageToLatLng` fails.** Expects `[4, 3]`,
      gets `[3, 3]`. Pre-existing; predates the monorepo restructure. Either the affine
      application drops a term or the fixture is wrong — decide which before trusting any
      derived coordinate. Nothing currently consumes the output, which is why it went
      unnoticed.
      <br>`npm run test:maps`

- [ ] **Re-verify the tokenizer after any survey-number change.** `npm run check:tokenizer`
      must print `0 fused`. This is not a style check: a fusion silently merges two
      different families' parcels into one row, and the merge is not reversible after sync.

---

## Documentation drift

The restructure and the CNN solver both landed after parts of the docs were written.

- [ ] **`docs/APP_SPEC.md` §9 and `design/README.md` still say never auto-solve the
      CAPTCHA.** That was true until 2026-08-14, when the CNN landed. Both CAPTCHAs are now
      auto-solved and the human spotlight is only the fallback. `CLAUDE.md` is already
      correct; these two contradict it.

- [ ] **`CLAUDE.md` names the wrong path for the village asset.** It says
      `assets/maps/villages.json`; `packages/maps/build-app-catalog.mjs` actually writes to
      `apps/android/app/src/main/assets/maps/villages.json`.

- [ ] **`docs/PROJECT_NOTES.md` references moved files.** `run-fast.mjs` is now
      `packages/legacy/ircms/run-fast.mjs`; `build_vf712_combined.py` is now
      `packages/legacy/reports/build_vf712_combined.py`.

- [ ] **Confirm the SUPERSEDED banners are still accurate.** Three `2026-08-10-maps-*`
      documents predate the discovery that all 881 Kheda + Anand sheets are rasterized.
      `docs/MAPS.md` is the current source of truth.

---

## Housekeeping

- [ ] **Stray nested directory:** `assets/anyror-css/assets/anyror-css/`. Harmless
      duplication from the original stylesheet capture; confirm nothing references it, then
      flatten.

- [ ] **`packages/legacy` is unverified.** Its runners were fixed to import from
      `packages/core` and to read `data/catalog/`, and they parse — but none has been run
      end-to-end since the restructure. They need a live iRCMS session, so this is a
      when-convenient check, not a blocker. Treat the package as reference until then.

- [ ] **Two WhatsApp credential stores.** `auth/` (login, check, check-auth, send) and
      `auth-fam/` (notify, verify, send-bhalej) have diverged. Consolidate to one, or
      document why both exist. `WA_AUTH_DIR` already overrides per-run.

- [ ] **`packages/whatsapp/groups.json` is not in the repo** (it holds personal contact
      data and is git-ignored). `list-group-participants.mjs` needs it — regenerate with
      `node packages/whatsapp/login.mjs`.

---

## Planned, not started

Direction already decided; see the linked documents before starting either.

- [ ] **A place is an id, not a name** —
      [`docs/plans/2026-08-11-unified-place-identity.md`](docs/plans/2026-08-11-unified-place-identity.md).
      Deletes the dedupe machinery outright. Partially absorbed by
      [`docs/specs/2026-08-14-unified-db-and-autofetch-design.md`](docs/specs/2026-08-14-unified-db-and-autofetch-design.md);
      reconcile the two before implementing.

- [ ] **Problem reports over WhatsApp** —
      [`docs/plans/2026-08-11-whatsapp-problem-reports.md`](docs/plans/2026-08-11-whatsapp-problem-reports.md).
      Lets a non-technical user report a bad record without leaving the app.

- [ ] **Village maps, next steps** —
      [`docs/plans/2026-08-11-maps-future-possibilities.md`](docs/plans/2026-08-11-maps-future-possibilities.md).
      Constrained by the rasterization finding: only ~5% of sheets statewide are vector, and
      none of the home districts.

---

## Done

- [x] **Monorepo restructure.** `apps/` + `packages/` + `tools/` under one npm workspace;
      all 71 hardcoded absolute paths replaced with a resolved `REPO` constant; every
      relative import remapped and verified to resolve.
- [x] **Repository cleaned for sharing.** Quiz material, the personal WhatsApp archive
      project, invoices and scratch captures removed from the tree and purged from history.
- [x] **Captcha dataset moved out of git** into `packages/captcha/samples.zip`.
- [x] **Captcha package reorganized** into `pipeline/ solvers/ model/ runners/ pdf/ probes/`,
      with the five stages named `fetch → tag → train → infer/eval → export_weights`.
- [x] **Removed the abandoned synthetic-captcha route** — the 60k-image `synth/` corpus, the
      generator, the synth-trained checkpoint, the dead `solve-anyror.py`, and all Kaggle
      material. Docs no longer claim the shipped model was trained on synthetic data; it was
      trained on 2,701 hand-tagged real captchas.
- [x] **Recovered the full `labels.csv`** (2,701 rows) from a pre-rewrite backup after an
      earlier restore had picked up a stale 99-row version.
