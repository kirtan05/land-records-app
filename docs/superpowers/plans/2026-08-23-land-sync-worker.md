# Land Sync Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deploy the control plane — a Cloudflare Worker on `kirtanjain.com` backed by D1 and R2 — that holds the job queue, authenticates devices, and hands out presigned R2 URLs.

**Architecture:** A standalone Worker (not an addition to `spine`) serving `/land/*` on the existing zone. D1 holds three tables: devices, jobs, and an object manifest that exists so clients never call R2 `LIST`. Bulk bytes never pass through the Worker — it issues presigned S3 URLs and the phone and laptop talk to R2 directly. The job claim is a single atomic conditional `UPDATE`, which is what makes exactly-once handoff possible.

**Tech Stack:** Cloudflare Workers, D1 (SQLite), R2 (S3 API), TypeScript, `aws4fetch` for presigning, Vitest via `@cloudflare/vitest-pool-workers` running against real D1 in workerd.

**Spec:** `docs/specs/2026-08-23-r2-sync-and-compute-offload-design.md`

**Prior art:** `~/Desktop/projects/spine/worker/` — same zone, same stack, 57 passing tests. Copy its harness shape, its auth reasoning, and its canary practice. Read `spine/worker/src/auth.ts` and `spine/worker/vitest.config.ts` before Task 1.

## Global Constraints

- **Separate Worker, shared zone.** `spine` serves `/users/*`, `/syncs/*`, `/api/*`. This Worker serves `/land/*` only. Never extend spine — a bad land-records deploy must not break the reading page.
- **`compatibility_date` must not exceed the bundled workerd build date**, or local dev and tests fail with `ERR_FUTURE_COMPATIBILITY_DATE`. Bump it only alongside `npm update wrangler`.
- **No R2 `LIST`, ever.** It is a Class A op and the only cost that scales with sync frequency rather than with new data. The `object` table is the manifest. This is spec §2.1 and it is load-bearing.
- **Bulk bytes never flow through the Worker.** Presigned URLs only.
- **Secrets are never in `wrangler.jsonc`.** `AUTH_PEPPER`, `ADMIN_TOKEN`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` are set with `wrangler secret put`.
- **Auth pattern is copied from spine, the wire format is not.** HMAC-SHA-256 with a pepper, constant-time compare, HMAC computed *before* the row lookup so an unknown device costs the same as a wrong token. But spine's `X-AUTH-KEY` unsalted MD5 is inherited from kosync — this Worker uses a random per-device bearer token.
- **All timestamps are unix seconds, server clock only.** A device's clock is never trusted. (`lamport` counters are a separate, per-device monotonic sequence and are opaque integers here.)
- **Tests run against real D1 in workerd, never a mock.** The parts most likely to break — conditional-update semantics, `RETURNING` behaviour, UNIQUE violation messages — are exactly what a mock would fake.

## File Structure

All paths relative to a new `apps/land-worker/` in this monorepo.

| File | Responsibility |
|---|---|
| `wrangler.jsonc` | routes, D1 + R2 bindings, vars, canary |
| `package.json` | scripts mirroring spine's (`test`, `typecheck`, `migrate:*`, `deploy`) |
| `vitest.config.ts` | workerd pool, migrations preloaded, test secrets |
| `migrations/0001_init.sql` | `device`, `job`, `object` |
| `src/index.ts` | route table only — no business logic |
| `src/http.ts` | JSON response helpers, error shape |
| `src/auth.ts` | token hashing, constant-time compare, `authenticate` |
| `src/jobs.ts` | enqueue, claim, done, fail, list |
| `src/manifest.ts` | object registration and `since` cursor reads |
| `src/presign.ts` | aws4fetch presigned GET/PUT |
| `src/routes/devices.ts` | admin-guarded device registration |
| `src/routes/land.ts` | request parsing/validation for `/land/*` |
| `test/helpers.ts` | shared fixtures: register a device, authed fetch |
| `test/*.test.ts` | one file per src module under test |

---

### Task 1: Worker scaffold, canary route, and test harness

Nothing here is business logic. The deliverable is: a Worker that boots in workerd, runs a passing test, and can prove route precedence on the live zone before any device is pointed at it.

**Files:**
- Create: `apps/land-worker/package.json`
- Create: `apps/land-worker/wrangler.jsonc`
- Create: `apps/land-worker/tsconfig.json`
- Create: `apps/land-worker/vitest.config.ts`
- Create: `apps/land-worker/src/index.ts`
- Create: `apps/land-worker/src/http.ts`
- Create: `apps/land-worker/migrations/0001_init.sql` (empty placeholder comment; filled in Task 2)
- Test: `apps/land-worker/test/canary.test.ts`
- Modify: `package.json` (root) — add `apps/*` to `workspaces`

- [ ] **Step 1: Create the R2 bucket and D1 database**

```fish
cd ~/Desktop/projects/irmsc
npx wrangler r2 bucket create land-records
npx wrangler d1 create land-records
```

Copy the `database_id` printed by the second command — it goes into `wrangler.jsonc` in Step 3.

- [ ] **Step 2: Write `package.json`**

```json
{
  "name": "land-worker",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "test": "vitest run",
    "test:watch": "vitest",
    "typecheck": "wrangler types && tsc --noEmit",
    "types": "wrangler types",
    "migrate:local": "wrangler d1 migrations apply land-records --local",
    "migrate:remote": "wrangler d1 migrations apply land-records --remote",
    "backup": "wrangler d1 export land-records --remote --output=land-$(date +%F).sql"
  },
  "dependencies": {
    "aws4fetch": "^1.0.20"
  },
  "devDependencies": {
    "@cloudflare/vitest-pool-workers": "^0.18.8",
    "@types/node": "^26.1.1",
    "typescript": "^5",
    "vitest": "^4.1.0",
    "wrangler": "^4.114.0"
  }
}
```

- [ ] **Step 3: Write `wrangler.jsonc`**

Replace `PASTE_DATABASE_ID_FROM_STEP_1` with the id from Step 1.

```jsonc
{
  "$schema": "node_modules/wrangler/config-schema.json",
  "name": "land-worker",
  "main": "src/index.ts",
  // Must not exceed the bundled workerd's build date, or local dev and tests
  // fail with ERR_FUTURE_COMPATIBILITY_DATE. Bump alongside `npm update wrangler`.
  "compatibility_date": "2026-07-22",
  "compatibility_flags": ["nodejs_compat"],

  // Routes on the existing zone. spine already owns /users/*, /syncs/* and
  // /api/* — this Worker must never claim those. /land-canary/* proves route
  // precedence before any device is pointed here; delete once verified.
  "routes": [
    { "pattern": "kirtanjain.com/land/*", "zone_name": "kirtanjain.com" },
    { "pattern": "kirtanjain.com/land-canary/*", "zone_name": "kirtanjain.com" }
  ],

  "d1_databases": [
    {
      "binding": "DB",
      "database_name": "land-records",
      "database_id": "PASTE_DATABASE_ID_FROM_STEP_1",
      "migrations_dir": "migrations"
    }
  ],

  "r2_buckets": [
    { "binding": "BUCKET", "bucket_name": "land-records" }
  ],

  "vars": {
    // Seconds a laptop holds a claim before it may be reclaimed. Longer than
    // the slowest realistic AnyRoR fetch, short enough that a dead laptop
    // does not strand a job for an afternoon.
    "CLAIM_TTL_SECONDS": "900",
    // Lifetime of a presigned R2 URL.
    "PRESIGN_TTL_SECONDS": "900",
    // Re-open device registration. Normally off; flipped only while enrolling.
    "ALLOW_REGISTRATION": "false"
  },

  "observability": { "enabled": true, "head_sampling_rate": 1 }

  // Secrets, set with `wrangler secret put`:
  //   AUTH_PEPPER            — HMAC key for stored device tokens
  //   ADMIN_TOKEN            — guards POST /land/devices
  //   R2_ACCESS_KEY_ID       — R2 API token, for presigning
  //   R2_SECRET_ACCESS_KEY   — R2 API token secret
  //   R2_ACCOUNT_ID          — for the S3 endpoint host
}
```

- [ ] **Step 4: Write `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "es2022",
    "module": "es2022",
    "moduleResolution": "bundler",
    "lib": ["es2022"],
    "types": ["./worker-configuration.d.ts", "@cloudflare/vitest-pool-workers"],
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true
  },
  "include": ["src/**/*.ts", "test/**/*.ts", "*.ts"]
}
```

- [ ] **Step 5: Write the empty migration placeholder**

`migrations/0001_init.sql`:

```sql
-- land-worker core schema. Filled in Task 2.
-- This file must exist now so readD1Migrations() has a directory to read.
SELECT 1;
```

- [ ] **Step 6: Write `vitest.config.ts`**

```ts
import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

