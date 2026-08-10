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

export class Session {
  constructor(token, cookie, html) {
    this.token = token;
    this.cookie = cookie;
    this.html = html;
    this.last = 0;
  }

  static async open() {
    const res = await fetch(HOME, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`eJamin: homepage HTTP ${res.status}`);
    const html = await res.text();
    const cookie = (res.headers.getSetCookie?.() ?? [])
      .map((c) => c.split(';')[0]).join('; ');
    return new Session(extractToken(html), cookie, html);
  }

  /** One throttled villageMapGet. Serial by construction — callers await each hop. */
  async post(type, id) {
    const wait = 400 - (Date.now() - this.last);
    if (wait > 0) await sleep(wait);
    this.last = Date.now();

    const body = new FormData();
    body.append('_token', this.token);
    body.append('id', String(id));
    body.append('type', type);

    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'User-Agent': UA, 'X-Requested-With': 'XMLHttpRequest', Referer: HOME, Cookie: this.cookie },
      body,
    });
    if (!res.ok) throw new Error(`eJamin: ${type}/${id} HTTP ${res.status}`);
    const json = await res.json();
    return json.status === 1 ? json.data : null;
  }
}
