#!/usr/bin/env python3
"""Rebuild clean AnyRoR integrated-survey PDFs from the saved anyror_<token>.json data
(no re-fetch). Proper section titles, bordered tables, Gujarati primary + Latin fallback."""
import json, os, re, sys
from fpdf import FPDF
from fpdf.enums import XPos, YPos
from fpdf.fonts import FontFace

OUT = "/home/kirtan/Desktop/projects/irmsc/output"
STATE = os.path.join(OUT, "_anyror_state.json")
FONTS = "/usr/share/fonts/noto"
NAVY = (31, 78, 120)
HEAD_FILL = (232, 238, 246)

def clean(s):
    return re.sub(r"\s+", " ", str(s)).strip()

def infer_title(h):
    if "મોસમ" in h or "પાક નું નામ" in h: return "પાક ની વિગત  —  Crop & Cultivation"
    if "નોંધની વિગત" in h: return "ગામ નમૂના નં. ૬ — નોંધો  —  Mutation Entries"
    if "Office Name" in h and ("Document" in h or "Deed" in h): return "નોંધાયેલ દસ્તાવેજ  —  Registered Documents (SRO)"
    if "Case No" in h or "Case Status" in h: return "મહેસૂલ કેસ  —  Revenue Cases (RTS)"
    if "રહેણાંક" in h or "વાણિજ્ય" in h: return "ક્ષેત્રફળ / જમીન વપરાશ  —  Area & Land-use"
    if "ખાતા નંબર" in h or "ખાતેદાર" in h: return "ખાતેદારની વિગત  —  Ownership (Khata / Owners)"
    if "બોજા" in h or "હક્ક" in h: return "બોજા તથા બીજા હક્ક  —  Encumbrances & Other Rights"
    if "Entry Details" in h or "નોંધ નંબર" in h: return "નોંધ નંબરો  —  Entry Numbers"
    if "ગણોતિયા" in h: return "ગણોતિયા  —  Tenant"
    return None

def col_weights(header, body, ncols):
    w = []
    for i in range(ncols):
        lens = ([len(header[i])] if i < len(header) else []) + [len(r[i]) for r in body if i < len(r)]
        avg = sum(lens) / max(1, len(lens))
        w.append(max(4.0, min(avg, 42.0)))
    return tuple(w)

def section_bar(pdf, title):
    if pdf.get_y() > pdf.eph - 18:
        pdf.add_page()
    pdf.set_font("guj", "B", 10); pdf.set_fill_color(*NAVY); pdf.set_text_color(255)
    pdf.cell(0, 6, "  " + title, new_x=XPos.LMARGIN, new_y=YPos.NEXT, fill=True)
    pdf.set_text_color(0); pdf.ln(1.2)

def build(token, survey, data, path):
    pdf = FPDF(orientation="L", format="A4", unit="mm")
    pdf.set_auto_page_break(True, margin=8)
    pdf.set_margins(8, 8, 8)
    pdf.add_font("guj", "", f"{FONTS}/NotoSansGujarati-Regular.ttf")
    pdf.add_font("guj", "B", f"{FONTS}/NotoSansGujarati-Bold.ttf")
    pdf.add_font("noto", "", f"{FONTS}/NotoSans-Regular.ttf")
    pdf.add_font("noto", "B", f"{FONTS}/NotoSans-Bold.ttf")
    pdf.set_fallback_fonts(["noto"])
    pdf.add_page()

    pdf.set_font("noto", "B", 15); pdf.set_text_color(*NAVY)
    pdf.cell(0, 8, "AnyRoR - Integrated Survey Record", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
    pdf.set_draw_color(*NAVY); pdf.set_line_width(0.6); y = pdf.get_y(); pdf.line(8, y, pdf.w - 8, y); pdf.ln(1.5)
    pdf.set_font("guj", "", 10); pdf.set_text_color(20)
    pdf.cell(0, 6, f"ગામ ભરોડા  ·  તાલુકા ઉમરેઠ  ·  જિલ્લા આણંદ      |      સરવે / બ્લોક નં. {survey}", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
    summ = "   ·   ".join(x for x in [
        (f"કુલ ક્ષેત્રફળ {data.get('total_area','')}" if data.get('total_area') else ""),
        (f"આકાર {data.get('total_assessment','')}" if data.get('total_assessment') else ""),
        data.get("tenure", ""), data.get("land_use", "")] if x)
    pdf.set_font("guj", "", 9); pdf.set_text_color(60)
    if summ: pdf.cell(0, 5, summ, new_x=XPos.LMARGIN, new_y=YPos.NEXT)
    if data.get("as_of"):
        pdf.set_font("noto", "", 8); pdf.cell(0, 4, f"As of {data['as_of']}", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
    pdf.ln(2.5)

    for sec in data.get("sections", []):
        rows = [[clean(c) for c in r] for r in sec.get("rows", [])]
        rows = [r for r in rows if any(r)]
        if not rows: continue
        ncols = max(len(r) for r in rows)

        if ncols == 1:
            title = infer_title(rows[0][0]) or clean(rows[0][0])
            items = [r[0] for r in rows[1:] if r and r[0]]
            if not items and not infer_title(rows[0][0]):
                continue  # bare label with no data and no known meaning
            section_bar(pdf, title)
            pdf.set_font("guj", "", 8.5); pdf.set_text_color(0)
            pdf.multi_cell(0, 4.6, ("   ·   ".join(items)) if items else "—", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
            pdf.ln(1.5); continue

        header, body = rows[0], rows[1:]
        section_bar(pdf, infer_title(" ".join(header)) or "વિગત / Details")
        for r in [header] + body:
            while len(r) < ncols: r.append("")
        pdf.set_font("guj", "", 7.5); pdf.set_text_color(0)
        head_style = FontFace(emphasis="BOLD", fill_color=HEAD_FILL, color=(0, 0, 0))
        with pdf.table(col_widths=col_weights(header, body, ncols), first_row_as_headings=True,
                       line_height=4.2, headings_style=head_style, borders_layout="ALL",
                       text_align="LEFT", width=pdf.epw) as table:
            for r in [header] + body:
                trow = table.row()
                for c in r: trow.cell(c)
        pdf.ln(2.5)

    pdf.output(path)
    return len(pdf.pages)

state = json.load(open(STATE))
only = sys.argv[1] if len(sys.argv) > 1 else None
for key, info in state.items():
    if only and info["token"] != only and key != only: continue
    jpath = os.path.join(OUT, info["token"], f"anyror_{info['token']}.json")
    if not os.path.exists(jpath):
        print(f"{key}: no json, skip"); continue
    data = json.load(open(jpath))
    out = os.path.join(OUT, info["token"], f"AnyRoR_SurveyNo_{info['token']}_LandRecord.pdf")
    n = build(info["token"], key, data, out)
    print(f"  {key:<11} -> {n} pages  {os.path.basename(out)}")
print("ANYROR PDFS REBUILT")
