# Land Records — Android App Plan

Goal: you and your dad self-serve these records from a phone, and the app becomes the **organized
home for the files** — a proper save/browse library, so **WhatsApp is only for sharing, never storage.**

## The key idea: a WebView, not a scraper

The app embeds a normal browser (Android **WebView**) and loads AnyRoR inside it. That single choice
dissolves everything we fought today:

- Runs on the **phone's own mobile-data IP** → residential, not the blocked home IP.
- A WebView **is a real browser** → no headless fingerprint, no WAF rejection.
- You solve the **CAPTCHA right in the view** → human-in-the-loop, exactly as the portal expects.
- No servers, no VPN, no relay. The app just automates the boring parts around your one tap.

The app injects the same cascade logic we already wrote (select record type, fill
district/taluka/village/survey), you solve the CAPTCHA + tap "Get Record Detail", and the app captures
the result, builds the PDF **on the phone**, and files it in the library.

## The library (the part you actually asked for)

A browsable hierarchy, stored on the device, backup-able:

```
Gujarat / <District> / <Taluka> / <Village> / Survey <No> /
    Integrated Record.pdf
    VF-7-12 (old scanned).pdf
    Deeds / <year>-<doc-no>.pdf …
    iRCMS Cases / Case NN.pdf …
```

- **Browse** by folder/cards down the hierarchy; **view** any PDF in-app.
- Each survey shows metadata at a glance: area, tenure, land-use, "as of" date, #deeds, #cases.
- **Search** by survey number or village.
- Files live in a visible `Documents/LandRecords/…` folder (so they survive, sync to Drive, and are
  yours) — not locked inside the app.
- **Share** button on any file or folder → Android share sheet → WhatsApp / Drive / email. WhatsApp
  becomes optional and one-tap, never the store.
- A saved **survey list per village** so a refresh is: pick village → tap each survey → solve CAPTCHA → done.

## How each record type is captured on-device

- **Integrated record** — inject our `format.mjs` cleanup CSS into the WebView, then WebView **print-to-PDF**
  (Android `PrintManager`/`PdfDocument`). Reuses the exact layout we already tuned (9.4pt, balanced
  columns, compact header, no empty sections).
- **VF-7/12** — JS `fetch` each `PDFView1.aspx?detail=…` through the WebView's cookies → blob → weed
  placeholders (text-layer check) → combine newest→oldest with year labels.
- **Deeds** — fetch the "View Deed" **TIFF**, decode it (TIFF lib), embed as a page → PDF.
- **iRCMS** — separate site/flow, added later (its CAPTCHA reuses across surveys, so it's easier).

## Tech stack (proposed)

Kotlin + Jetpack Compose · embedded WebView (full control of cookies + JS injection + print-to-PDF) ·
Room DB for metadata/search · a PDF lib (combine/merge) · a TIFF decoder for deeds. The scraping/cascade
JS is kept as a **remotely-updatable config** so when AnyRoR changes its page, selectors are fixed
without shipping a new app.

## Honest constraints

- **CAPTCHA stays a human tap** — one per survey per record type. By design, and the right thing.
- **Site changes break selectors** → occasional maintenance (mitigated by the updatable-JS config).
- TIFF/PDF handling adds some app size + code.
- **Personal use only** — your family's land. Keep it personal-scale; not a bulk-harvest tool. Distribute
  as a **sideloaded APK** for you + dad (not the Play Store, which scrutinizes portal automation).

## Build phases

1. **MVP** — WebView + saved survey list + cascade injection + **Integrated record → PDF** + the
   **organized library (browse/view/share)**. This alone replaces the WhatsApp-as-storage pain.
2. **VF-7/12** fetch + combine + weeding.
3. **Deeds** (TIFF → PDF).
4. **iRCMS** cases.
5. Polish — search, metadata cards, Drive backup/export, updatable-selectors config.

## Decisions (LOCKED)

1. **Native Android (Kotlin)** + Jetpack Compose + WebView.
2. **Full scope**: Integrated + VF-7/12 + Deeds (TIFF→PDF) + iRCMS, all in the app. Gujarat/AnyRoR first,
   hierarchy kept generic for other states later.
3. Files in a **visible `Documents/LandRecords/…` folder** (backup-able, survives uninstall).
4. **Sideloaded APK** (not Play Store).
5. **Testing:** `adb install` to the user's **tab + Pixel** first; only after that goes to dad.

## Toolchain (present on this machine)

Java 21 · Android SDK at `~/Android/Sdk` · `adb` · Android Studio at `/opt/android-studio`. Build via the
Gradle wrapper → debug APK → `adb install` to tab/Pixel. (No standalone `gradle`/`kotlinc`; wrapper handles it.)

## Build order (each slice is testable on-device before the next)

0. **Scaffold** — Gradle project, Compose shell, permissions, `Documents/LandRecords` storage, Room DB.
1. **Library first** — browse the hierarchy (state→…→survey→type), view a PDF, share via the Android
   sheet. Seed with the PDFs we already generated so save/browse works before any fetching.
2. **AnyRoR WebView engine** — embedded WebView + injected cascade (record type, dist/tal/vil/survey from
   a saved list), you solve the CAPTCHA, reach the detail page. This is the highest-risk piece → validate
   on the Pixel (real mobile IP) early.
3. **Integrated record** → clean PDF (inject `format.mjs` CSS, print-to-PDF) → filed in the library.
4. **VF-7/12** → fetch period scans, weed placeholders, combine.
5. **Deeds** → TIFF fetch + decode → PDF.
6. **iRCMS** cases (separate site; CAPTCHA reuses).
7. Polish — search, metadata cards, Drive export.

Reality: full on-device validation of steps 2–6 needs the **phone hotspot** (residential IP) since that's
what makes AnyRoR load; the app engine reuses the exact cascade/weeding/format logic already proven in
`anyror/*.mjs`.
