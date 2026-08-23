#!/usr/bin/env python3
"""Rebuild each survey's VF712_<token>_ALL.pdf: newest period first, with a clear year
label page before each period's scanned documents."""
import json, os, re, sys
from io import BytesIO
from fpdf import FPDF
from pypdf import PdfReader, PdfWriter
import sys, os; sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../tools'))
from repo_root import REPO

OUT = str(REPO)+'/output'
STATE = os.path.join(OUT, "_vf712_state.json")
FONTS = "/usr/share/fonts/noto"

def period_of(f):
    m = re.search(r"_(\d{4}-\d{4})_", f)
    return m.group(1) if m else "0000-0000"

def divider(survey, period, n, idx, total):
    p = FPDF(format="letter", unit="mm")
    p.add_font("noto", "", f"{FONTS}/NotoSans-Regular.ttf")
    p.add_font("noto", "B", f"{FONTS}/NotoSans-Bold.ttf")
    p.add_font("guj", "", f"{FONTS}/NotoSansGujarati-Regular.ttf")
    p.add_page()
    p.set_y(70)
    p.set_text_color(31, 78, 120); p.set_font("noto", "B", 15)
    p.cell(0, 10, f"Survey No {survey}  -  Bharoda (VF-7/12)", align="C"); p.ln(16)
    p.set_text_color(0); p.set_font("guj", "", 18)
    p.cell(0, 10, "થોક વર્ષ", align="C"); p.ln(9)
    p.set_text_color(110); p.set_font("noto", "", 12)
    p.cell(0, 8, "Scanned VF-7/12 period", align="C"); p.ln(13)
    p.set_text_color(196, 90, 0); p.set_font("noto", "B", 60)
    p.cell(0, 30, period.replace("-", "  -  "), align="C"); p.ln(34)
    p.set_text_color(120); p.set_font("noto", "", 12)
    p.cell(0, 8, f"{n} scanned document(s)   |   section {idx} of {total}", align="C")
    return bytes(p.output())

def build(token, survey, d):
    files = [f for f in os.listdir(d) if f.endswith(".pdf") and not f.endswith("_ALL.pdf")]
    groups = {}
    for f in files:
        groups.setdefault(period_of(f), []).append(f)
    periods = sorted(groups, key=lambda p: int(p[:4]), reverse=True)  # latest first
    writer = PdfWriter()
    for i, per in enumerate(periods, 1):
        fs = sorted(groups[per])  # r00..r25 -> numeric order
        for pg in PdfReader(BytesIO(divider(survey, per, len(fs), i, len(periods)))).pages:
            writer.add_page(pg)
        for f in fs:
            for pg in PdfReader(os.path.join(d, f)).pages:
                writer.add_page(pg)
    out = os.path.join(d, f"VF712_{token}_ALL.pdf")
    with open(out, "wb") as fh:
        writer.write(fh)
    return len(periods), sum(len(g) for g in groups.values()), len(writer.pages)

state = json.load(open(STATE))
only = sys.argv[1] if len(sys.argv) > 1 else None
for key, info in state.items():
    if only and info["token"] != only and key != only:
        continue
    d = info["dir"]
    if not os.path.isdir(d):
        print(f"{key}: no dir"); continue
    nper, ndoc, npg = build(info["token"], key, d)
    print(f"  {key:<11} {nper} periods (newest first), {ndoc} docs, {npg} pages -> VF712_{info['token']}_ALL.pdf")
print("VF712 COMBINED REBUILT")
