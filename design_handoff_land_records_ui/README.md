# Handoff: Land Records — Android UI (direction “Cadastre”, comfy density)

## Overview
The approved visual design and UX flow for the **Land Records** Android app
(`kirtan05/land-records-app`). It covers all five screens named in
`docs/APP_SPEC.md` §5 — Library, Survey detail, Fetch (WebView + CAPTCHA),
Add/Edit property, Settings — plus the “Saving to your library…” state, in
light **and** dark theme, with a Gujarati / bilingual / English language mode.

This replaces the placeholder in `android/app/src/main/java/com/landrecords/app/MainActivity.kt`.
Nothing in `data/`, `web/` or the storage layer changes.

## About the design files
`prototype/` holds **design references written in HTML/JS** — a clickable
prototype of the intended look and behaviour. **Do not port the HTML.** The job
is to rebuild these screens in the existing Kotlin + Jetpack Compose app using
Material 3 primitives and the tokens below. The prototype is the visual source of
truth (per `docs/APP_SPEC.md` §11); this README is the spec.

Open `prototype/Land Records - Android UI.dc.html` in a browser to click through
it (it needs `support.js` and `android-frame.jsx` next to it, as bundled).
`CadastrePhone.dc.html` is the approved direction on its own — it takes
`density` (`comfy` | `default` | `compact`), `theme` (`light` | `dark`) and
`lang` (`both` | `gu` | `en`) as props.

**Approved configuration: `density = comfy`, `theme` follows system, `lang = both`.**

## Fidelity
**High fidelity.** Colours, type sizes, radii, spacing and copy below are final —
match them. Where the prototype uses a web font stack, use the Compose
equivalent in §Typography. Icons in the prototype are geometric placeholders
(chevrons, rules, circles); replace them with Material Symbols equivalents at the
same size and colour, and do not add icons that aren’t specified.

---

## Design tokens

### Colour — light (`Cadastre`)
| Token | Hex | Use |
|---|---|---|
| `bg` | `#E9EEEC` | screen background (“field”) |
| `surface` | `#FBFCFB` | cards, tiles, app-bar, fields |
| `surfaceAlt` | `#DFE6E4` | pressed states, progress track, chip fill |
| `ink` | `#0F1614` | primary text |
| `ink2` | `#4B5754` | secondary text |
| `ink3` | `#7C8785` | tertiary / labels / placeholders |
| `line` | `#C9D3D0` | 1dp borders |
| `hair` | `#DCE3E1` | 1dp dashed insets, blueprint grid |
| `accent` | `#B4531B` | primary action, active state (surveyor ochre) |
| `accentSoft` | `#F7E7DA` | accent tint fill (selected chips, badges) |
| `onAccent` | `#FFF9F4` | text/icon on accent |

### Colour — dark
| Token | Hex |
|---|---|
| `bg` | `#0D1214` |
| `surface` | `#151B1E` |
| `surfaceAlt` | `#1E2629` |
| `ink` | `#E9EEEE` |
| `ink2` | `#A2AFB0` |
| `ink3` | `#6F7C7E` |
| `line` | `#283336` |
| `hair` | `#212A2D` |
| `accent` | `#E58A55` |
| `accentSoft` | `#2A1C13` |
| `onAccent` | `#150D07` |

Both themes are first-class; follow the system setting by default, overridable in
Settings. `android/app/src/main/res/values/colors.xml` and `values-night/colors.xml`
should be updated to `#E9EEEC` / `#0D1214` for `window_background`.

### Typography
Three families, bundled as app assets (no CDN):
- **Space Grotesk** (500/600/700) — headings, body, buttons, Latin UI text.
- **IBM Plex Mono** (400/500) — survey numbers, counts, all-caps labels, paths, file paths, record-type sub-labels.
- **Noto Sans Gujarati** (400/600) — all Gujarati. Set as the fallback in the same
  `FontFamily` so mixed strings render without boxes (see `docs/PROJECT_NOTES.md` —
  the same trap as the PDF renderer).

