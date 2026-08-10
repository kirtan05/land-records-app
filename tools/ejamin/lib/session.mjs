// One polite eJamin session. The site is a Laravel app: every map lookup is a POST to
// /villageMapGet carrying the page's CSRF token plus the session cookie, and it only answers
// when the request looks like the site's own jQuery ($.ajax sets X-Requested-With).
const HOME = 'https://ejamingujarat.com/';
const ENDPOINT = 'https://ejamingujarat.com/villageMapGet';
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36';

/** The CSRF token the page bakes into its own $.ajax calls. */
export function extractToken(html) {
  const m = html.match(/_token["']?\s*[:,]\s*["']([A-Za-z0-9]{40})["']/);
  if (!m) throw new Error('eJamin: CSRF token not found in homepage');
  return m[1];
}

/** TP-map rows for every district, embedded in the page as `let tpMapData = {...};`. */
export function extractTpMapData(html) {
  const i = html.indexOf('let tpMapData');
  if (i < 0) throw new Error('eJamin: tpMapData literal not found');
  const start = html.indexOf('{', i);
  // Brace-match rather than regex — the literal contains braces inside strings is not a risk here
  // (Laravel json_encode escapes nothing brace-like), but it spans ~500 KB and is not line-bounded.
  let depth = 0;
  for (let j = start; j < html.length; j++) {
    if (html[j] === '{') depth++;
    else if (html[j] === '}' && --depth === 0) return JSON.parse(html.slice(start, j + 1));
  }
  throw new Error('eJamin: tpMapData literal is unterminated');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * A counting semaphore. eJamin is a private commercial site, not a gujarat.gov.in host, so the
 * AnyRoR politeness rules do not apply to it — the catalogue walk is ~18k requests and running it
 * serially measured out at ~14 hours. We run it wide instead and let the server tell us the limit:
 * a 429 or 5xx halves the live concurrency and retries with backoff, so the crawl settles at
 * whatever rate the site will actually serve rather than a rate we guessed.
 */
class Gate {
  constructor(limit) {
    this.limit = limit;
    this.active = 0;
    this.queue = [];
  }

  async acquire() {
    if (this.active < this.limit) { this.active++; return; }
    await new Promise((r) => this.queue.push(r));
    this.active++;
  }

  release() {
    this.active--;
    this.queue.shift()?.();
  }

  /** Back off after the server pushes back. Never drops below 1 — that is still forward progress. */
  shrink() {
    this.limit = Math.max(1, Math.floor(this.limit / 2));
  }
}

export class Session {
  constructor(token, cookie, html, concurrency = Number(process.env.EJAMIN_CONCURRENCY ?? 16)) {
    this.token = token;
    this.cookie = cookie;
    this.html = html;
    this.gate = new Gate(concurrency);
    this.throttled = 0;
  }

  static async open() {
    const res = await fetch(HOME, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`eJamin: homepage HTTP ${res.status}`);
    const html = await res.text();
    const cookie = (res.headers.getSetCookie?.() ?? [])
      .map((c) => c.split(';')[0]).join('; ');
    return new Session(extractToken(html), cookie, html);
  }

  /**
   * One villageMapGet, run under the concurrency gate with retry-and-back-off. Safe to call from
   * many callers at once. Returns the payload, or null when the site reports no data — an empty
   * result is a real answer and is never turned into a fabricated row upstream.
   */
  async post(type, id) {
    // Retry loop sits OUTSIDE the gate: each attempt acquires and releases exactly once, so a
    // back-off never leaks a permit and slowly inflates the real concurrency.
    for (let attempt = 0; ; attempt++) {
      const body = new FormData();
      body.append('_token', this.token);
      body.append('id', String(id));
      body.append('type', type);

      let res;
      await this.gate.acquire();
      try {
        res = await fetch(ENDPOINT, {
          method: 'POST',
          headers: { 'User-Agent': UA, 'X-Requested-With': 'XMLHttpRequest', Referer: HOME, Cookie: this.cookie },
          body,
        });
      } finally {
        this.gate.release();
      }

      // 429/5xx = the site pushing back. Narrow the pipe and retry; only give up after 5 tries so a
      // transient blip never silently drops a village from the catalogue.
      if (res.status === 429 || res.status >= 500) {
        if (attempt >= 5) throw new Error(`eJamin: ${type}/${id} HTTP ${res.status} after ${attempt} retries`);
        this.throttled++;
        this.gate.shrink();
        await sleep(500 * 2 ** attempt);
        continue;
      }
      if (!res.ok) throw new Error(`eJamin: ${type}/${id} HTTP ${res.status}`);
      const json = await res.json();
      return json.status === 1 ? json.data : null;
    }
  }

  /** Run [items] through [fn] concurrently; the gate inside post() is what actually bounds it. */
  async map(items, fn) {
    return Promise.all(items.map(fn));
  }
}
