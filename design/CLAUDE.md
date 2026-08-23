# Building the Land Records UI

The approved design lives in `design/`. Read
`design/README.md` before writing any UI code — it is the
visual source of truth required by `docs/APP_SPEC.md` §11, and it names exact
colours, type sizes, radii, spacing, copy and flows.

Rules for this app's UI:
- Direction is "Cadastre" at **comfy** density. Ochre accent `#B4531B` (dark `#E58A55`),
  cool-green neutrals, **no elevation** — 1dp borders only.
- Every survey is drawn as a **parcel tile**: 1dp border, radius 12dp, plus a 1dp
  dashed inset at 5dp. Every survey/record set carries the 4-slot **stamp strip**
  (I · V · D · C), filled when held, hollow when missing.
- Survey numbers, counts and all-caps labels are **IBM Plex Mono**; headings and
  body are **Space Grotesk**; Gujarati falls back to **Noto Sans Gujarati** in every
  family (mixed strings box out otherwise).
- App chrome respects the **language setting** (gu | both | en) via `L(gu, en)`;
  land data is never translated — Gujarati numerals stay, with a Latin helper line.
- Never invent land data. Unknown metadata renders `—`.
- Both themes are first-class. All motion collapses under reduced-motion.
- The CAPTCHA is always human. Pre-fill and lock the cascade fields, dim the rest
  of the page, spotlight only the code box and Get Record Detail.

Drop-in starting points: `design/compose/{Color,Type,Dimens}.kt`
and `strings-additions.xml`.