Scale for the approved **comfy** density (sp):

| Role | Size | Weight | Tracking | Notes |
|---|---|---|---|---|
| Screen title (`h1`) | 22 | 700 | −0.02em | Library, Add, Settings, Saving |
| Survey number (hero) | 34 | 500 | −0.03em | Mono, in the parcel plate |
| Survey number (tile) | 24 | 500 | −0.02em | Mono |
| Body / row label | 15 | 400–600 | 0 | record names, buttons, pickers |
| Metadata | 13 | 400 | 0 | area, tenure, as-of |
| Mini / all-caps label | 11 | 400–500 | 0.10–0.16em | Mono, uppercase |

Other densities (available in the prototype, not shipped): `default` 13.5/11/9.5,
`compact` 12.5/10/9, with 2-up tiles.

### Spacing, radius, sizes (comfy)
- Base unit **4dp**. Screen padding **20dp**. Card/tile padding **15dp**. Vertical gap between cards **12dp**.
- Radius: tile/card **12dp** · pill/button **20dp** (fully rounded) · field & icon button **10dp** · stamp **4dp** · parcel plate **12dp** (inner dashed **8dp**).
- Primary button height **56dp**. Field height **48dp**. Icon button **34–36dp**. Minimum touch target **48dp** everywhere.
- **No elevation anywhere.** Separation is 1dp borders (`line`) — Material 3 `CardDefaults.outlinedCardColors`, elevation 0.

### Signature elements (keep these — they carry the direction)
1. **Parcel tile / plate** — every survey is drawn as a plot: 1dp solid outer
   border, radius 12dp, plus a **1dp dashed inset** at 5dp with radius 8dp
   (colour `hair` on tiles, `line` on the detail plate). Nothing else is dashed
   except the “Get more records” button and the “not fetched” divider.
2. **Stamp strip** — four 23×21dp slots in a row, radius 4dp, mono 8.5sp:
   `I` Integrated · `V` VF-7/12 · `D` Deeds · `C` iRCMS cases. Held = `accent`
   border + `accentSoft` fill + `accent` text; missing = `line` border,
   transparent fill, `ink3` text. When a type has more than one document the
   count is appended in the current numeral system (`V૧૦`, `C૧૪`).
3. **Blueprint grid** — headers on Library and Survey detail use `surface` with a
   22dp grid of 1dp `hair` lines (two repeating linear gradients / a tiled drawable).

---

## Language mode (new — not in the original spec)
A three-way app-language setting: **`gu`** (Gujarati only) · **`both`** (default:
Gujarati primary, English helper) · **`en`** (English only).

- Reachable two ways: a pill in the Library header showing `ગુ` / `ગુ/EN` / `EN`
  that **cycles** on tap, and a segmented control in Settings.
- It changes **app chrome only**. Land data — survey numbers, area, assessment,
  tenure, land use, dates — always keeps its Gujarati numerals; in `both` mode a
  Latin helper line sits under it, in `en` mode the Latin value is promoted, in
  `gu` mode the helper is hidden. Settings states this: “Land data stays in
  Gujarati — this changes the app’s own labels.”
- Implement as a persisted preference read into a `CompositionLocal`, with the
  label pair helper:
  `fun L(gu: String, en: String) = when (lang) { GU -> gu; EN -> en; BOTH -> "$gu · $en" }`
  All strings live in `strings.xml` as `_gu` / `_en` pairs (the file already has
  `fetch_banner` / `fetch_banner_gu` in this shape).

---

## Screens

### 1. Library (home) — start destination
**Purpose:** browse saved records. Opens here, never on a form.

