#!/usr/bin/env python3
"""Fast keyboard tagger for the AnyRoR captcha samples.

  python packages/captcha/tag-anyror.py [--port 8765]

Opens a page: big captcha image, type the 6 chars, Enter saves + advances.
Back = go back one. Writes packages/captcha/samples/anyror/labels.csv incrementally,
resuming from the last tagged file. Only tag what's readable; type '?' to skip.
"""
import csv
import http.server
import socketserver
import sys
import webbrowser
from pathlib import Path

DIR = Path(__file__).parent / "samples" / "anyror"
LABELS = DIR / "labels.csv"
PORT = 8765
for i, a in enumerate(sys.argv):
    if a == "--port" and i + 1 < len(sys.argv):
        PORT = int(sys.argv[i + 1])

PAGE = """<!doctype html><meta charset=utf-8><title>tag anyror captchas</title>
<style>
 body{font:16px system-ui;background:#1c1c1e;color:#eee;display:flex;flex-direction:column;align-items:center;gap:18px;padding-top:8vh}
 img{image-rendering:pixelated;width:570px;background:#fff;border-radius:8px;padding:14px}
 input{font:700 34px/1.2 monospace;text-align:center;letter-spacing:10px;padding:10px;width:340px;border-radius:8px;border:2px solid #666;background:#111;color:#fff;text-transform:lowercase}
 #st{opacity:.7;font-family:monospace} b{color:#e58a55}
</style>
<div id=st>…</div><img id=im><input id=in autocomplete=off autofocus placeholder="6 digits" maxlength="6" inputmode="numeric" pattern="[0-9]*">
<div style="opacity:.55">exactly 6 digits · Enter = save & next · Backspace on empty = go back</div>
<script>
let files = [], labels = {}, i = 0;
const im = document.getElementById('im'), inp = document.getElementById('in'), st = document.getElementById('st');
async function boot(){ const r = await fetch('state').then(r=>r.json()); files=r.files; labels=r.labels; i=r.next; show(); }
function show(){
  if(i>=files.length){ st.innerHTML='<b>ALL DONE</b> — labels.csv complete'; im.style.visibility='hidden'; inp.disabled=true; return; }
  im.style.visibility='visible';
  im.src = 'img/' + files[i] + '?t=' + Date.now();
  st.textContent = (i+1)+' / '+files.length+'   ·   '+files[i]+(labels[files[i]]? '   (was: '+labels[files[i]]+')':'');
  inp.value = labels[files[i]] || ''; inp.focus();
}
inp.addEventListener('input', ()=>{ inp.value = inp.value.replace(/[^0-9]/g,'').slice(0,6); }); // 6 digits only
inp.addEventListener('keydown', async e=>{
  if(e.key==='Enter'){
    const v=inp.value.trim();
    if(!/^\d{6}$/.test(v)){ inp.style.borderColor='#e58a55'; return; } // refuse anything but 6 digits
    inp.style.borderColor='#666';
    labels[files[i]]=v; await save();
    i++; show();
  } else if(e.key==='Backspace' && !inp.value){ e.preventDefault(); if(i>0){i--; show();} }
});
async function save(){ await fetch('save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(labels)}); }
boot();
</script>"""


def read_labels():
    out = {}
    if LABELS.exists():
        for row in csv.reader(LABELS.read_text().splitlines()):
            if len(row) >= 2 and row[0] != "file":
                out[row[0]] = row[1]
    return out


def numeric_files():
    # Sort by the number, not the string — 1011.png must come after 999.png, not inside
    # the 100s (string order interleaves 4-digit files and makes "next untagged" jump back).
    return sorted((p.name for p in DIR.glob("*.png")), key=lambda n: int(n.split(".")[0]))


def write_labels(labels):
    with LABELS.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["file", "label"])
        for name in numeric_files():
            if name in labels:
                w.writerow([name, labels[name]])


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):  # quiet
        pass

    def _send(self, body, ct="text/html"):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/?"):
            self._send(PAGE)
        elif self.path.startswith("/img/"):
            name = self.path[5:].split("?")[0]
            p = DIR / name
            self._send(p.read_bytes(), "image/png") if p.exists() else self.send_error(404)
        elif self.path.startswith("/state"):
            import json
            labels = read_labels()
            files = numeric_files()
            nxt = next((i for i, f in enumerate(files) if f not in labels), len(files))
            self._send(json.dumps({"files": files, "labels": labels, "next": nxt}), "application/json")
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path.startswith("/save"):
            import json
            n = int(self.headers.get("Content-Length", 0))
            posted = json.loads(self.rfile.read(n) or b"{}")
            # MERGE, never replace: a stale/duplicate tab posts its whole old dict —
            # replacing would silently delete labels the other tab just made.
            labels = read_labels()
            labels.update({k: v for k, v in posted.items() if isinstance(v, str) and v.isdigit() and len(v) == 6})
            write_labels(labels)
            self._send("ok", "text/plain")
        else:
            self.send_error(404)


if __name__ == "__main__":
    n = len(list(DIR.glob("*.png")))
    print(f"{n} PNGs in {DIR}; already tagged: {len(read_labels())}")
    print(f"open http://127.0.0.1:{PORT}")
    webbrowser.open(f"http://127.0.0.1:{PORT}")
    # Threading: Chrome holds parallel/keep-alive connections; a single-threaded
    # server wedges on the first one and the UI "sticks" after a few dozen saves.
    with http.server.ThreadingHTTPServer(("127.0.0.1", PORT), H) as s:
        s.daemon_threads = True
        s.serve_forever()
