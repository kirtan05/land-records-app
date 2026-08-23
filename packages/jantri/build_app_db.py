#!/usr/bin/env python3
"""Build a compact, ship-in-the-APK version of the jantri database.

Size comes from three structural facts, not from generic compression:

  * every per-sq.m figure in the source equals round(per_acre / 4046.856422)
    (verified for all 1,074,451 cells), and the 2023 rate is 2x the 2011 rate --
    so only per_acre_2011 is stored and the other three are derived at read time;
  * survey numbers within a row are mostly consecutive runs, so the 7.5M-entry
    index becomes ~1.4M (lo..hi) ranges plus a small table of suffixed entries;
  * `survey_numbers_raw`, `page` and the label text are provenance, not lookup
    data, and are dropped (they stay in the full jantri.sqlite).

Emits one DB per district so the app downloads only what a user needs, plus
`_all.sqlite` for reference.
"""
import os, sqlite3, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(ROOT, "data", "jantri", "jantri.sqlite")
DST = os.path.join(ROOT, "data", "jantri", "app")

LT = {"dry": 0, "irrigated": 1, "waste": 2, "mineral": 3}
RC = {"": 0, "SAMANYA": 1, "DISTRICT_ROAD": 2, "HIGHWAY": 3}

SCHEMA = """
PRAGMA page_size=4096;
CREATE TABLE villages (
  village_id INTEGER PRIMARY KEY, taluka TEXT NOT NULL,
  village TEXT NOT NULL, village_key TEXT NOT NULL);
-- land_type: 0 dry 1 irrigated 2 waste 3 mineral
-- road_class: 0 unknown 1 general 2 district road 3 highway
-- per sq.m = round(acre/4046.856422); 2023 rate = 2x
CREATE TABLE rates (
  row_id INTEGER NOT NULL, village_id INTEGER NOT NULL,
  land_type INTEGER NOT NULL, road_class INTEGER NOT NULL,
  acre_2011 INTEGER NOT NULL);
CREATE TABLE sranges (            -- plain survey numbers, run-length encoded
  village_id INTEGER NOT NULL, row_id INTEGER NOT NULL,
  lo INTEGER NOT NULL, hi INTEGER NOT NULL);
CREATE TABLE ssub (               -- survey numbers carrying a suffix
  village_id INTEGER NOT NULL, row_id INTEGER NOT NULL,
  base INTEGER NOT NULL, suffix TEXT NOT NULL);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
"""
INDEXES = """
CREATE INDEX ix_r  ON rates(row_id);
CREATE INDEX ix_sr ON sranges(village_id, lo, hi);
CREATE INDEX ix_ss ON ssub(village_id, base);
CREATE INDEX ix_vk ON villages(village_key);
"""

def build(src, district, path):
    if os.path.exists(path):
        os.remove(path)
    d = sqlite3.connect(path)
    d.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    d.executescript(SCHEMA)
    vids = [r[0] for r in src.execute(
        "SELECT village_id FROM villages WHERE district=?", (district,))]
    if not vids:
        return None
    qmarks = ",".join("?" * len(vids))
    d.executemany("INSERT INTO villages VALUES (?,?,?,?)", src.execute(
        f"SELECT village_id,taluka,village,village_key FROM villages "
        f"WHERE village_id IN ({qmarks})", vids).fetchall())
    d.executemany("INSERT INTO rates VALUES (?,?,?,?,?)", [
        (rid, vid, LT[lt], RC.get(rc, 0), acre) for rid, vid, lt, rc, acre in src.execute(
            f"SELECT row_id,village_id,land_type,road_class,rate_per_acre_2011 "
            f"FROM rates WHERE village_id IN ({qmarks})", vids)])

    rows = src.execute(
        f"SELECT village_id,row_id,survey_base,survey_suffix FROM survey_index "
        f"WHERE village_id IN ({qmarks}) ORDER BY village_id,row_id,survey_base",
        vids).fetchall()
    ranges, subs, run = [], [], None
    for v, rid, b, s in rows:
        if s:
            subs.append((v, rid, b, s))
            continue
        if run and run[0] == v and run[1] == rid and b == run[3] + 1:
            run = (v, rid, run[2], b)
        else:
            if run:
                ranges.append(run)
            run = (v, rid, b, b)
    if run:
        ranges.append(run)
    d.executemany("INSERT INTO sranges VALUES (?,?,?,?)", ranges)
    d.executemany("INSERT INTO ssub VALUES (?,?,?,?)", subs)
    d.executescript(INDEXES)
    d.executemany("INSERT INTO meta VALUES (?,?)", [
        ("district", district),
        ("source", "Gujarat ASR-2011 Final, garvi.gujarat.gov.in"),
        ("sqm_per_acre", "4046.856422"),
        ("rate_2023", "2011 x 2, per the revision effective 15/04/2023 (derived)"),
        ("note", "jantri is a stamp-duty floor, not a market valuation"),
    ])
    d.commit()
    d.execute("VACUUM")
    d.close()
    return len(ranges), len(subs)

def main():
    os.makedirs(DST, exist_ok=True)
    src = sqlite3.connect(SRC)
    dists = [r[0] for r in src.execute("SELECT DISTINCT district FROM villages ORDER BY 1")]
    tot = totz = 0
    print(f"{'district':<15}{'raw':>9}{'gzip':>9}  ranges/suffixed")
    for dn in dists:
        p = os.path.join(DST, f"{dn}.sqlite")
        nr, ns = build(src, dn, p)
        sz = os.path.getsize(p)
        z = len(zlib.compress(open(p, "rb").read(), 9))
        tot += sz; totz += z
        print(f"{dn:<15}{sz/1e6:>8.1f}M{z/1e6:>8.1f}M  {nr:>9,}/{ns:,}")
    print(f"{'TOTAL':<15}{tot/1e6:>8.1f}M{totz/1e6:>8.1f}M")

if __name__ == "__main__":
    main()
