#!/usr/bin/env python3
"""Build the app asset: jantri rates for Anand + Kheda, keyed so the Android app
can find a village by whatever name it holds.

The app stores a property's district/taluka/village as English strings from the
AnyRoR/iRCMS cascade; the jantri PDFs use their own transliteration. `village_lookup`
therefore carries a normalised key row for EVERY known spelling of a village --
the jantri name, the iRCMS English name, and the Gujarati name -- all pointing at
the same village_id. Lookup never has to guess.

Output: apps/android/app/src/main/assets/jantri/jantri.sqlite
"""
import csv, os, re, sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(ROOT, "data", "jantri", "jantri.sqlite")
CROSSWALK = os.path.join(ROOT, "data", "jantri", "crosswalk.csv")
DST_DIR = os.path.join(ROOT, "android", "app", "src", "main", "assets", "jantri")
DST = os.path.join(DST_DIR, "jantri.sqlite")
DISTRICTS = ["ANAND", "KHEDA"]

LT = {"dry": 0, "irrigated": 1, "waste": 2, "mineral": 3}
RC = {"": 0, "SAMANYA": 1, "DISTRICT_ROAD": 2, "HIGHWAY": 3}

SCHEMA = """
PRAGMA page_size=4096;
CREATE TABLE villages (
  village_id INTEGER PRIMARY KEY, district TEXT, taluka TEXT, village TEXT,
  ircms_district TEXT, ircms_taluka TEXT, ircms_village TEXT, ircms_village_gu TEXT,
  ircms_village_code TEXT, match_quality TEXT);
-- every known spelling -> village_id. key = A-Z0-9 upper (Gujarati kept verbatim).
CREATE TABLE village_lookup (key TEXT NOT NULL, village_id INTEGER NOT NULL);
-- land_type 0 dry 1 irrigated 2 waste 3 mineral; road_class 0 unknown 1 general
-- 2 district road 3 highway. sq.m = round(acre/4046.856422); 2023 = acre x 2.
CREATE TABLE rates (
  row_id INTEGER NOT NULL, village_id INTEGER NOT NULL,
  land_type INTEGER NOT NULL, road_class INTEGER NOT NULL, acre_2011 INTEGER NOT NULL);
CREATE TABLE sranges (
  village_id INTEGER NOT NULL, row_id INTEGER NOT NULL,
  lo INTEGER NOT NULL, hi INTEGER NOT NULL);
CREATE TABLE ssub (
  village_id INTEGER NOT NULL, row_id INTEGER NOT NULL,
  base INTEGER NOT NULL, suffix TEXT NOT NULL);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
"""
INDEXES = """
CREATE INDEX ix_lookup ON village_lookup(key);
CREATE INDEX ix_rates  ON rates(row_id);
CREATE INDEX ix_sr     ON sranges(village_id, lo, hi);
CREATE INDEX ix_ss     ON ssub(village_id, base);
"""

def key(s):
    s = (s or "").strip()
    if not s:
        return ""
    if re.search(r"[઀-૿]", s):          # Gujarati: keep as-is, strip spaces
        return re.sub(r"\s+", "", s)
    return re.sub(r"[^A-Z0-9]", "", s.upper())

def main():
    os.makedirs(DST_DIR, exist_ok=True)
    if os.path.exists(DST):
        os.remove(DST)
    src = sqlite3.connect(SRC)
    d = sqlite3.connect(DST)
    d.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    d.executescript(SCHEMA)

    cw = {int(r["village_id"]): r for r in csv.DictReader(open(CROSSWALK, encoding="utf-8"))
          if r["jantri_district"] in DISTRICTS}
    vids = list(cw)
    q = ",".join("?" * len(vids))

    rows, lookups = [], []
    for vid, dist, tal, vil in src.execute(
            f"SELECT village_id,district,taluka,village FROM villages WHERE village_id IN ({q})", vids):
        c = cw[vid]
        quality = c["match"] if c["match"] != "none" else "unmatched"
        if c["tie"] in ("successor", "assigned"):
            quality += "+" + c["tie"]
        rows.append((vid, dist, tal, vil, c["ircms_district"], c["ircms_taluka"],
                     c["ircms_village_en"], c["ircms_village_gu"],
                     c["ircms_village_code"], quality))
        for name in (vil, c["ircms_village_en"], c["ircms_village_gu"]):
            k = key(name)
            if k:
                lookups.append((k, vid))
            kb = key(re.sub(r"\(.*?\)", "", name or ""))   # alias-stripped form
            if kb and kb != k:
                lookups.append((kb, vid))
    d.executemany("INSERT INTO villages VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
    d.executemany("INSERT INTO village_lookup VALUES (?,?)", sorted(set(lookups)))

    rids = {}
    def rid(s):
        if s not in rids:
            rids[s] = len(rids) + 1
        return rids[s]
    d.executemany("INSERT INTO rates VALUES (?,?,?,?,?)", [
        (rid(r), v, LT[lt], RC.get(rc, 0), a) for r, v, lt, rc, a in src.execute(
            f"SELECT row_id,village_id,land_type,road_class,rate_per_acre_2011 "
            f"FROM rates WHERE village_id IN ({q})", vids)])

    ranges, subs, run = [], [], None
    for v, r, b, s in src.execute(
            f"SELECT village_id,row_id,survey_base,survey_suffix FROM survey_index "
            f"WHERE village_id IN ({q}) ORDER BY village_id,row_id,survey_base", vids):
        if s:
            subs.append((v, rid(r), b, s))
            continue
        if run and run[0] == v and run[1] == rid(r) and b == run[3] + 1:
            run = (v, run[1], run[2], b)
        else:
            if run:
                ranges.append(run)
            run = (v, rid(r), b, b)
    if run:
        ranges.append(run)
    d.executemany("INSERT INTO sranges VALUES (?,?,?,?)", ranges)
    d.executemany("INSERT INTO ssub VALUES (?,?,?,?)", subs)
    d.executescript(INDEXES)
    d.executemany("INSERT INTO meta VALUES (?,?)", [
        ("districts", ", ".join(DISTRICTS)),
        ("document", "Gujarat ASR-2011 Final (jantri), rural agricultural land"),
        ("source", "garvi.gujarat.gov.in/PDF/RURAL"),
        ("gr_date", "18/04/2011"),
        ("sqm_per_acre", "4046.856422"),
        ("rate_2023", "2011 x 2 (revision effective 15/04/2023), derived"),
        ("disclaimer", "Stamp-duty floor rate, not a market valuation."),
        ("schema", "1"),
    ])
    d.commit()
    d.execute("VACUUM")
    d.close()
    print(f"{DST}\n  villages {len(rows):,}  lookup keys {len(set(lookups)):,}"
          f"  ranges {len(ranges):,}  suffixed {len(subs):,}"
          f"\n  size {os.path.getsize(DST)/1e6:.1f} MB")

if __name__ == "__main__":
    main()
