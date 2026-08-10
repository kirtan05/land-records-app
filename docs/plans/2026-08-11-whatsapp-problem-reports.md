# Plan — problem reports arrive automatically over WhatsApp

**Status:** proposed, not started.

**Assumption being made explicit:** "WhatsApp auto bugfix" is read as *the bug report
should reach Kirtan by itself, over WhatsApp, instead of the user having to send an
email*. It is **not** read as the app fixing bugs on its own.

---

## Today

Settings → "Report a problem" (`DiagnosticsReport.kt`) builds a diagnostics file —
version, device, crash stacks persisted by `LandRecordsApp`, recent logs — and fires an
`ACTION_SEND` intent to `kirtanjain0504@gmail.com` with it attached.

That means the report only arrives if the user **picks an email app, and taps send**. For
the actual user of this app, a report that requires three deliberate steps after
something already went wrong is a report that never arrives. The failures we most want to
see (a CAPTCHA loop, a fetch that silently produced nothing) are exactly the ones where
he will put the phone down instead.

## What should happen

Tap once → the report is delivered → the app says "sent". Nothing else.

The repo already has a working WhatsApp path: `wa/` (Baileys) with an authenticated
session, used to deliver record bundles.

### Shape

```
app  --HTTPS POST-->  small relay (already-authenticated Baileys session)  --> WhatsApp
```

The app must **not** hold WhatsApp credentials — Baileys is a full account session; on a
phone that is both a security problem and a ban risk. So:

- App posts the diagnostics bundle to a relay endpoint with a shared token.
- Relay is the existing `wa/` session (or a WhatsApp Business API number, which is the
  supportable version if this ever leaves the family).
- Relay forwards to Kirtan's number with the version + a one-line summary as the caption.

### Fallbacks, in order

1. WhatsApp relay (silent, one tap).
2. If the relay is unreachable, **queue it on disk and retry** on next launch — a report
   about a network failure must survive the network failure.
3. The current email intent stays as the manual escape hatch.

### Auto-capture, with consent

The highest-value reports are the ones nobody thinks to send. On an unhandled crash,
persist the stack (already done) and on next launch offer **"Something went wrong last
time — send the details?"** with one button. Default to asking, not to silent upload:
this app holds personal land records, and a diagnostics bundle that ever included record
content would be a privacy breach, not a feature.

## Non-negotiables

- **Never include land data.** Diagnostics = version, device, stack traces, log lines.
  Assert this in a test over the bundle's contents — a log line that happens to contain a
  survey number or an owner's name must be scrubbed.
- **No credentials in the APK.** A relay token, scoped and revocable — not a WhatsApp
  session.
- **Silent by default only for crashes the user already saw**, and only after they agree
  once.

## Open questions

- Is a relay worth standing up, or is a pre-filled WhatsApp share intent
  (`https://wa.me/<number>?text=…` plus the file) enough? That is one tap fewer than
  email and needs no server — probably the right first version, and it should be built
  before the relay.
- Where does the relay run — the work Ubuntu box (see the `adbrelay` skill) or a small
  always-on host? The box is not always reachable, which argues for retry-on-disk
  regardless.
