#!/usr/bin/env python3
"""Fetch taluka + village lists (English AND Gujarati, with official codes) from
iRCMS, for use as the jantri <-> AnyRoR village crosswalk.

    python3 tools/jantri/fetch_cascade.py 15 16 32

iRCMS ServiceData/fetch is a plain POST needing a session cookie + CSRF token
scraped from /ViewSurveyList. Options come back as
    <option value="05">Balasinor : બાલાસિનોર</option>
Deliberately slow (1 req/sec) -- these are government endpoints.
"""
import json, os, re, sys, time, urllib.parse, urllib.request, http.cookiejar

BASE = "https://ircms.gujarat.gov.in"
PAGE = f"{BASE}/ViewSurveyList"
FETCH = f"{BASE}/ServiceData/fetch"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "..", "data", "jantri", "cascade")
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
OPT = re.compile(r'<option value="([^"]+)"[^>]*>\s*(.*?)\s*</option>', re.S)

def session():
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    op.addheaders = [("User-Agent", UA)]
    html = op.open(PAGE, timeout=60).read().decode("utf-8", "replace")
    m = re.search(r'name="_token" value="([^"]+)"', html)
    if not m:
        raise SystemExit("could not find CSRF token")
    return op, m.group(1), html

_sess = {}

def post(op, token, **fields):
    """POST with retries; on repeated failure the session is rebuilt (the CSRF
    token is tied to the cookie and both expire)."""
    last = None
    for attempt in range(5):
        try:
            data = urllib.parse.urlencode(dict(_token=_sess.get("token", token),
                                               **fields)).encode()
            req = urllib.request.Request(FETCH, data=data, headers={
                "User-Agent": UA, "X-Requested-With": "XMLHttpRequest",
                "Referer": PAGE,
                "Content-Type": "application/x-www-form-urlencoded"})
            time.sleep(1.0)
            body = _sess["op"].open(req, timeout=90).read().decode("utf-8", "replace")
            if "CSRF" in body:
                raise RuntimeError("CSRF token mismatch")
            return body
        except Exception as e:                       # noqa: BLE001
            last = e
            print(f"    retry {attempt + 1}/5 ({type(e).__name__})", flush=True)
            time.sleep(3 * (attempt + 1))
            if attempt >= 1:                         # refresh session and token
                try:
                    o, t, _ = session()
                    _sess["op"], _sess["token"] = o, t
                except Exception:                    # noqa: BLE001
                    pass
    raise SystemExit(f"giving up after 5 attempts: {last}")

def options(html):
    out = []
    for val, label in OPT.findall(html):
        if val in ("-1", ""):
            continue
        label = re.sub(r"\s+", " ", label).strip()
        en, gu = (label.split(":", 1) + [""])[:2] if ":" in label else (label, "")
        out.append({"code": val, "en": en.strip(), "gu": gu.strip()})
    return out

def main():
    codes = sys.argv[1:] or ["15", "16", "32"]
    os.makedirs(OUT, exist_ok=True)
    op, token, html = session()
    _sess["op"], _sess["token"] = op, token
    dsel = re.search(r'id="sel_district".*?</select>', html, re.S)
    districts = {d["code"]: d for d in options(dsel.group(0))} if dsel else {}
    for dc in codes:
        d = districts.get(dc, {"code": dc, "en": f"DISTRICT{dc}", "gu": ""})
        done = os.path.join(OUT, f"{dc}_{d['en'].lower()}.json")
        if os.path.exists(done):
            print(f"{d['en']}: cached")
            continue
        talukas = options(post(op, token, select="sel_district", value=dc,
                               dependent="sel_taluka"))
        for t in talukas:
            t["villages"] = options(post(op, token, select="sel_taluka",
                                         value=t["code"], value2=dc,
                                         dependent="sel_village"))
            print(f"  {d['en']:<12} {t['en']:<16} {len(t['villages']):>4} villages",
                  flush=True)
        obj = {"district": d, "source": f"{FETCH} (iRCMS cascade)",
               "talukas": talukas,
               "villageCount": sum(len(t["villages"]) for t in talukas)}
        path = os.path.join(OUT, f"{dc}_{d['en'].lower()}.json")
        json.dump(obj, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"{d['en']}: {len(talukas)} talukas, {obj['villageCount']} villages -> {path}")

if __name__ == "__main__":
    main()