/**
 * Tests run against real D1 inside workerd, not a mock. The behaviour most
 * likely to break here — the atomic claim's conditional UPDATE ... RETURNING,
 * and UNIQUE violation messages on idempotent enqueue — is exactly what a
 * mock would fake into passing.
 */
export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));

  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            AUTH_PEPPER: "test-pepper-not-a-real-secret",
            ADMIN_TOKEN: "test-admin-token",
            R2_ACCESS_KEY_ID: "test-access-key",
            R2_SECRET_ACCESS_KEY: "test-secret-key",
            R2_ACCOUNT_ID: "test-account",
          },
        },
      }),
    ],
    test: { setupFiles: ["./test/setup.ts"] },
  };
});
```

- [ ] **Step 7: Write `test/setup.ts`**

```ts
import { applyD1Migrations, env } from "cloudflare:test";

// Migrations are applied once per isolate, before any test runs. Without this
// every test sees an empty database and fails on "no such table".
await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
```

- [ ] **Step 8: Write `src/http.ts`**

```ts
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** Every error the Worker returns has this shape, so clients parse one thing. */
export function error(message: string, status: number): Response {
  return json({ error: message }, status);
}
```

- [ ] **Step 9: Write the failing canary test**

`test/canary.test.ts`:

```ts
import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

describe("canary", () => {
  it("responds to a ping", async () => {
    const res = await SELF.fetch("https://land.test/land-canary/ping");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, worker: "land-worker" });
  });

  it("404s an unknown path with the standard error shape", async () => {
    const res = await SELF.fetch("https://land.test/land/nope");
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not found" });
  });
});
```

- [ ] **Step 10: Run the test to verify it fails**

```fish
cd apps/land-worker && npm install && npx vitest run test/canary.test.ts
```

Expected: FAIL — `src/index.ts` does not exist yet.

- [ ] **Step 11: Write `src/index.ts`**

```ts
import { error, json } from "./http";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.length > 1 ? url.pathname.replace(/\/+$/, "") : url.pathname;
    const method = request.method.toUpperCase();

    if (method === "GET" && path === "/land-canary/ping") {
      return json({ ok: true, worker: "land-worker" });
    }

    return error("not found", 404);
  },
} satisfies ExportedHandler<Env>;
```

- [ ] **Step 12: Run the test to verify it passes**

```fish
npx vitest run test/canary.test.ts
```

Expected: PASS, 2 tests.

- [ ] **Step 13: Add the workspace and commit**

Add `"apps/*"` to the root `package.json` `workspaces` array, so it reads `["packages/*", "apps/*"]`.

```fish
cd ~/Desktop/projects/irmsc
git add apps/land-worker package.json
git commit -m "feat(worker): scaffold land-worker with canary route and D1 test harness"
```

---

### Task 2: The schema

**Files:**
- Modify: `apps/land-worker/migrations/0001_init.sql`
- Test: `apps/land-worker/test/schema.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: tables `device`, `job`, `object`. Column names are referenced verbatim by every later task.

- [ ] **Step 1: Write the failing schema test**

`test/schema.test.ts`:

```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";

describe("schema", () => {
  it("creates the three core tables", async () => {
    const { results } = await env.DB.prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
    ).all<{ name: string }>();
    const names = results.map((r) => r.name);
    expect(names).toContain("device");
    expect(names).toContain("job");
    expect(names).toContain("object");
  });

  it("enforces one job per survey_uid", async () => {
    await env.DB.prepare(
      "INSERT INTO job (id, survey_uid, state, requested_by, attempts, created_at, updated_at) VALUES (?1, ?2, 'pending', 'dev1', 0, 1, 1)",
    ).bind("j1", "gj:15:03:029/221_P").run();

    await expect(
      env.DB.prepare(
        "INSERT INTO job (id, survey_uid, state, requested_by, attempts, created_at, updated_at) VALUES (?1, ?2, 'pending', 'dev1', 0, 1, 1)",
      ).bind("j2", "gj:15:03:029/221_P").run(),
    ).rejects.toThrow(/UNIQUE/);
  });

  it("gives objects a monotonic seq usable as a manifest cursor", async () => {
    await env.DB.prepare(
      "INSERT INTO object (key, kind, size, device_id, created_at) VALUES ('blobs/aaa', 'blob', 10, 'dev1', 1)",
    ).run();
    await env.DB.prepare(
      "INSERT INTO object (key, kind, size, device_id, created_at) VALUES ('blobs/bbb', 'blob', 10, 'dev1', 1)",
    ).run();

    const { results } = await env.DB.prepare(
      "SELECT key, seq FROM object ORDER BY seq",
    ).all<{ key: string; seq: number }>();
    expect(results[1].seq).toBeGreaterThan(results[0].seq);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/schema.test.ts
```

Expected: FAIL — "no such table: device".

- [ ] **Step 3: Write the migration**

Replace the whole of `migrations/0001_init.sql`:

```sql
-- land-worker core schema — docs/specs/2026-08-23-r2-sync-and-compute-offload-design.md §2.
-- All timestamps are unix seconds on the SERVER clock. A device's clock is never trusted.

CREATE TABLE device (
  id          TEXT PRIMARY KEY,   -- caller-visible, e.g. "laptop", "dad-phone"
  label       TEXT NOT NULL,
  role        TEXT NOT NULL,      -- 'worker' (may claim jobs) | 'client' (may not)
  token_hash  TEXT NOT NULL,      -- HMAC-SHA-256(AUTH_PEPPER, bearer token)
  created_at  INTEGER NOT NULL,
  revoked_at  INTEGER             -- non-null = revoked; the row is kept as history
);

CREATE TABLE job (
  id            TEXT PRIMARY KEY,
  -- One job per survey, forever. This UNIQUE is what makes enqueue idempotent:
  -- the phone re-announces a still-PENDING survey on every sync tick (spec §1)
  -- and must not create a second job each time.
  survey_uid    TEXT NOT NULL UNIQUE,
  state         TEXT NOT NULL,    -- 'pending' | 'done' | 'failed'
  requested_by  TEXT NOT NULL REFERENCES device(id),
  claimed_by    TEXT REFERENCES device(id),
  claim_expires INTEGER,          -- unix seconds; past = reclaimable
  attempts      INTEGER NOT NULL DEFAULT 0,
  last_error    TEXT,
  done_lamport  INTEGER,          -- the batch that carries the result
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

-- Serves the claim's inner SELECT: oldest pending first.
CREATE INDEX job_pending ON job (state, created_at);

-- The manifest. This table exists so clients never call R2 LIST (spec §2.1).
CREATE TABLE object (
  -- AUTOINCREMENT, not rowid reuse: `seq` is a cursor handed to clients, so a
  -- reused id would make a client silently skip an object.
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  key         TEXT NOT NULL UNIQUE,   -- 'blobs/<sha256>' | 'db/<device>/<lamport>.ndjson.gz'
  kind        TEXT NOT NULL,          -- 'blob' | 'batch'
  size        INTEGER NOT NULL,
  device_id   TEXT NOT NULL REFERENCES device(id),
  lamport     INTEGER,                -- batches only; NULL for blobs
  created_at  INTEGER NOT NULL
);
```

- [ ] **Step 4: Run to verify it passes**