Layout, top to bottom:
1. **Header block** — `surface`, blueprint grid, 1dp bottom `line`, padding 16/20/14.
   - Title `તમારા જમીન રેકોર્ડ` (22sp/700). Sub-line mono 11sp uppercase `ink3`:
     `Your land records · 15 surveys` (in `gu`: `૧૫ સર્વે`; in `en`: `15 surveys`).
   - Right: language pill (36dp tall, radius 10, 1dp `line`) + settings icon button (36dp).
   - **Search field** below, 44dp, radius 10, 1dp `line`, `surface` fill, leading
     11dp circle outline, placeholder `સર્વે નંબર કે ગામ શોધો`. Search matches
     survey number or village (`SurveyDao` on `normalized` + village name).
2. **Path breadcrumb** — pills, 13sp, radius 20, joined by 10×1dp rules:
   `ગુજરાત › આણંદ › ઉમરેઠ › ભરોડા`. The **last** pill is filled `accent`/`onAccent`;
   the rest are `surface` with a `line` border. No trailing rule.
3. **Village switcher** — a row of equal-width cards (radius 10, 1dp border):
   `ભરોડા` (`Bharoda · 9`), `સુંદલપુરા` (`Sundalpura · 5`), `વળેટવા` (`Valetva · 1`).
   Selected = `accentSoft` fill, `accent` border and text. Selecting a village
   changes the breadcrumb (Valetva is under **ખેડા › નડિયાદ ગ્રામ્ય**) and the list.
4. **Survey list** — section label (mono 11sp uppercase, `સર્વે · Surveys`) with the
   count right-aligned; then **one parcel tile per row** (comfy). Each tile:
   survey number (mono 24sp) · area (13sp Gujarati numerals, e.g. `૩-૩૧-૮૪`) ·
   Latin helper (mono 11sp `3-31-84 ha-a-m²`) · tenure (13sp `ink2`, one line,
   ellipsised) · stamp strip. Tapping opens Survey detail; press state = `surfaceAlt`.
   Surveys with no fetched metadata show `—` and no tenure line (never invented data).
   Queued villages show their numbers as tiles with all-hollow stamps and
   `રેકોર્ડ બાકી · No records yet`; tapping one goes straight to Fetch.
5. **Primary action** — full-width 56dp `accent` button, radius 12:
   `મિલકત ઉમેરો · Add property`.

### 2. Survey detail
**Purpose:** see and act on one survey’s documents.

1. **Header** — `surface` + blueprint grid: back icon button (34dp) and the
   breadcrumb text `આણંદ › ઉમરેઠ › ભરોડા`; below, the **parcel plate** (bordered,
   dashed inset, `bg` fill) holding mono label `સર્વે` and the number at 34sp,
   with village name + `Bharoda · Umreth` beside it.
2. **Metadata strip** — wrapping row of small bordered cards (radius 8, padding
   7/10): `કુલ ક્ષેત્રફળ` `૩-૩૧-૮૪` / `3-31-84 ha-a-m²` · `આકાર` `૨૪.૬૯` / `24.69` ·
   `સત્તાપ્રકાર` `બીન ખેતી પ્રિપાત્ર` / `Non-agri eligible` · `જમીનનો ઉપયોગ` `ખેતીલાયક` /
   `Agricultural` · `ની સ્થિતિએ` `૧૨ જુલાઈ ૨૦૨૬` / `12 Jul 2026`. Unknown → `—`.
3. **Record cards** — one per `RecordType`, in enum order, radius 12, 1dp `line`:
   - 3dp vertical rail at the left edge: `accent` when held, `line` when missing.
   - Name (15sp/600) + mono uppercase English sub-line (hidden in `gu`/`en` modes).
   - Right: document count (mono 20sp) over a mono 11sp uppercase unit
     (`1 document`, `10 period scans`, `14 cases`); missing = `—` + `none`, `ink3`.
   - Held: as-of line (`ની સ્થિતિએ ૧૨ જુલાઈ ૨૦૨૬ · 12 Jul 2026`) then a pill row —
     **જુઓ · View** (filled `accent`), **ફરી PDF · Re-generate PDF**, **શેર · Share**
     (both outlined). At comfy density these wrap to full-width rows; that’s intended.
   - Missing: 1dp dashed top divider, `bg` fill, mono `Not fetched yet` on the left
     and an outlined **મેળવો · Get record** pill on the right.
   - A record captured in this session gets a `હમણાં ઉમેર્યું · Just added` badge
     (radius 20, `accentSoft`/`accent`) under its name until the screen is left.
