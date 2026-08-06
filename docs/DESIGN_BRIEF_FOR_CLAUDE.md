# Design brief — paste this into claude.ai to generate the mockup

> Everything between the two `=====` lines is the prompt. Paste it into a new claude.ai chat and ask for an
> Artifact. When you have a look you like, send the Artifact (or the code) back here and I'll build the
> Compose UI to match.

=====================================================================================================

You are my design lead. Design the UI for a **native Android app** and deliver it as a **single, self‑contained interactive HTML Artifact** (a clickable prototype), plus a short **design‑tokens** note at the end so it can be rebuilt in Jetpack Compose.

## What the app is
A **calm, organized library for Gujarat land records** (from the AnyRoR + iRCMS government portals). Today these PDFs get dumped into WhatsApp and lost; this app becomes the *home* for the files — browse, view, share — and WhatsApp is only a share target. The primary users are me and my **father, who is not technical**. It must feel **trustworthy, obvious, and quiet** — like a well‑kept filing cabinet, not a developer tool. It is for our own family land — personal scale, not a bulk tool.

## Design principles (these are the product)
- **Library‑first.** Opening the app lands on the saved records, never a form.
- **Show only what's needed.** No dashboards of knobs. Two things happen: *browsing saved records* (almost always) and *getting a new one* (occasionally). Design for those two; hide everything else.
- **One human step, clearly framed.** The only unavoidable manual action when fetching is solving a CAPTCHA. Present it cleanly; automate everything around it.
- **Bilingual & legible.** Real land data in **Gujarati**, with an **English** helper line for me. Real names, real numbers — treat them with care. Clean typography, generous spacing.
- **Trustworthy over flashy.** This is official family property data. Calm, legible, confidence‑inspiring. Not a startup landing page.
- **Offline‑first feel.** Saved records open instantly; fetching is the only "online" moment.

## Screens to design (phone, ~390×844, show it inside a simple Android phone frame)
1. **Library (home)** — the default. A browsable hierarchy (Gujarat › District › Taluka › Village › Survey) plus a **search** field and a **Recents** strip. Show the *village* level open, listing its surveys as clean cards. Each survey card shows the survey number big, and small metadata (total area, tenure) + little count chips for how many record types it holds. A calm header, no clutter. A single primary action to **Add property**.
2. **Survey detail** — pick one survey (221/p). Header with the survey number + a compact bilingual metadata strip (area / assessment / tenure / land‑use / "as of" date). Below: the survey's records as **cards, one per record type** — *Integrated Survey Record*, *Old Scanned VF‑7/12*, *Registered Deeds*, *iRCMS Cases* — each showing #docs and an "as of" date, with per‑card actions **View · Re‑generate PDF · Share**. Record types that don't exist yet show a quiet **"Get record"** state instead. One clear **"Get more records"** action.
3. **Fetch (the WebView step)** — this is the AnyRoR government web page shown *inside* the app, **pre‑filled** (record type + district/taluka/village/survey already selected — show them as filled, locked-looking fields). A **slim banner** at top states the one human task. The CAPTCHA box and the "Get Record Detail" button are gently **spotlighted**. Everything else on the page is dimmed. After the tap, the app captures the result automatically (show a brief "Saving to your library…" progress state as a second frame).
4. **Add / Edit property** — pickers for State › District › Taluka › Village, then a **survey‑number list editor**: existing numbers shown as removable chips, plus an "Add survey number" input. Save button.
5. **Settings** (minimal) — storage location (`Documents/LandRecords`), layout/theme, backup/export, about. One quiet screen.

## Interactions
Make it a **clickable prototype**: tapping a village opens its surveys; tapping a survey opens its detail; a "Get record" / "Get more" opens the Fetch screen; the Fetch "Get Record Detail" advances to the saving state and then to the new record in the library. Tasteful motion only (gentle screen transitions, card press feedback). Respect reduced‑motion. Light **and** dark theme, both first‑class.

## Aesthetic direction — offer me **two** distinct directions to choose from
Both must feel trustworthy and calm and handle Gujarati + Latin type beautifully. Avoid the AI‑default looks (cream‑and‑terracotta serif, neon‑on‑black, generic purple gradient, Inter‑everywhere, emoji as icons, everything rounded). Ground the choices in the subject: official Gujarat land records — think land, soil, survey maps, cadastral linework, official seals, ledger paper — reinterpreted warmly and modernly for a phone. Pick a real display/body type pairing that supports **Noto Sans Gujarati** (or similar) for the Gujarati and a complementary Latin face; inline fonts as data URIs so nothing depends on a CDN. Choose neutrals with a slight hue bias, not flat grey. Pick one memorable signature element (e.g. how a survey/record card is rendered, or how the hierarchy breadcrumb works) and let everything else stay quiet.