```fish
npx vitest run test/schema.test.ts
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```fish
git add apps/land-worker/migrations apps/land-worker/test/schema.test.ts
git commit -m "feat(worker): device, job and object schema"
```

---

### Task 3: Device auth

**Files:**
- Create: `apps/land-worker/src/auth.ts`
- Create: `apps/land-worker/src/routes/devices.ts`
- Create: `apps/land-worker/test/helpers.ts`
- Modify: `apps/land-worker/src/index.ts`
- Test: `apps/land-worker/test/auth.test.ts`

**Interfaces:**
- Consumes: `device` table (Task 2), `json`/`error` (Task 1).
- Produces:
  - `hashToken(pepper: string, token: string): Promise<string>`
  - `authenticate(request: Request, env: Env): Promise<Device | null>` where `Device = { id: string; role: "worker" | "client" }`
  - `POST /land/devices` → `{ id, token }` (token shown once, never again)
  - `test/helpers.ts` exports `enroll(role?)` → `{ id, token }` and `authed(token)` → headers record.

- [ ] **Step 1: Write the failing auth test**

`test/auth.test.ts`:

```ts
import { SELF, env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { hashToken } from "../src/auth";

const ADMIN = { "x-admin-token": "test-admin-token" };

async function enroll(id: string, role = "worker") {
  const res = await SELF.fetch("https://land.test/land/devices", {
    method: "POST",
    headers: { ...ADMIN, "content-type": "application/json" },
    body: JSON.stringify({ id, label: id, role }),
  });
  return { res, body: (await res.json()) as { id: string; token: string } };
}

describe("device registration", () => {
  it("rejects an unauthenticated caller", async () => {
    const res = await SELF.fetch("https://land.test/land/devices", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ id: "x", label: "x", role: "worker" }),
    });
    expect(res.status).toBe(401);
  });

  it("issues a token and stores only its hash", async () => {
    const { res, body } = await enroll("laptop-a");
    expect(res.status).toBe(201);
    expect(body.token).toMatch(/^[0-9a-f]{64}$/);

    const row = await env.DB.prepare("SELECT token_hash FROM device WHERE id = 'laptop-a'")
      .first<{ token_hash: string }>();
    expect(row?.token_hash).toBe(await hashToken("test-pepper-not-a-real-secret", body.token));
    // The plaintext token must never be recoverable from the database.
    expect(row?.token_hash).not.toBe(body.token);
  });

  it("refuses a duplicate device id", async () => {
    await enroll("laptop-b");
    const { res } = await enroll("laptop-b");
    expect(res.status).toBe(409);
  });
});

describe("authenticate", () => {
  it("accepts a valid bearer token", async () => {
    const { body } = await enroll("laptop-c");
    const res = await SELF.fetch("https://land.test/land/whoami", {
      headers: { authorization: `Bearer ${body.token}` },
    });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ id: "laptop-c", role: "worker" });
  });

  it("rejects a wrong token", async () => {
    await enroll("laptop-d");
    const res = await SELF.fetch("https://land.test/land/whoami", {
      headers: { authorization: `Bearer ${"0".repeat(64)}` },
    });
    expect(res.status).toBe(401);
  });

  it("rejects a revoked device", async () => {
    const { body } = await enroll("laptop-e");
    await env.DB.prepare("UPDATE device SET revoked_at = 1 WHERE id = 'laptop-e'").run();
    const res = await SELF.fetch("https://land.test/land/whoami", {
      headers: { authorization: `Bearer ${body.token}` },
    });
    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/auth.test.ts
```

Expected: FAIL — cannot resolve `../src/auth`.

- [ ] **Step 3: Write `src/auth.ts`**

```ts
/**
 * Device authentication.
 *
 * The stored-credential pattern is copied from spine (HMAC-SHA-256 with a
 * pepper, constant-time compare, HMAC computed before the row lookup). The wire
 * format is deliberately NOT copied: spine's unsalted-MD5 `X-AUTH-KEY` is
 * inherited from the kosync protocol, and nothing constrains us here, so this
 * uses a random 256-bit bearer token.
 *
 * HMAC rather than argon2/bcrypt: a memory-hard KDF on every request exceeds the
 * Workers CPU budget, and the credential being protected is already a full-entropy
 * random token — it has no password to be guessed. The stored hash only needs to
 * survive a database dump, which a random single-purpose token survives fine.
 */

const encoder = new TextEncoder();

function toHex(buffer: ArrayBuffer): string {
  let out = "";
  for (const byte of new Uint8Array(buffer)) out += byte.toString(16).padStart(2, "0");
  return out;
}

/** HMAC-SHA-256(pepper, token), hex encoded. */
export async function hashToken(pepper: string, token: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(pepper),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return toHex(await crypto.subtle.sign("HMAC", key, encoder.encode(token)));
}

/** A fresh 256-bit token, hex encoded. */
export function newToken(): string {
  return toHex(crypto.getRandomValues(new Uint8Array(32)).buffer);
}

export function timingSafeEqualHex(a: string, b: string): boolean {
  const left = encoder.encode(a);
  const right = encoder.encode(b);
  // timingSafeEqual throws on a length mismatch. Both operands are fixed-width
  // SHA-256 hex, so a mismatch means malformed stored data, not a guess.
  if (left.byteLength !== right.byteLength) return false;
  return crypto.subtle.timingSafeEqual(left, right);
}

export interface Device {
  id: string;
  role: "worker" | "client";
}

/**
 * Returns the authenticated device, or null.
 *
 * The HMAC is computed before the row is read so an unknown device costs the
 * same as a known device with a wrong token.
 */
export async function authenticate(request: Request, env: Env): Promise<Device | null> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) return null;
  const token = header.slice("Bearer ".length).trim();
  if (!token) return null;

  const expected = await hashToken(env.AUTH_PEPPER, token);
  const row = await env.DB.prepare(
    "SELECT id, role, token_hash, revoked_at FROM device WHERE token_hash = ?1",
  )
    .bind(expected)
    .first<{ id: string; role: string; token_hash: string; revoked_at: number | null }>();
  if (!row || row.revoked_at !== null) return null;

  return timingSafeEqualHex(row.token_hash, expected)
    ? { id: row.id, role: row.role as Device["role"] }
    : null;
}

/** Constant-time check of the admin secret guarding device registration. */
export function isAdmin(request: Request, env: Env): boolean {
  const given = request.headers.get("x-admin-token");
  if (!given || given.length !== env.ADMIN_TOKEN.length) return false;
  return timingSafeEqualHex(toHex(encoder.encode(given).buffer), toHex(encoder.encode(env.ADMIN_TOKEN).buffer));
}
```

- [ ] **Step 4: Write `src/routes/devices.ts`**

```ts
import { isAdmin, hashToken, newToken } from "../auth";
import { error, json } from "../http";

interface Body {
  id?: string;
  label?: string;
  role?: string;
}

export async function registerDevice(request: Request, env: Env): Promise<Response> {
  if (!isAdmin(request, env)) return error("unauthorized", 401);

  const body = (await request.json().catch(() => ({}))) as Body;
  const { id, label, role } = body;
  if (!id || !label) return error("id and label are required", 400);
  if (role !== "worker" && role !== "client") return error("role must be worker or client", 400);

  const token = newToken();
  const hash = await hashToken(env.AUTH_PEPPER, token);
  const now = Math.floor(Date.now() / 1000);

  try {
    await env.DB.prepare(
      "INSERT INTO device (id, label, role, token_hash, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
    )
      .bind(id, label, role, hash, now)
      .run();
  } catch (e) {
    if (String(e).includes("UNIQUE")) return error("device already exists", 409);
    throw e;
  }

  // The only time the plaintext token is ever visible. It is not recoverable.
  return json({ id, token }, 201);
}
```

- [ ] **Step 5: Wire the routes in `src/index.ts`**

Replace the body of `fetch` with:

```ts
    if (method === "GET" && path === "/land-canary/ping") {
      return json({ ok: true, worker: "land-worker" });
    }

    if (method === "POST" && path === "/land/devices") {
      return registerDevice(request, env);
    }

    if (method === "GET" && path === "/land/whoami") {
      const device = await authenticate(request, env);
      return device ? json(device) : error("unauthorized", 401);
    }

    return error("not found", 404);
```

Add at the top of the file:

```ts
import { authenticate } from "./auth";
import { registerDevice } from "./routes/devices";
```

- [ ] **Step 6: Run to verify it passes**

```fish
npx vitest run test/auth.test.ts
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Write `test/helpers.ts`**

```ts
import { SELF } from "cloudflare:test";

const ADMIN = { "x-admin-token": "test-admin-token" };

let counter = 0;

/** Registers a fresh device and returns its id and plaintext token. */
export async function enroll(role: "worker" | "client" = "worker") {
  const id = `${role}-${++counter}`;
  const res = await SELF.fetch("https://land.test/land/devices", {
    method: "POST",
    headers: { ...ADMIN, "content-type": "application/json" },
    body: JSON.stringify({ id, label: id, role }),
  });
  if (res.status !== 201) throw new Error(`enroll failed: ${res.status}`);
  const body = (await res.json()) as { id: string; token: string };
  return body;
}

export function authed(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}`, "content-type": "application/json" };
}
```

- [ ] **Step 8: Run the full suite and commit**

```fish
npx vitest run
git add apps/land-worker/src apps/land-worker/test
git commit -m "feat(worker): per-device bearer auth and admin-guarded registration"
```

---

### Task 4: Enqueue a job

**Files:**
- Create: `apps/land-worker/src/jobs.ts`
- Create: `apps/land-worker/src/routes/land.ts`
- Modify: `apps/land-worker/src/index.ts`
- Test: `apps/land-worker/test/enqueue.test.ts`

**Interfaces:**
- Consumes: `authenticate` (Task 3), `job` table (Task 2).
- Produces:
  - `enqueue(env: Env, deviceId: string, surveyUid: string): Promise<{ id: string; state: string; created: boolean }>`
  - `POST /land/jobs` body `{ survey_uid }` → `200 { id, state, created }`

- [ ] **Step 1: Write the failing test**

`test/enqueue.test.ts`:

```ts
import { SELF, env } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { authed, enroll } from "./helpers";