4. **વધુ રેકોર્ડ મેળવો · Get more records** — full-width 56dp button with a **1dp
   dashed `accent` border**, transparent fill.

### 3. Fetch (the WebView step)
**Purpose:** the one human action — solve the CAPTCHA.

1. Compact app bar (`surface`): back, `રેકોર્ડ મેળવો · 221/p`, and the host
   `anyror.gujarat.gov.in` in mono 11sp `ink3` — the user must see they’re on the
   real government site.
2. **Slim banner**, full-bleed `accent` / `onAccent`, 12/16 padding:
   `નીચે દેખાતો કોડ લખો અને 'Get Record Detail' દબાવો` (15sp) over
   `Type the code below, then tap Get Record Detail` (mono 11sp, 85% alpha).
3. **The live WebView** below. The injected script must:
   - pre-select record type + district/taluka/village/survey (the proven cascade in
     `anyror/*.mjs`) and render those fields as **filled and locked** — reduced
     opacity, disabled, each with a small `auto` tag;
   - **dim everything else** on the page to ~30–42% opacity;
   - **spotlight** the CAPTCHA image, its input and the *Get Record Detail* button:
     full opacity, on a `#FBFCFB` card, radius 12, with a slow 2.4s pulsing
     `accentSoft` ring (3px → 7px). The submit button is restyled to the app’s
     `accent` at 48dp/radius 10.
   Never auto-solve the CAPTCHA (`docs/APP_SPEC.md` §9).
4. On submit the app captures the result automatically — no second confirmation.

### 4. Saving state
Full-screen, centred, on `bg`: a parcel plate containing mono `221/p · Bharoda`,
`તમારી લાઇબ્રેરીમાં સાચવી રહ્યાં છીએ…` (22sp/600), `Saving to your library…` (mono
13sp), a 4dp progress bar (`accent` on `surfaceAlt`, indeterminate/determinate),
and three step rows with 5dp square bullets: `પાનું વાંચી રહ્યાં છીએ · Reading the page`,
`PDF બનાવી રહ્યાં છીએ · Building the PDF`, `લાઇબ્રેરીમાં ફાઇલ કરી રહ્યાં છીએ · Filing in
your library`. Under the plate, the destination path in mono 9sp:
`Documents/LandRecords/Anand/Umreth/Bharoda/Survey 221_P`.
Steps tick off as the real work completes; on completion navigate to Survey detail
with the new record marked *Just added*. On failure, keep the user here with a
plain-language error and a Retry — never dump to a blank library.

### 5. Add / Edit property
Back + title `મિલકત ઉમેરો · Add property`. Four picker rows (bordered cards, radius
10, 48dp+): mono uppercase label on the left (`State`, `District`, `Taluka`,
`Village`), value 15sp/600 with Latin helper, trailing chevron. Each opens a
searchable bottom-sheet list (source: `survey-catalog.json`).
Then `સર્વે નંબર · Survey numbers`: existing numbers as removable chips (radius 20,
mono 13sp, 18dp `×` button on `surfaceAlt`), a 48dp input
`સર્વે નંબર ઉમેરો · Add survey number` with a 66dp **ઉમેરો · Add** button, and a
full-width **સાચવો · Save**. Editing an existing property reuses this screen.

### 6. Settings
Back + `સેટિંગ્સ`. Bordered cards, 8dp apart:
1. **ભાષા · Language** — three pills `ગુજરાતી` / `બંને · Both` / `English`, selected =
   `accentSoft`; caption: “Land data stays in Gujarati — this changes the app’s own labels.”
