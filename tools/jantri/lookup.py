#!/usr/bin/env python3
"""Look up the jantri rate for a survey number.

    python3 tools/jantri/lookup.py KHEDA VALETVA 5
    python3 tools/jantri/lookup.py ANAND ADAS 857/Paiki

Resolution rules, in order:
  1. exact match on base + suffix  (857/PAIKI matches only the 857/Paiki entry)
  2. base-only match               (857 matches 857, and reports 857/1, 857/2 ... as related)
A survey number can legitimately appear in more than one row (~1% of cases), e.g.
part of it is on a highway and part is not. All matches are returned; the caller
decides whether to show "multiple rates apply".
"""
import os, re, sqlite3, sys

DB = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                  "..", "..", "data", "jantri", "jantri.sqlite")

def vkey(s):
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())

def norm(survey):
    m = re.fullmatch(r"\s*(\d+)\s*(?:/\s*([A-Za-z0-9]+))?\s*", survey)
    if not m:
        raise SystemExit(f"unparseable survey number: {survey!r}")
    suf = (m.group(2) or "").upper()
    if suf in ("PAIKI", "AIKI", "IKI", "PAIK"):
        suf = "PAIKI"
    return int(m.group(1)), suf

def lookup(con, district, village, survey):
    base, suf = norm(survey)
    rows = con.execute("""
        SELECT v.district, v.taluka, v.village, s.survey_base, s.survey_suffix,
               r.land_type, lt.gu, r.road_class, rc.en,
               r.rate_per_acre_2011, r.rate_per_sqm_2011,
               r.rate_per_acre_2023, r.rate_per_sqm_2023, r.page
          FROM survey_index s
          JOIN villages v ON v.village_id = s.village_id
          JOIN rates    r ON r.row_id     = s.row_id
     LEFT JOIN land_types   lt ON lt.code = r.land_type
     LEFT JOIN road_classes rc ON rc.code = r.road_class
         WHERE v.district = ? AND v.village_key = ? AND s.survey_base = ?
      ORDER BY s.survey_suffix, r.land_type""",
        (district.upper(), vkey(village), base)).fetchall()
    exact = [r for r in rows if (r[4] or "") == suf]
    return (exact or rows), bool(exact), suf

def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    district, village, survey = sys.argv[1:4]
    con = sqlite3.connect(DB)
    rows, was_exact, suf = lookup(con, district, village, survey)
    if not rows:
        print(f"no jantri entry for survey {survey} in {village}, {district}")
        return
    if not was_exact and suf:
        print(f"note: no entry for suffix /{suf}; showing all entries for that base number")
    seen = {(r[0], r[1], r[2]) for r in rows}
    if len(seen) > 1:
        print(f"warning: village name matches {len(seen)} talukas -- disambiguate: {sorted(seen)}")
    rowids = {(r[3], r[4]) for r in rows}
    if len(rowids) > 1:
        print(f"note: base number matches {len(rowids)} distinct survey entries")
    for (d, t, v, base, sfx, lt, ltgu, rc, rcen, a11, s11, a23, s23, page) in rows:
        num = f"{base}/{sfx}" if sfx else str(base)
        print(f"  {d}/{t}/{v}  survey {num:<12} {lt:<10} {rcen or rc or '-'}")
        print(f"      2011: Rs {s11:>6,}/sq.m   Rs {a11:>10,}/acre   (PDF p.{page})")
        print(f"      2023: Rs {s23:>6,}/sq.m   Rs {a23:>10,}/acre   (2011 x 2)")

if __name__ == "__main__":
    main()
