#!/usr/bin/env python3
"""
Parse Gujarat ASR-2011 (jantri) RURAL agricultural rate PDFs into structured CSV.

Source PDFs: https://garvi.gujarat.gov.in/PDF/RURAL/<name>.pdf  (see ../../data/jantri/sources.txt)

The PDFs carry a real text layer. Gujarati is in a legacy non-Unicode font, so all
Gujarati renders as mojibake -- but every field we need (survey numbers, rates,
district/taluka/village names) is plain ASCII. The handful of Gujarati strings that
matter are a closed set and are mapped to Unicode in GUJ_MAP below.

Table geometry is identical on every page of every district, so columns are assigned
by x-coordinate of each word's centre.
"""
import csv, os, re, subprocess, sys, unicodedata
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PDF_DIR = os.path.join(ROOT, "data", "jantri", "pdf")
OUT_DIR = os.path.join(ROOT, "data", "jantri", "out")

SQM_PER_ACRE = 4046.856422

# --- column geometry (x of word centre) -------------------------------------
# header cells sit at x = 230.3 binpiyat | 316.3 piyat | 375.7 kharaba | 444.0 khanij
COL_EDGES = [(225.0, "survey"), (302.0, "dry"), (371.0, "irrigated"),
             (440.0, "waste"), (487.0, "mineral"), (1e9, "road_class")]

def col_of(xc):
    for edge, name in COL_EDGES:
        if xc < edge:
            return name
    return "road_class"

# --- the closed set of Gujarati strings, mapped from legacy-font mojibake ----
# Right-hand column: location/road classification.
GUJ_MAP = {
    ";FDFgI": ("SAMANYA", "સામાન્ય", "General"),
    "HL<,F D]bIq VgI HL<,F DFU\" p%FZ": ("DISTRICT_ROAD", "જીલ્લા મુખ્ય/અન્ય જીલ્લા માર્ગ ઉપર", "On district main / other district road"),
    "ZFQ8LIqZFHI nMZLDFU\" p%FZ": ("HIGHWAY", "રાષ્ટ્રીય/રાજ્ય ધોરીમાર્ગ ઉપર", "On national / state highway"),
}
_GUJ_NOSP = {re.sub(r"\s+", "", k): v for k, v in GUJ_MAP.items()}
# Distinctive fragments, for cells whose glyph runs are split or reordered by the
# PDF's text layer. Checked in this order; each fragment is unique to its class.
GUJ_SIGNATURES = [
    ("nMZLDFU", "HIGHWAY"), ("ZFQ8LI", "HIGHWAY"),
    ("D]bI", "DISTRICT_ROAD"), ("bIq", "DISTRICT_ROAD"),
    (";FDFgI", "SAMANYA"),
]
_BY_CODE = {v[0]: v for v in GUJ_MAP.values()}

def classify(cell):
    """('CODE', gujarati, english) for a road-class cell; ('','','') if empty."""
    s = re.sub(r"\s+", "", cell)
    if not s:
        return ("", "", ""), "empty"
    if s in _GUJ_NOSP:
        return _GUJ_NOSP[s], "exact"
    for frag, code in GUJ_SIGNATURES:
        if frag in s:
            return _BY_CODE[code], "signature"
    return ("", "", ""), "unmapped"

LAND_TYPE_GUJ = {
    "dry":       ("બિનપિયત", "Non-irrigated"),
    "irrigated": ("પિયત", "Irrigated"),
    "waste":     ("બિનખેડાણપાત્ર ખરાબા", "Uncultivable waste"),
    "mineral":   ("ખનિજ તત્વોવાળી", "Mineral-bearing"),
}

# header label markers (mojibake) used to locate district/taluka/village on a page
M_DISTRICT = "HL<,F"      # જીલ્લા
M_TALUKA   = "SFP"        # તાલુકા (second half of "TF,] SFP")
M_VILLAGE  = "GFDP"       # ગામનુ નામ

WORD_RE = re.compile(
    rb'<word xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">(.*?)</word>',
    re.S)