const SURVEY = "gj:15:03:029/221_P";
let phone: { id: string; token: string };

beforeAll(async () => {
  phone = await enroll("client");
});

function post(token: string, survey_uid: unknown) {
  return SELF.fetch("https://land.test/land/jobs", {
    method: "POST",
    headers: authed(token),
    body: JSON.stringify({ survey_uid }),
  });
}

describe("POST /land/jobs", () => {
  it("rejects an unauthenticated caller", async () => {
    const res = await SELF.fetch("https://land.test/land/jobs", {
      method: "POST",
      body: JSON.stringify({ survey_uid: SURVEY }),
    });
    expect(res.status).toBe(401);
  });

  it("rejects a missing survey_uid", async () => {
    const res = await post(phone.token, undefined);
    expect(res.status).toBe(400);
  });

  it("creates a pending job", async () => {
    const res = await post(phone.token, SURVEY);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { state: string; created: boolean };
    expect(body.state).toBe("pending");
    expect(body.created).toBe(true);
  });

  // The phone re-announces every still-PENDING survey on each sync tick
  // (spec §1). That must never accumulate duplicate jobs.
  it("is idempotent on re-announce", async () => {
    const first = (await (await post(phone.token, SURVEY)).json()) as { id: string; created: boolean };
    const second = (await (await post(phone.token, SURVEY)).json()) as { id: string; created: boolean };
    expect(second.id).toBe(first.id);
    expect(second.created).toBe(false);

    const row = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM job WHERE survey_uid = ?1",
    ).bind(SURVEY).first<{ n: number }>();
    expect(row?.n).toBe(1);
  });

  it("re-opens a failed job instead of creating a second one", async () => {
    const uid = "gj:15:03:029/222_1";
    const first = (await (await post(phone.token, uid)).json()) as { id: string };
    await env.DB.prepare("UPDATE job SET state = 'failed' WHERE id = ?1").bind(first.id).run();

    const again = (await (await post(phone.token, uid)).json()) as { id: string; state: string };
    expect(again.id).toBe(first.id);
    expect(again.state).toBe("pending");
  });

  it("leaves a done job alone", async () => {
    const uid = "gj:15:03:029/223";
    const first = (await (await post(phone.token, uid)).json()) as { id: string };
    await env.DB.prepare("UPDATE job SET state = 'done' WHERE id = ?1").bind(first.id).run();

    const again = (await (await post(phone.token, uid)).json()) as { state: string };
    expect(again.state).toBe("done");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/enqueue.test.ts
```

Expected: FAIL — 404 from the router, so the status assertions fail.

- [ ] **Step 3: Write `src/jobs.ts`**

```ts
/** Deterministic job id, so the same survey always maps to the same row. */
async function jobId(surveyUid: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(surveyUid));
  let hex = "";
  for (const byte of new Uint8Array(digest).slice(0, 12)) hex += byte.toString(16).padStart(2, "0");
  return hex;
}

export interface EnqueueResult {
  id: string;
  state: string;
  created: boolean;
}

/**
 * Idempotent on survey_uid. The phone re-announces every still-pending survey
 * on each sync tick (spec §1), so this is called repeatedly for the same
 * survey and must converge on one row.
 *
 * A 'failed' job re-opens — a retry is the point of re-announcing. A 'done'
 * job does not: the record already exists, and re-fetching would tombstone
 * links for nothing.
 */
export async function enqueue(
  env: Env,
  deviceId: string,
  surveyUid: string,
): Promise<EnqueueResult> {
  const id = await jobId(surveyUid);
  const now = Math.floor(Date.now() / 1000);

  const existing = await env.DB.prepare("SELECT id, state FROM job WHERE survey_uid = ?1")
    .bind(surveyUid)
    .first<{ id: string; state: string }>();

  if (existing) {
    if (existing.state === "failed") {
      await env.DB.prepare(
        "UPDATE job SET state = 'pending', claimed_by = NULL, claim_expires = NULL, updated_at = ?2 WHERE id = ?1",
      )
        .bind(existing.id, now)
        .run();
      return { id: existing.id, state: "pending", created: false };
    }
    return { id: existing.id, state: existing.state, created: false };
  }

  await env.DB.prepare(
    "INSERT INTO job (id, survey_uid, state, requested_by, attempts, created_at, updated_at) " +
      "VALUES (?1, ?2, 'pending', ?3, 0, ?4, ?4)",
  )
    .bind(id, surveyUid, deviceId, now)
    .run();

  return { id, state: "pending", created: true };
}
```

- [ ] **Step 4: Write `src/routes/land.ts`**

```ts
import { authenticate, type Device } from "../auth";
import { error, json } from "../http";
import { enqueue } from "../jobs";

export async function requireDevice(
  request: Request,
  env: Env,
): Promise<Device | Response> {
  const device = await authenticate(request, env);
  return device ?? error("unauthorized", 401);
}

export async function postJob(request: Request, env: Env, device: Device): Promise<Response> {
  const body = (await request.json().catch(() => ({}))) as { survey_uid?: unknown };
  const surveyUid = body.survey_uid;
  if (typeof surveyUid !== "string" || surveyUid.length === 0) {
    return error("survey_uid is required", 400);
  }
  return json(await enqueue(env, device.id, surveyUid));
}
```

- [ ] **Step 5: Wire it in `src/index.ts`**

Insert before the final `return error("not found", 404)`:

```ts
    if (path.startsWith("/land/")) {
      const device = await requireDevice(request, env);
      if (device instanceof Response) return device;

      if (method === "POST" && path === "/land/jobs") return postJob(request, env, device);
    }
```

Add the import:

```ts
import { postJob, requireDevice } from "./routes/land";
```

Note: `/land/devices` and `/land/whoami` are matched *before* this block, so they keep their own auth handling.

- [ ] **Step 6: Run to verify it passes**

```fish
npx vitest run test/enqueue.test.ts
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```fish
git add apps/land-worker/src apps/land-worker/test
git commit -m "feat(worker): idempotent job enqueue"
```

---

### Task 5: The atomic claim

This is the task the whole design rests on. Everything else has an obvious implementation; this one has a race that a non-atomic version passes tests for and then loses in production.

**Files:**
- Modify: `apps/land-worker/src/jobs.ts`
- Modify: `apps/land-worker/src/routes/land.ts`
- Modify: `apps/land-worker/src/index.ts`
- Test: `apps/land-worker/test/claim.test.ts`

**Interfaces:**
- Consumes: `enqueue` (Task 4).
- Produces:
  - `claim(env: Env, deviceId: string, ttlSeconds: number): Promise<{ id: string; survey_uid: string } | null>`
  - `POST /land/jobs/claim` → `200 { id, survey_uid }` or `204` when nothing is claimable.

- [ ] **Step 1: Write the failing test**

`test/claim.test.ts`:

```ts
import { SELF, env } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { authed, enroll } from "./helpers";

let phone: { id: string; token: string };
let laptop: { id: string; token: string };

beforeAll(async () => {
  phone = await enroll("client");
  laptop = await enroll("worker");
});

async function enqueue(uid: string) {
  await SELF.fetch("https://land.test/land/jobs", {
    method: "POST",
    headers: authed(phone.token),
    body: JSON.stringify({ survey_uid: uid }),
  });
}

function claim(token: string) {
  return SELF.fetch("https://land.test/land/jobs/claim", {
    method: "POST",
    headers: authed(token),
  });
}

describe("POST /land/jobs/claim", () => {
  it("returns 204 when nothing is pending", async () => {
    const res = await claim(laptop.token);
    expect(res.status).toBe(204);
  });

  it("refuses a client device", async () => {
    await enqueue("gj:15:03:029/300");
    const res = await claim(phone.token);
    expect(res.status).toBe(403);
  });

  it("hands out the oldest pending job", async () => {
    await enqueue("gj:15:03:029/301");
    await env.DB.prepare("UPDATE job SET created_at = 1 WHERE survey_uid = 'gj:15:03:029/301'").run();
    await enqueue("gj:15:03:029/302");
    await env.DB.prepare("UPDATE job SET created_at = 2 WHERE survey_uid = 'gj:15:03:029/302'").run();

    const res = await claim(laptop.token);
    const body = (await res.json()) as { survey_uid: string };
    expect(body.survey_uid).toBe("gj:15:03:029/301");
  });

  // The reason this endpoint exists at all. With an advisory claim, two
  // concurrent workers can both believe they hold the same job.
  it("gives one job to exactly one of N concurrent claimers", async () => {
    await env.DB.prepare("DELETE FROM job").run();
    await enqueue("gj:15:03:029/400");

    const results = await Promise.all(
      Array.from({ length: 8 }, () => claim(laptop.token)),
    );
    const wins = results.filter((r) => r.status === 200);
    expect(wins.length).toBe(1);
    expect(results.filter((r) => r.status === 204).length).toBe(7);
  });

  it("increments attempts and sets an expiry", async () => {
    await env.DB.prepare("DELETE FROM job").run();
    await enqueue("gj:15:03:029/401");
    await claim(laptop.token);

    const row = await env.DB.prepare(
      "SELECT attempts, claimed_by, claim_expires FROM job WHERE survey_uid = 'gj:15:03:029/401'",
    ).first<{ attempts: number; claimed_by: string; claim_expires: number }>();
    expect(row?.attempts).toBe(1);
    expect(row?.claimed_by).toBe(laptop.id);
    expect(row?.claim_expires).toBeGreaterThan(Math.floor(Date.now() / 1000));
  });

  // A laptop that dies mid-job must release its work by timeout, not by
  // anyone noticing.
  it("reclaims a job whose claim has expired", async () => {
    await env.DB.prepare("DELETE FROM job").run();
    await enqueue("gj:15:03:029/402");
    await claim(laptop.token);
    expect((await claim(laptop.token)).status).toBe(204);

    await env.DB.prepare("UPDATE job SET claim_expires = 1 WHERE survey_uid = 'gj:15:03:029/402'").run();

    const res = await claim(laptop.token);
    expect(res.status).toBe(200);
    const row = await env.DB.prepare(
      "SELECT attempts FROM job WHERE survey_uid = 'gj:15:03:029/402'",
    ).first<{ attempts: number }>();
    expect(row?.attempts).toBe(2);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/claim.test.ts
```

Expected: FAIL — the route 404s.

- [ ] **Step 3: Add `claim` to `src/jobs.ts`**

```ts
export interface ClaimedJob {
  id: string;
  survey_uid: string;
}

/**
 * Atomically take the oldest claimable job, or return null.
 *
 * This is ONE statement on purpose. A read-then-write version passes every
 * sequential test and then hands the same job to two workers under concurrency,
 * which is exactly the failure the D1-backed queue exists to prevent. The
 * subselect picks the candidate and the outer UPDATE re-checks the predicate,
 * so a row claimed between the two is simply not matched.
 *
 * A claim whose expiry has passed is claimable again: a worker that dies
 * mid-job releases it by timeout rather than by anyone noticing.
 */
export async function claim(
  env: Env,
  deviceId: string,
  ttlSeconds: number,
): Promise<ClaimedJob | null> {
  const now = Math.floor(Date.now() / 1000);

  const row = await env.DB.prepare(
    `UPDATE job
        SET claimed_by = ?1,
            claim_expires = ?2,
            attempts = attempts + 1,
            updated_at = ?3
      WHERE id = (
              SELECT id FROM job
               WHERE state = 'pending'
                 AND (claimed_by IS NULL OR claim_expires IS NULL OR claim_expires < ?3)
               ORDER BY created_at, id
               LIMIT 1
            )
        AND state = 'pending'
        AND (claimed_by IS NULL OR claim_expires IS NULL OR claim_expires < ?3)
    RETURNING id, survey_uid`,
  )
    .bind(deviceId, now + ttlSeconds, now)
    .first<ClaimedJob>();

  return row ?? null;
}
```

- [ ] **Step 4: Add the route handler to `src/routes/land.ts`**

```ts
export async function postClaim(request: Request, env: Env, device: Device): Promise<Response> {
  // Only a 'worker' device may take jobs. Dad's phone announces work; it never
  // claims work on behalf of anyone else.
  if (device.role !== "worker") return error("device role may not claim jobs", 403);

  const ttl = Number(env.CLAIM_TTL_SECONDS);
  const job = await claim(env, device.id, ttl);
  return job ? json(job) : new Response(null, { status: 204 });
}
```

Update the import at the top of the file:

```ts
import { claim, enqueue } from "../jobs";
```

- [ ] **Step 5: Wire it in `src/index.ts`**

Inside the `/land/` block, after the `postJob` line:

```ts
      if (method === "POST" && path === "/land/jobs/claim") return postClaim(request, env, device);
```

and extend the import to `import { postClaim, postJob, requireDevice } from "./routes/land";`

Order matters: `/land/jobs/claim` must be matched before any generic `/land/jobs` prefix handling added later.

- [ ] **Step 6: Run to verify it passes**

```fish
npx vitest run test/claim.test.ts
```

Expected: PASS, 6 tests — including the 8-way concurrent claim.

- [ ] **Step 7: Commit**

```fish
git add apps/land-worker/src apps/land-worker/test
git commit -m "feat(worker): atomic job claim with expiry-based reclaim"
```

---

### Task 6: Completing, failing, and inspecting jobs

**Files:**
- Modify: `apps/land-worker/src/jobs.ts`
- Modify: `apps/land-worker/src/routes/land.ts`
- Modify: `apps/land-worker/src/index.ts`
- Test: `apps/land-worker/test/complete.test.ts`

**Interfaces:**
- Consumes: `claim` (Task 5).
- Produces:
  - `complete(env, deviceId, jobId, lamport): Promise<boolean>`
  - `fail(env, deviceId, jobId, message): Promise<boolean>`
  - `listJobs(env, state?): Promise<JobRow[]>`
  - `POST /land/jobs/:id/done` body `{ lamport }`, `POST /land/jobs/:id/fail` body `{ error }`, `GET /land/jobs?state=`

- [ ] **Step 1: Write the failing test**

`test/complete.test.ts`:

```ts
import { SELF, env } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { authed, enroll } from "./helpers";

let phone: { id: string; token: string };
let laptop: { id: string; token: string };
let other: { id: string; token: string };

beforeAll(async () => {
  phone = await enroll("client");
  laptop = await enroll("worker");
  other = await enroll("worker");
});

async function freshClaim(uid: string) {
  await SELF.fetch("https://land.test/land/jobs", {
    method: "POST",
    headers: authed(phone.token),
    body: JSON.stringify({ survey_uid: uid }),
  });
  const res = await SELF.fetch("https://land.test/land/jobs/claim", {
    method: "POST",
    headers: authed(laptop.token),
  });
  return (await res.json()) as { id: string; survey_uid: string };
}

function done(token: string, id: string, body: unknown) {
  return SELF.fetch(`https://land.test/land/jobs/${id}/done`, {
    method: "POST",
    headers: authed(token),
    body: JSON.stringify(body),
  });
}

