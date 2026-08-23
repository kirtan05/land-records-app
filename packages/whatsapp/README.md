# `packages/whatsapp/` — WhatsApp delivery (Baileys)

Sends generated land-record PDFs to a family WhatsApp group. Optional: the
Android app's share sheet is replacing this path.

| Script | What it does |
|---|---|
| `login.mjs` | Pairs a session — prints a QR to the terminal and writes `packages/whatsapp/qr.png`, then lists groups. |
| `check.mjs`, `check-auth.mjs`, `wa-check-any.mjs` | Read-only session health checks. Report `AUTHED` / `NEEDS_SCAN`, send nothing. |
| `verify.mjs` | Read-only: check whether phone numbers are registered on WhatsApp. |
| `list-group-participants.mjs` | Read-only: list a group's participants. |
| `send.mjs` | Sends the Bharoda record set to a group. |
| `send-anyror.mjs` | Sends AnyRoR Integrated Survey Record PDFs, one per survey. |
| `send-vf712.mjs` | Sends the old scanned VF-7/12 combined PDFs, one per survey. |
| `send-bhalej.mjs` | Sends Bhalej "complete record" PDFs (cover → integrated → each હસ્તલિખિત નોંધ scan). |
| `notify.mjs` | Generic one-shot text sender for alerts from any script. |

## Setup

```bash
node packages/whatsapp/login.mjs          # scan the QR with WhatsApp → Linked devices
node packages/whatsapp/check.mjs          # confirm AUTHED
node packages/whatsapp/send-anyror.mjs --dry-run    # always dry-run first
```

Every sender supports `--dry-run` (prints the plan, connects to nothing) and
`--group="<name>"` or an explicit `<jid>@g.us`.

## Not in version control

- `packages/whatsapp/auth/`, `packages/whatsapp/auth-fam/` — Baileys session credentials. **These are
  account credentials; never commit them.** Recreate with `login.mjs`.
- `packages/whatsapp/groups.json`, `packages/whatsapp/participants.json`, `packages/whatsapp/qr*.png` — generated, and
  contain personal contact data.

`WA_AUTH_DIR` overrides which auth store a script uses.