## Real content to use (NO lorem — use these exact names/numbers, Gujarati + English)
**Location:** Gujarat › **Anand (આણંદ)** › **Umreth (ઉમરેઠ)** › **Bharoda (ભરોડા)** — has 9 surveys with records. Also under Umreth: **Sundalpura (સુંદલપુરા)** — 5 surveys queued (no records yet). And Gujarat › **Kheda (ખેડા)** › **Nadiad Gramya (નડિયાદ ગ્રામ્ય)** › **Valetva (વળેટવા)** — 1 survey queued.

**Bharoda surveys (survey no · what it holds):**
- **221/p** — Integrated ✓, Old VF‑7/12 ✓ (10 period scans), iRCMS ✓ (14 cases). Area **૩-૩૧-૮૪** (3‑31‑84 હે‑આ‑ચોમી / H‑A‑sqm), Assessment **૨૪.૬૯**, Tenure **બીન ખેતી પ્રિપાત્ર** (Non‑agri eligible), Land use **ખેતીલાયક** (Agricultural).
- **222/1** — Integrated ✓, VF‑7/12 ✓, iRCMS ✓ (2 cases). Area **૦-૩૦-૩પ**, Assessment **૨.૧૨**, Tenure **જુની શરત (જુ.શ)** (Old tenure), Land use **ખેતીલાયક**.
- **222/2/p** — Integrated ✓, VF‑7/12 ✓, iRCMS ✓ (9 cases).
- **222/3/p1** — Integrated ✓, VF‑7/12 ✓, iRCMS ✓ (1 case).
- **222/3/p2** — Integrated ✓, VF‑7/12 ✓, iRCMS — none.
- **226/p1** — Integrated ✓, VF‑7/12 ✓, iRCMS — none.
- **228/p1/p** — Integrated ✓, VF‑7/12 ✓, iRCMS ✓ (2 cases).
- **229/p** — Integrated ✓, VF‑7/12 ✓, iRCMS ✓ (1 case).
- **230/p1/p3** — Integrated ✓, VF‑7/12 ✓, iRCMS — none. Area **૦-૪૭-૦૪**, Assessment **૩.૪૮**, Tenure **જુની શરત (જુ.શ)**.

**Sundalpura queued surveys (no records yet):** 906, 845/અ, 851, 901/p, 902.
**Valetva queued survey:** 41.

**Record‑type labels (Gujarati · English):**
- **સંકલિત સર્વે રેકોર્ડ · Integrated Survey Record (7/12)** — the full current record.
- **જૂનું સ્કેન થયેલ ૭/૧૨ · Old Scanned VF‑7/12** — historical scanned pages by period.
- **નોંધાયેલ દસ્તાવેજ · Registered Deeds** — sub‑registrar deed documents.
- **જમીન કેસ (iRCMS) · Land Cases (iRCMS)** — revenue/RTS case + order documents.

**Metadata chip labels (Gujarati · English):** કુલ ક્ષેત્રફળ · Total area · આકાર · Assessment · સત્તાપ્રકાર · Tenure · જમીનનો ઉપયોગ · Land use · ની સ્થિતિએ · As of.
Gujarat land area is written **Hectare‑Are‑SqMetre** (e.g. 3‑31‑84). Keep Gujarati numerals where shown, with the Latin equivalent as the small helper line.

**Fetch banner copy (Gujarati · English):** "નીચે દેખાતો કોડ લખો અને 'Get Record Detail' દબાવો · Type the code shown below, then tap Get Record Detail." Saving state: "તમારી લાઇબ્રેરીમાં સાચવી રહ્યાં છીએ… · Saving to your library…".

## Deliverable format
- One self‑contained HTML Artifact, mobile‑framed, clickable between the screens above, light + dark.
- End with a compact **Design tokens** block: the palette (4–6 named hex), the type pairing + scale, corner radius, spacing rhythm, and the card/chip/banner component specs — so this can be rebuilt in Jetpack Compose. Keep it real and specific.

=====================================================================================================

## After you pick a look
Send me the Artifact link (or paste its code / a screenshot) here. I'll lift the tokens and components into the Compose app and build every screen to match, then wire up the real library data, the WebView fetch engine, and PDF capture per the master spec.