describe("job completion", () => {
  it("marks a claimed job done and records the lamport", async () => {
    const job = await freshClaim("gj:15:03:029/500");
    const res = await done(laptop.token, job.id, { lamport: 42 });
    expect(res.status).toBe(200);

    const row = await env.DB.prepare("SELECT state, done_lamport FROM job WHERE id = ?1")
      .bind(job.id)
      .first<{ state: string; done_lamport: number }>();
    expect(row?.state).toBe("done");
    expect(row?.done_lamport).toBe(42);
  });

  // Otherwise a worker whose claim expired could overwrite the result of the
  // worker that legitimately took over.
  it("refuses completion from a device that does not hold the claim", async () => {
    const job = await freshClaim("gj:15:03:029/501");
    const res = await done(other.token, job.id, { lamport: 1 });
    expect(res.status).toBe(409);

    const row = await env.DB.prepare("SELECT state FROM job WHERE id = ?1")
      .bind(job.id)
      .first<{ state: string }>();
    expect(row?.state).toBe("pending");
  });

  it("requires a numeric lamport", async () => {
    const job = await freshClaim("gj:15:03:029/502");
    const res = await done(laptop.token, job.id, { lamport: "forty-two" });
    expect(res.status).toBe(400);
  });

  it("404s an unknown job", async () => {
    const res = await done(laptop.token, "deadbeefdeadbeefdeadbeef", { lamport: 1 });
    expect(res.status).toBe(404);
  });

  it("records a failure with its message and frees the claim", async () => {
    const job = await freshClaim("gj:15:03:029/503");
    const res = await SELF.fetch(`https://land.test/land/jobs/${job.id}/fail`, {
      method: "POST",
      headers: authed(laptop.token),
      body: JSON.stringify({ error: "captcha rejected twice" }),
    });
    expect(res.status).toBe(200);

    const row = await env.DB.prepare(
      "SELECT state, last_error, claimed_by FROM job WHERE id = ?1",
    )
      .bind(job.id)
      .first<{ state: string; last_error: string; claimed_by: string | null }>();
    expect(row?.state).toBe("failed");
    expect(row?.last_error).toBe("captcha rejected twice");
    expect(row?.claimed_by).toBeNull();
  });
});