PAGE_RE = re.compile(rb'<page width="([\d.]+)" height="([\d.]+)">(.*?)</page>', re.S)

def unesc(b):
    s = b.decode("utf-8", "replace")
    return (s.replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'"))

# Kachchh is typeset in a font whose encoding is the usual one shifted by +29
# (so 'ASR' arrives as '$65', 'Paiki' as '3DLNL'). Undo it and the page becomes
# byte-identical to every other district.
SHIFT = 29
H_BINPIYAT = "lAGl%FIT"          # column header, always at x = 230.3

def unshift(s):
    return "".join(chr(ord(c) + SHIFT) if ord(c) < 0x80 - SHIFT else c for c in s)

def pages_of(pdf):
    """Yield [(xmin,ymin,xmax,ymax,text), ...] per page. Regex, not XML: the legacy
    font emits raw control bytes that make some districts' XML non-well-formed."""
    xml = subprocess.run(["pdftotext", "-bbox-layout", pdf, "-"],
                         capture_output=True).stdout
    for pm in PAGE_RE.finditer(xml):
        ws = [(float(a), float(b), float(c), float(d), unesc(t))
              for a, b, c, d, t in WORD_RE.findall(pm.group(3))]
        if not any(H_BINPIYAT in w[4] for w in ws) and \
           any(H_BINPIYAT in unshift(w[4]) for w in ws):
            ws = [(a, b, c, d, unshift(t)) for a, b, c, d, t in ws]
        yield ws

def join_words(ws):
    """Reading order: group into lines (2pt tolerance), then left-to-right."""
    return " ".join(w[4] for w in sorted(ws, key=lambda w: (round(w[1] / 2.0), w[0]))).strip()

def split_y(words):
    """y of the column-header row. Its vertical position shifts between districts,
    so everything is measured relative to it rather than hardcoded."""
    ys = [w[1] for w in words if H_BINPIYAT in w[4] and 220 < w[0] < 245]
    return min(ys) if ys else 109.3

def header_fields(words, hy):
    """district / taluka / village from the page header band, above the table."""
    band = [w for w in words if w[1] < hy - 2]
    out = {}
    # (marker x-window, value x-window): the value window must be bounded on the right
    # too, or the "Rs.per acre" legend at x=500 leaks into district/taluka.
    for key, marker, xlo, xhi, vlo, vhi in (
            ("district", M_DISTRICT,   0, 280,  88, 280),
            ("taluka",   M_TALUKA,   280, 480, 315, 480),
            ("village",  M_VILLAGE,    0, 280,  88, 280)):
        # substring, not equality: some districts emit several labels as one token
        anchor = [w for w in band if marker in w[4] and xlo <= w[0] < xhi]
        if not anchor:
            continue
        ay = anchor[0][1]
        # value = ASCII words to the right of the marker on (roughly) the same line
        val = [w for w in band
               if vlo <= w[0] < vhi and w[0] > anchor[0][2] - 1 and abs(w[1] - ay) < 12
               and re.fullmatch(r"[A-Za-z0-9()/.\- ]+", w[4]) and w[4] != "P"]
        out[key] = re.sub(r"\s+", " ", join_words(val)).strip(" .")
    return out

RATE_RE = re.compile(r"^(\d{3,9})/-$")
SQM_RE  = re.compile(r"^\((\d{1,7})/-\)$")

def parse_pdf(pdf, district_hint):
    rate_rows, index_rows = [], []
    stats = defaultdict(int)
    rid = 0
    last_taluka = last_village = ""
    for pno, words in enumerate(pages_of(pdf), 1):
        hy = split_y(words)
        hdr = header_fields(words, hy)
        district = hdr.get("district") or district_hint
        # continuation pages repeat taluka but may omit the village name
        taluka = hdr.get("taluka") or last_taluka
        village = hdr.get("village") or last_village
        last_taluka, last_village = taluka, village
        # the wrapped second line of the "kharaba" header sits ~8pt below hy
        body = [w for w in words if w[1] > hy + 14]
        if not body:
            continue
        # anchors: per-acre rate cells define one table row each
        anchors = []
        for w in body:
            xc = (w[0] + w[2]) / 2
            c = col_of(xc)
            m = RATE_RE.match(w[4])
            if m and c in ("dry", "irrigated", "waste", "mineral"):
                anchors.append((w[1], c, int(m.group(1))))
        anchors.sort()
        if not anchors:
            # Continuation page: a survey list that overflowed from the previous page.
            # The rate is printed only on the first page, so these survey numbers
            # belong to the last row emitted.
            spill = [w for w in body if col_of((w[0] + w[2]) / 2) == "survey"]
            if spill and rate_rows:
                rate_rows[-1]["survey_numbers_raw"] += " " + re.sub(
                    r"\s+", " ", join_words(spill)).strip()
                stats["continuation_pages"] += 1
            else:
                stats["pages_no_anchor"] += 1
            continue
        # A single table row may price several land types, producing one rate cell
        # per column at (almost) the same y. Cluster them so every cell inherits the
        # row's survey list and road class.
        clusters = []           # list of [ (y, land_type, per_acre), ... ]
        for a in anchors:
            if clusters and a[0] - clusters[-1][0][0] <= 6:
                clusters[-1].append(a)
            else:
                clusters.append([a])
        ys = [c[0][0] for c in clusters]

        def bucket(y):
            """index of the last anchor starting at or above this word (6pt slack)."""
            lo, hi = 0, len(ys)
            while lo < hi:
                mid = (lo + hi) // 2
                if ys[mid] <= y + 6:
                    lo = mid + 1
                else:
                    hi = mid
            return lo - 1

        surveys = defaultdict(list)
        classes = defaultdict(list)
        spill_top = []          # survey words above the first rate on this page
        sqm_cells = []          # (y, column, value) -- paired to an anchor by column+y
        for w in body:
            xc = (w[0] + w[2]) / 2
            c = col_of(xc)
            m = SQM_RE.match(w[4])
            if m and c in ("dry", "irrigated", "waste", "mineral"):
                sqm_cells.append((w[1], c, int(m.group(1))))
                continue
            i = bucket(w[1])
            if i < 0:
                # above the first rate on this page -> tail of the previous page's row
                if c == "survey":
                    spill_top.append(w)
                continue
            if c == "survey":
                surveys[i].append(w)
            elif c == "road_class":
                classes[i].append(w)
        if spill_top and rate_rows:
            rate_rows[-1]["survey_numbers_raw"] += " " + re.sub(
                r"\s+", " ", join_words(spill_top)).strip()
            stats["spill_rows"] += 1

        for i, cells in enumerate(clusters):
            rid += 1
            raw = re.sub(r"\s+", " ", join_words(surveys.get(i, []))).strip()
            cls_raw = re.sub(r"\s+", " ", join_words(classes.get(i, []))).strip()
            (code, guj, eng), how = classify(cls_raw)
            stats["class_" + how] += 1
            # every rate cell in the row shares that row's survey list and road class
            for (y, land_type, per_acre) in cells:
                # pair the per-sq.m figure with the per-acre figure in the SAME column
                cand = [(yy - y, v) for yy, cc, v in sqm_cells
                        if cc == land_type and 0 < yy - y < 20]
                per_sqm_calc = round(per_acre / SQM_PER_ACRE)
                per_sqm = min(cand)[1] if cand else per_sqm_calc
                if abs(per_sqm - per_sqm_calc) > 1:
                    stats["sqm_mismatch"] += 1
                rate_rows.append(dict(
                    row_id=f"{district_hint}-{rid}", district=district, taluka=taluka,
                    village=village, page=pno, land_type=land_type,
                    land_type_gu=LAND_TYPE_GUJ[land_type][0],
                    land_type_en=LAND_TYPE_GUJ[land_type][1],
                    road_class=code, road_class_gu=guj, road_class_en=eng,
                    rate_per_acre_2011=per_acre, rate_per_sqm_2011=per_sqm,
                    rate_per_acre_2023=per_acre * 2, rate_per_sqm_2023=per_sqm * 2,
                    survey_numbers_raw=raw))
                stats["rows"] += 1
        stats["pages"] += 1

    # expanded last, so carried-over continuation text is included; once per logical
    # row (a row can appear as several rate cells, one per land type)
    done = set()
    for r in rate_rows:
        r["survey_numbers_raw"] = r["survey_numbers_raw"].strip()
        if r["row_id"] in done:
            continue
        done.add(r["row_id"])
        for s in expand_surveys(r["survey_numbers_raw"]):
            index_rows.append(dict(row_id=r["row_id"], district=r["district"],
                                   taluka=r["taluka"], village=r["village"],
                                   survey_no=s[0], survey_base=s[1],
                                   survey_suffix=s[2], is_paiki=s[3]))
        stats["surveys"] += 1
    return rate_rows, index_rows, stats

# --- survey number normalisation --------------------------------------------
PAIKI_RE = re.compile(r"^p?a?iki$", re.I)

def norm_token(tok):
    """'857/Paiki' -> ('857/PAIKI','857','PAIKI',1);  '824/A' -> ('824/A','824','A',0)"""
    tok = tok.strip(" .,;:")
    if not tok:
        return None
    m = re.fullmatch(r"(\d+)\s*/\s*([A-Za-z0-9]+)", tok)
    if m:
        base, suf = m.group(1), m.group(2).upper()
        if PAIKI_RE.match(suf) or suf in ("PAIKI", "AIKI", "IKI", "PAIK"):
            return (f"{base}/PAIKI", base, "PAIKI", 1)
        return (f"{base}/{suf}", base, suf, 0)
    m = re.fullmatch(r"(\d+)", tok)
    if m:
        return (m.group(1), m.group(1), "", 0)
    return None

def expand_surveys(raw):
    """Split a survey-list cell into normalised entries, expanding 'NNN TO MMM'."""
    if not raw:
        return []
    out, seen = [], set()
    txt = raw.replace("To", "TO").replace("to", "TO")
    # ranges first: '-/1130TO1131', '475 TO 476'
    def add(e):
        if e and e[0] not in seen:
            seen.add(e[0])
            out.append(e)
    for m in re.finditer(r"(\d+)\s*TO\s*(\d+)", txt):
        a, b = int(m.group(1)), int(m.group(2))
        if 0 < b - a <= 2000:
            for n in range(a, b + 1):
                add((str(n), str(n), "", 0))
        else:
            add((str(a), str(a), "", 0)); add((str(b), str(b), "", 0))
    txt = re.sub(r"\d+\s*TO\s*\d+", " ", txt)
    for tok in re.split(r"[,\s]+", txt):
        add(norm_token(tok))
    return out

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    wanted = sys.argv[1:]
    src = os.path.join(ROOT, "data", "jantri", "sources.txt")
    districts = [l.split()[0] for l in open(src) if l.strip()]
    if wanted:
        districts = [d for d in districts if d in wanted]
    grand = defaultdict(int)
    for d in districts:
        pdf = os.path.join(PDF_DIR, f"{d}.pdf")
        rr, ir, st = parse_pdf(pdf, d)
        for k, v in st.items():
            grand[k] += v
        for name, rows in (("rates", rr), ("survey_index", ir)):
            if not rows:
                continue
            path = os.path.join(OUT_DIR, f"{d}.{name}.csv")
            with open(path, "w", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
                w.writeheader()
                w.writerows(rows)
        vil = len({(r["taluka"], r["village"]) for r in rr})
        print(f"{d:<15} pages={st['pages']:5d} rows={st['rows']:6d} "
              f"surveys={len(ir):7d} villages={vil:5d} "
              f"unmapped={st["class_unmapped"]:5d} empty_cls={st["class_empty"]:6d} sqm_mismatch={st['sqm_mismatch']}",
              flush=True)
    print("TOTALS:", dict(grand))

if __name__ == "__main__":
    main()