2. **સંગ્રહ સ્થાન · Storage location** — mono `Documents/LandRecords`, caption
   “Visible in Files · backs up to Drive”, tapping opens SAF folder picker.
3. **થીમ · Theme** — `દિવસ · Light` / `રાત્રિ · Dark` pills (add System if desired).
4. Rows with chevrons: `બેકઅપ અને નિકાસ · Backup & export`,
   `PDF લેઆઉટ · PDF layout · re-generate all`, `એપ વિશે · About`.
5. Footer, mono 9sp: `Land Records 0.1 · sideloaded · for family use`.

---

## Interactions & behaviour
- **Flow:** Library → (village pill switches list) → tile → Survey detail →
  *Get record* / *Get more records* → Fetch → user solves CAPTCHA → Saving →
  back to Survey detail with the new record highlighted. Add property and
  Settings are pushed from the Library header/primary action. System back always
  pops one level; from Fetch it cancels the fetch.
- **Motion:** screen enter = 220ms fade + 7dp upward slide, ease-out. Press
  feedback = background to `surfaceAlt` (no ripple colour change, no scale).
  CAPTCHA spotlight ring = 2.4s ease-in-out loop. Caret blink 1.1s step.
  Everything must respect *Remove animations* / reduced-motion — drop to instant.
- **Offline:** everything except Fetch works with no network; no spinners on
  library reads.
- **Share:** `View` opens the in-app PDF viewer; `Share` opens the Android share
  sheet on the file (or the survey folder from an overflow); `Re-generate PDF`
  re-renders from the stored `.source/` HTML with no network and no CAPTCHA
  (`docs/APP_SPEC.md` §4D) and shows a brief inline progress state on the card.

## State
Per screen (hoist into ViewModels backed by Room):
- Library: `village` (selected), `query`, list of surveys with counts per record type.
- Survey detail: `surveyId`, records + doc counts + as-of, `justAddedRecordType`.
- Fetch: `recordType`, prefilled cascade values, `phase` = idle | solving | capturing | saving | done | error.
- Add/Edit: picker selections, `surveyNumbers` list, text input.
- App-wide (persisted): `lang` (gu | both | en), `theme` (system | light | dark), storage tree Uri.

## Assets
No bitmap assets. Fonts (Space Grotesk, IBM Plex Mono, Noto Sans Gujarati — all
SIL OFL) must be bundled in `res/font/`. Icons: Material Symbols (Outlined) at
20–24dp in `ink2` — back chevron, menu, search, share, refresh, chevron-right.
The launcher icon stays as-is but its background should move from `#2E5D50` to
`#B4531B` to match.

## Files in this bundle
- `prototype/Land Records - Android UI.dc.html` — the full comparison document: turn 2 (approved, three densities) and turn 1 (both original directions, for context).
- `prototype/CadastrePhone.dc.html` — the approved direction as a standalone, prop-driven prototype (`density` / `theme` / `lang`). **This is the reference to build against; use `density="comfy"`.**
- `prototype/android-frame.jsx`, `prototype/support.js` — support files so the prototype runs offline in a browser.

## Suggested build order (fits `docs/APP_SPEC.md` §10)
1. Theme layer: `ui/theme/Color.kt`, `Type.kt`, `Dimens.kt`, `LocalLang` + `L()` helper; bundle fonts; update `colors.xml` / `values-night/colors.xml`.
2. Shared components: `ParcelTile`, `ParcelPlate`, `StampStrip`, `MetaChip`, `RecordCard`, `PathBreadcrumb`, `BlueprintHeader`, `PrimaryButton` / `DashedButton`, `RemovableChip`.
3. Library screen against seeded Room data, then Survey detail.
4. Fetch screen: WebView chrome, banner, injected dim/spotlight CSS, capture hand-off, Saving screen.
5. Add/Edit property, Settings (language + theme wired to the persisted preference).