describe("GET /land/jobs", () => {
  it("lists jobs filtered by state", async () => {
    await freshClaim("gj:15:03:029/600");
    const res = await SELF.fetch("https://land.test/land/jobs?state=pending", {
      headers: authed(phone.token),
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { jobs: Array<{ state: string }> };
    expect(body.jobs.length).toBeGreaterThan(0);
    expect(body.jobs.every((j) => j.state === "pending")).toBe(true);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/complete.test.ts
```

Expected: FAIL — routes 404.

- [ ] **Step 3: Add the functions to `src/jobs.ts`**

```ts
/**
 * Mark a job done. Returns false if the job does not exist or the caller does
 * not hold its claim — a worker whose claim expired must not be able to
 * overwrite the result of whoever legitimately took over.
 */
export async function complete(
  env: Env,
  deviceId: string,
  jobId: string,
  lamport: number,
): Promise<"ok" | "not_found" | "not_holder"> {
  const row = await env.DB.prepare("SELECT claimed_by FROM job WHERE id = ?1")
    .bind(jobId)
    .first<{ claimed_by: string | null }>();
  if (!row) return "not_found";
  if (row.claimed_by !== deviceId) return "not_holder";

  await env.DB.prepare(
    "UPDATE job SET state = 'done', done_lamport = ?2, claim_expires = NULL, updated_at = ?3 WHERE id = ?1",
  )
    .bind(jobId, lamport, Math.floor(Date.now() / 1000))
    .run();
  return "ok";
}

/** Record a failure and release the claim so the job can be retried. */
export async function fail(
  env: Env,
  deviceId: string,
  jobId: string,
  message: string,
): Promise<"ok" | "not_found" | "not_holder"> {
  const row = await env.DB.prepare("SELECT claimed_by FROM job WHERE id = ?1")
    .bind(jobId)
    .first<{ claimed_by: string | null }>();
  if (!row) return "not_found";
  if (row.claimed_by !== deviceId) return "not_holder";

  await env.DB.prepare(
    "UPDATE job SET state = 'failed', last_error = ?2, claimed_by = NULL, claim_expires = NULL, updated_at = ?3 WHERE id = ?1",
  )
    .bind(jobId, message.slice(0, 500), Math.floor(Date.now() / 1000))
    .run();
  return "ok";
}

export interface JobRow {
  id: string;
  survey_uid: string;
  state: string;
  attempts: number;
  claimed_by: string | null;
  last_error: string | null;
  created_at: number;
  updated_at: number;
}

/** Inspection. This is what makes a stuck job queryable instead of invisible. */
export async function listJobs(env: Env, state?: string): Promise<JobRow[]> {
  const sql =
    "SELECT id, survey_uid, state, attempts, claimed_by, last_error, created_at, updated_at FROM job" +
    (state ? " WHERE state = ?1" : "") +
    " ORDER BY created_at DESC LIMIT 200";
  const stmt = state ? env.DB.prepare(sql).bind(state) : env.DB.prepare(sql);
  const { results } = await stmt.all<JobRow>();
  return results;
}
```

- [ ] **Step 4: Add the handlers to `src/routes/land.ts`**

```ts
export async function postDone(
  request: Request,
  env: Env,
  device: Device,
  jobId: string,
): Promise<Response> {
  const body = (await request.json().catch(() => ({}))) as { lamport?: unknown };
  if (typeof body.lamport !== "number" || !Number.isFinite(body.lamport)) {
    return error("lamport must be a number", 400);
  }
  const outcome = await complete(env, device.id, jobId, body.lamport);
  if (outcome === "not_found") return error("no such job", 404);
  if (outcome === "not_holder") return error("caller does not hold this claim", 409);
  return json({ ok: true });
}

export async function postFail(
  request: Request,
  env: Env,
  device: Device,
  jobId: string,
): Promise<Response> {
  const body = (await request.json().catch(() => ({}))) as { error?: unknown };
  const message = typeof body.error === "string" ? body.error : "unspecified";
  const outcome = await fail(env, device.id, jobId, message);
  if (outcome === "not_found") return error("no such job", 404);
  if (outcome === "not_holder") return error("caller does not hold this claim", 409);
  return json({ ok: true });
}

export async function getJobs(url: URL, env: Env): Promise<Response> {
  const state = url.searchParams.get("state") ?? undefined;
  if (state && !["pending", "done", "failed"].includes(state)) {
    return error("unknown state", 400);
  }
  return json({ jobs: await listJobs(env, state) });
}
```

Extend the import: `import { claim, complete, enqueue, fail, listJobs } from "../jobs";`

- [ ] **Step 5: Wire the routes in `src/index.ts`**

Inside the `/land/` block, after the claim line:

```ts
      if (method === "GET" && path === "/land/jobs") return getJobs(url, env);

      const doneMatch = path.match(/^\/land\/jobs\/([0-9a-f]{24})\/done$/);
      if (method === "POST" && doneMatch) return postDone(request, env, device, doneMatch[1]);

      const failMatch = path.match(/^\/land\/jobs\/([0-9a-f]{24})\/fail$/);
      if (method === "POST" && failMatch) return postFail(request, env, device, failMatch[1]);
```

Extend the import to include `getJobs, postDone, postFail`.

- [ ] **Step 6: Run to verify it passes**

```fish
npx vitest run test/complete.test.ts
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```fish
git add apps/land-worker/src apps/land-worker/test
git commit -m "feat(worker): job completion, failure and inspection"
```

---

### Task 7: The object manifest

The manifest is the reason clients never call R2 `LIST`. Its cursor is `object.seq`, not a timestamp — two objects registered in the same second must still be totally ordered, or a client silently skips one.

**Files:**
- Create: `apps/land-worker/src/manifest.ts`
- Modify: `apps/land-worker/src/routes/land.ts`
- Modify: `apps/land-worker/src/index.ts`
- Test: `apps/land-worker/test/manifest.test.ts`

**Interfaces:**
- Consumes: `object` table (Task 2).
- Produces:
  - `registerObject(env, deviceId, entry): Promise<{ seq: number }>` where `entry = { key: string; kind: "blob" | "batch"; size: number; lamport?: number }`
  - `manifestSince(env, since: number, limit: number): Promise<ObjectRow[]>`
  - `POST /land/objects`, `GET /land/manifest?since=&limit=`

- [ ] **Step 1: Write the failing test**

`test/manifest.test.ts`:

```ts
import { SELF } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { authed, enroll } from "./helpers";

let laptop: { id: string; token: string };

beforeAll(async () => {
  laptop = await enroll("worker");
});

function register(body: unknown, token = laptop.token) {
  return SELF.fetch("https://land.test/land/objects", {
    method: "POST",
    headers: authed(token),
    body: JSON.stringify(body),
  });
}

function manifest(since: number, token = laptop.token) {
  return SELF.fetch(`https://land.test/land/manifest?since=${since}`, {
    headers: authed(token),
  });
}

describe("object manifest", () => {
  it("registers a blob and returns its seq", async () => {
    const res = await register({ key: `blobs/${"a".repeat(64)}`, kind: "blob", size: 1234 });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { seq: number };
    expect(body.seq).toBeGreaterThan(0);
  });

  it("rejects a key that does not match its kind", async () => {
    const res = await register({ key: "nonsense/x", kind: "blob", size: 1 });
    expect(res.status).toBe(400);
  });

  it("requires a lamport for a batch", async () => {
    const res = await register({ key: "db/laptop/7.ndjson.gz", kind: "batch", size: 10 });
    expect(res.status).toBe(400);
  });

  // Re-uploading a PDF already in the store must be free, not an error.
  it("is idempotent on key", async () => {
    const key = `blobs/${"b".repeat(64)}`;
    const first = (await (await register({ key, kind: "blob", size: 10 })).json()) as { seq: number };
    const second = (await (await register({ key, kind: "blob", size: 10 })).json()) as { seq: number };
    expect(second.seq).toBe(first.seq);
  });

  it("returns only objects after the cursor, in seq order", async () => {
    const before = (await (await manifest(0)).json()) as { objects: Array<{ seq: number }>; cursor: number };
    const cursor = before.cursor;

    await register({ key: `blobs/${"c".repeat(64)}`, kind: "blob", size: 1 });
    await register({ key: `blobs/${"d".repeat(64)}`, kind: "blob", size: 1 });

    const after = (await (await manifest(cursor)).json()) as {
      objects: Array<{ key: string; seq: number }>;
      cursor: number;
    };
    expect(after.objects.length).toBe(2);
    expect(after.objects[0].seq).toBeLessThan(after.objects[1].seq);
    expect(after.cursor).toBe(after.objects[1].seq);
  });

  it("returns an unchanged cursor when there is nothing new", async () => {
    const first = (await (await manifest(0)).json()) as { cursor: number };
    const second = (await (await manifest(first.cursor)).json()) as {
      objects: unknown[];
      cursor: number;
    };
    expect(second.objects).toEqual([]);
    expect(second.cursor).toBe(first.cursor);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/manifest.test.ts
```

Expected: FAIL — routes 404.

- [ ] **Step 3: Write `src/manifest.ts`**

```ts
/**
 * The object manifest.
 *
 * This table is the whole reason clients never call R2 LIST (spec §2.1): LIST is
 * a Class A op and the only cost in the design that would scale with sync
 * frequency rather than with new data. D1 already knows what exists, so R2 is
 * addressed purely by key.
 *
 * The cursor is `seq`, never `created_at`. Two objects registered in the same
 * second must still be totally ordered, or a client resuming from a timestamp
 * silently skips one.
 */

export type ObjectKind = "blob" | "batch";

export interface ObjectEntry {
  key: string;
  kind: ObjectKind;
  size: number;
  lamport?: number;
}

export interface ObjectRow {
  seq: number;
  key: string;
  kind: string;
  size: number;
  device_id: string;
  lamport: number | null;
  created_at: number;
}

const BLOB_KEY = /^blobs\/[0-9a-f]{64}$/;
const BATCH_KEY = /^db\/[A-Za-z0-9_-]+\/\d+\.ndjson\.gz$/;

/** True if the key is one of the two namespaces this bucket permits. */
export function isSignableKey(key: string): boolean {
  return BLOB_KEY.test(key) || BATCH_KEY.test(key);
}

/** Validates that the key matches the kind. Returns an error message, or null. */
export function validateEntry(entry: ObjectEntry): string | null {
  if (!Number.isFinite(entry.size) || entry.size < 0) return "size must be a non-negative number";
  if (entry.kind === "blob") {
    if (!BLOB_KEY.test(entry.key)) return "blob key must be blobs/<sha256>";
    return null;
  }
  if (entry.kind === "batch") {
    if (!BATCH_KEY.test(entry.key)) return "batch key must be db/<device>/<lamport>.ndjson.gz";
    if (typeof entry.lamport !== "number") return "batch requires a lamport";
    return null;
  }
  return "kind must be blob or batch";
}

/**
 * Idempotent on key: blobs are content-addressed, so re-registering the same
 * sha256 is a no-op that must return the original seq rather than fail.
 */
export async function registerObject(
  env: Env,
  deviceId: string,
  entry: ObjectEntry,
): Promise<{ seq: number }> {
  const existing = await env.DB.prepare("SELECT seq FROM object WHERE key = ?1")
    .bind(entry.key)
    .first<{ seq: number }>();
  if (existing) return { seq: existing.seq };

  const row = await env.DB.prepare(
    "INSERT INTO object (key, kind, size, device_id, lamport, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6) RETURNING seq",
  )
    .bind(entry.key, entry.kind, entry.size, deviceId, entry.lamport ?? null, Math.floor(Date.now() / 1000))
    .first<{ seq: number }>();

  return { seq: row!.seq };
}

export async function manifestSince(env: Env, since: number, limit: number): Promise<ObjectRow[]> {
  const { results } = await env.DB.prepare(
    "SELECT seq, key, kind, size, device_id, lamport, created_at FROM object WHERE seq > ?1 ORDER BY seq LIMIT ?2",
  )
    .bind(since, limit)
    .all<ObjectRow>();
  return results;
}
```

- [ ] **Step 4: Add the handlers to `src/routes/land.ts`**

```ts
export async function postObject(request: Request, env: Env, device: Device): Promise<Response> {
  const body = (await request.json().catch(() => ({}))) as Partial<ObjectEntry>;
  const entry = {
    key: String(body.key ?? ""),
    kind: body.kind as ObjectKind,
    size: Number(body.size),
    lamport: body.lamport,
  };
  const problem = validateEntry(entry);
  if (problem) return error(problem, 400);
  return json(await registerObject(env, device.id, entry));
}

export async function getManifest(url: URL, env: Env): Promise<Response> {
  const since = Number(url.searchParams.get("since") ?? 0);
  if (!Number.isFinite(since) || since < 0) return error("since must be a non-negative number", 400);
  const limit = Math.min(Number(url.searchParams.get("limit") ?? 1000), 1000);

  const objects = await manifestSince(env, since, limit);
  // When nothing is new the cursor must not move, so a client can poll safely.
  const cursor = objects.length > 0 ? objects[objects.length - 1].seq : since;
  return json({ objects, cursor });
}
```

Add the import:

```ts
import {
  isSignableKey,
  manifestSince,
  registerObject,
  validateEntry,
  type ObjectEntry,
  type ObjectKind,
} from "../manifest";
```

- [ ] **Step 5: Wire the routes in `src/index.ts`**

Inside the `/land/` block:

```ts
      if (method === "POST" && path === "/land/objects") return postObject(request, env, device);
      if (method === "GET" && path === "/land/manifest") return getManifest(url, env);
```

Extend the import to include `getManifest, postObject`.

- [ ] **Step 6: Run to verify it passes**

```fish
npx vitest run test/manifest.test.ts
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```fish
git add apps/land-worker/src apps/land-worker/test
git commit -m "feat(worker): object manifest with a seq cursor, replacing R2 LIST"
```

---

### Task 8: Presigned R2 URLs

**Files:**
- Create: `apps/land-worker/src/presign.ts`
- Modify: `apps/land-worker/src/routes/land.ts`
- Modify: `apps/land-worker/src/index.ts`
- Test: `apps/land-worker/test/presign.test.ts`

**Interfaces:**
- Consumes: `validateEntry` (Task 7) for key shape.
- Produces:
  - `presign(env, key, method: "GET" | "PUT"): Promise<string>`
  - `POST /land/presign` body `{ key, method }` → `{ url, expires_in }`

- [ ] **Step 1: Write the failing test**

`test/presign.test.ts`:

```ts
import { SELF } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { authed, enroll } from "./helpers";

let laptop: { id: string; token: string };
let phone: { id: string; token: string };

beforeAll(async () => {
  laptop = await enroll("worker");
  phone = await enroll("client");
});

function presign(body: unknown, token = laptop.token) {
  return SELF.fetch("https://land.test/land/presign", {
    method: "POST",
    headers: authed(token),
    body: JSON.stringify(body),
  });
}

const KEY = `blobs/${"e".repeat(64)}`;

describe("POST /land/presign", () => {
  it("signs a GET url with an expiry", async () => {
    const res = await presign({ key: KEY, method: "GET" });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { url: string; expires_in: number };
    const url = new URL(body.url);
    expect(url.hostname).toBe("test-account.r2.cloudflarestorage.com");
    expect(url.pathname).toBe(`/land-records/${KEY}`);
    expect(url.searchParams.get("X-Amz-Expires")).toBe("900");
    expect(url.searchParams.get("X-Amz-Signature")).toMatch(/^[0-9a-f]+$/);
    expect(body.expires_in).toBe(900);
  });

  it("signs a PUT url for a worker", async () => {
    const res = await presign({ key: KEY, method: "PUT" });
    expect(res.status).toBe(200);
  });

  // Dad's phone uploads its own marks, so it needs PUT too — but only a device
  // that exists. The point of the check is that role is enforced somewhere
  // deliberate rather than by accident.
  it("allows a client to request a GET url", async () => {
    const res = await presign({ key: KEY, method: "GET" }, phone.token);
    expect(res.status).toBe(200);
  });

  it("rejects an unknown method", async () => {
    const res = await presign({ key: KEY, method: "DELETE" });
    expect(res.status).toBe(400);
  });

  // A key outside the two known shapes must never be signable: a signed URL for
  // an arbitrary key is a write primitive into the whole bucket.
  it("rejects a key outside the known namespaces", async () => {
    const res = await presign({ key: "../secrets", method: "GET" });
    expect(res.status).toBe(400);
  });

  it("rejects an unauthenticated caller", async () => {
    const res = await SELF.fetch("https://land.test/land/presign", {
      method: "POST",
      body: JSON.stringify({ key: KEY, method: "GET" }),
    });
    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```fish
npx vitest run test/presign.test.ts
```

Expected: FAIL — route 404s.

- [ ] **Step 3: Write `src/presign.ts`**

```ts
import { AwsClient } from "aws4fetch";

/**
 * Presigned R2 URLs.
 *
 * Bulk bytes never flow through the Worker (spec §2.3) — proxying hundreds of
 * megabytes would burn request time and CPU for no benefit — so clients get a
 * short-lived signed URL and talk to R2 directly.
 *
 * aws4fetch rather than @aws-sdk/client-s3: it is dependency-free and built on
 * fetch + SubtleCrypto, which is exactly what a Worker has. The AWS SDK pulls a
 * large dependency tree in for one signature.
 *
 * Callers MUST validate the key with isSignableKey() before calling this. A
 * signed URL for an arbitrary key is a write primitive into the entire bucket.
 */

export async function presign(env: Env, key: string, method: "GET" | "PUT"): Promise<string> {
  const client = new AwsClient({
    accessKeyId: env.R2_ACCESS_KEY_ID,
    secretAccessKey: env.R2_SECRET_ACCESS_KEY,
    service: "s3",
    region: "auto",
  });

  const ttl = env.PRESIGN_TTL_SECONDS;
  const endpoint = new URL(
    `https://${env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com/land-records/${key}`,
  );
  endpoint.searchParams.set("X-Amz-Expires", ttl);

  const signed = await client.sign(new Request(endpoint, { method }), {
    aws: { signQuery: true },
  });
  return signed.url;
}
```

- [ ] **Step 4: Add the handler to `src/routes/land.ts`**

```ts
export async function postPresign(request: Request, env: Env, _device: Device): Promise<Response> {
  const body = (await request.json().catch(() => ({}))) as { key?: unknown; method?: unknown };
  const key = String(body.key ?? "");
  const method = body.method;

  if (method !== "GET" && method !== "PUT") return error("method must be GET or PUT", 400);
  if (!isSignableKey(key)) return error("key is outside the signable namespaces", 400);

  return json({
    url: await presign(env, key, method),
    expires_in: Number(env.PRESIGN_TTL_SECONDS),
  });
}
```

Add the import: `import { presign } from "../presign";`

- [ ] **Step 5: Wire it in `src/index.ts`**

Inside the `/land/` block:

```ts
      if (method === "POST" && path === "/land/presign") return postPresign(request, env, device);
```

Extend the import to include `postPresign`.

- [ ] **Step 6: Run to verify it passes**

```fish
npx vitest run test/presign.test.ts
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Run the whole suite, typecheck, and commit**

```fish
npx vitest run
npm run typecheck
git add apps/land-worker
git commit -m "feat(worker): presigned R2 URLs via aws4fetch"
```

---

### Task 9: Deploy and verify on the zone

The canary exists to prove route precedence *before* any device points at this Worker. Spine established this practice on the same zone; dad's records now depend on it.

**Files:**
- Modify: `apps/land-worker/wrangler.jsonc` (remove the canary route at the end)
- Create: `docs/RUNBOOK-land-worker.md`

- [ ] **Step 1: Set the secrets**

```fish
cd ~/Desktop/projects/irmsc/apps/land-worker
npx wrangler secret put AUTH_PEPPER            # paste: openssl rand -hex 32
npx wrangler secret put ADMIN_TOKEN            # paste: openssl rand -hex 32
npx wrangler secret put R2_ACCOUNT_ID
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
```

Create the R2 API token first in the dashboard (R2 → Manage API Tokens), scoped to **Object Read & Write on the `land-records` bucket only**. A bucket-scoped token is the blast radius if the Worker is ever compromised.

- [ ] **Step 2: Apply migrations to the remote database**

```fish
npm run migrate:remote
```

Expected: `0001_init.sql` applied.

- [ ] **Step 3: Deploy**

```fish
npm run deploy
```

- [ ] **Step 4: Verify route precedence with the canary**

```fish
curl -s https://kirtanjain.com/land-canary/ping
```

Expected: `{"ok":true,"worker":"land-worker"}`

If this returns the Pages site's 404 instead, the route is not taking precedence — **stop and fix the route before enrolling any device.**

- [ ] **Step 5: Verify spine is unaffected**

```fish
curl -s -o /dev/null -w '%{http_code}\n' https://kirtanjain.com/api/healthcheck
```

Expected: `200`. This confirms the new Worker's routes did not capture spine's paths.

- [ ] **Step 6: Enroll the two real devices**

```fish
set ADMIN (read -P "admin token: ")
curl -s -X POST https://kirtanjain.com/land/devices \
  -H "x-admin-token: $ADMIN" -H 'content-type: application/json' \
  -d '{"id":"laptop","label":"Arch laptop","role":"worker"}'
curl -s -X POST https://kirtanjain.com/land/devices \
  -H "x-admin-token: $ADMIN" -H 'content-type: application/json' \
  -d '{"id":"dad-phone","label":"Dad phone","role":"client"}'
```

Save both tokens now — they are not recoverable. The laptop token goes into `packages/sync/.env` (Plan 2); the phone token is baked into the APK (Plan 3).

- [ ] **Step 7: Turn registration off**

Set `"ALLOW_REGISTRATION": "false"` in `wrangler.jsonc` (it already is) and confirm `ADMIN_TOKEN` is only in `wrangler secret`, never in a file. Re-deploy if changed.

- [ ] **Step 8: Write the runbook**

`docs/RUNBOOK-land-worker.md` must cover, with the exact commands from this task:
rotating a device token (register a new id, revoke the old with
`UPDATE device SET revoked_at = unixepoch() WHERE id = ?`), reading the queue
(`GET /land/jobs?state=failed`), tailing logs (`npx wrangler tail`), backing up D1
(`npm run backup`), and the canary check to run after every deploy.

- [ ] **Step 9: Remove the canary route and commit**

Once Steps 4–5 pass, delete the `/land-canary/*` route from `wrangler.jsonc` and the handler from `src/index.ts`, then re-deploy.

```fish
npx vitest run
npm run deploy
cd ~/Desktop/projects/irmsc
git add apps/land-worker docs/RUNBOOK-land-worker.md
git commit -m "chore(worker): deploy land-worker, enroll devices, drop canary"
```

---

## What this plan does NOT cover

Deliberately out of scope, each its own plan:

- **Plan 2 — laptop watcher** (`packages/sync/watchd.mjs`): claim polling, running the existing `run-anyror.mjs` / `run-vf712.mjs` path, uploading blobs and batches through `SyncRemote`, corpus backfill for the §7 cutover.
- **Plan 3 — app** : the `SyncRemote` Kotlin client, the resolution ladder, the schema collapse (spec §4.2), deleting `ui/fetch/` and `LegacyMigration.kt`, and demoting the fetch engine to queue-driven.

Both consume this Worker's API, which is why it is built and deployed first: they can be written against a running service rather than against a guess.

## Verification

After Task 9, all of these must hold:

```fish
cd apps/land-worker && npx vitest run && npm run typecheck   # 41 tests, clean types
curl -s https://kirtanjain.com/api/healthcheck               # spine still 200
cd ~/Desktop/projects/irmsc && npm run verify                # identity layer untouched
```

The last one matters most: this plan must not change identity behaviour at all. `probe-tokenizer` must still print **0 fused**.
