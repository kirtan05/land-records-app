// iRCMS captcha solver — DETERMINISTIC, no OCR. The site's captcha is an SVG
// (POST /return_captcha → {captcha_svg}) whose answer sits in plain <text> nodes.
// IMPORTANT: requesting a fresh captcha REGENERATES the server-side session code,
// so solve() returns the code for the captcha the server currently expects —
// solving and submitting must use the same round (don't solve twice).
export const CAPTCHA_URL = 'https://ircms.gujarat.gov.in/return_captcha';

/** In-page: fetch a fresh captcha SVG and return the answer (<text> nodes sorted by x). */
export async function solve(page) {
  return page.evaluate(async (url) => {
    const tok = document.querySelector('input[name="_token"]')?.value;
    if (!tok) return { err: 'no _token on page' };
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: '_token=' + encodeURIComponent(tok),
    });
    const j = await r.json().catch(() => null);
    const svg = j?.captcha_svg;
    if (!svg) return { err: 'no captcha_svg in response' };
    const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
    const code = Array.from(doc.querySelectorAll('text'))
      .map((t) => ({ x: +t.getAttribute('x'), ch: t.textContent }))
      .sort((a, b) => a.x - b.x)
      .map((t) => t.ch)
      .join('');
    return code ? { code } : { err: 'no <text> nodes parsed' };
  }, CAPTCHA_URL);
}

/**
 * Solve + fill + submit one survey search on the iRCMS ViewSurveyList page.
 * Retries a fresh captcha on "invalid" (site rotates on failure).
 * Returns {kind:'rows',rows} | {kind:'norecord'} | {kind:'error',err}.
 */
export async function searchWithAutoCaptcha(page, { maxTries = 3 } = {}) {
  for (let attempt = 1; attempt <= maxTries; attempt++) {
    const s = await solve(page);
    if (s.err) return { kind: 'error', err: 'solve: ' + s.err };
    const outcome = await page.evaluate(async (code) => {
      const tok = document.querySelector('input[name="_token"]')?.value;
      const q = (id) => document.querySelector(id)?.value;
      const body = new URLSearchParams({
        dist: q('#sel_district'), taluka: q('#sel_taluka'), village: q('#sel_village'),
        surveyno: q('#sel_survey_no'), captcha_code: code, _token: tok,
      });
      const r = await fetch('https://ircms.gujarat.gov.in/ViewSurveyListController', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
      });
      const data = await r.json().catch(() => null);
      if (!data || data.success === false) {
        const msg = (data && data.message) || 'bad response';
        return /captcha|invalid/i.test(msg) ? { kind: 'badcaptcha', msg } : { kind: 'norecord', msg };
      }
      // Rebuild the results table exactly like the site's own success handler.
      let rows = 0;
      const tb = document.querySelector('#surveylist_table tbody');
      if (tb) {
        tb.innerHTML = '';
        (Array.isArray(data) ? data : Object.values(data)).forEach((v) => {
          if (v && v.sr_no) {
            rows++;
            tb.insertAdjacentHTML('beforeend',
              `<tr><td>${v.sr_no}</td><td>${v.case_str}</td><td>${v.offname}</td><td>${v.dtv}</td><td>${v.sno_str}</td><td>${v.pet_res}</td><td>${v.view}</td></tr>`);
          }
        });
      }
      return { kind: rows ? 'rows' : 'norecord', rows };
    }, s.code);
    if (outcome.kind === 'badcaptcha') { await page.waitForTimeout(600); continue; }
    return outcome;
  }
  return { kind: 'error', err: 'captcha rejected after retries' };
}
