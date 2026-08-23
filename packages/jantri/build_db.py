#!/usr/bin/env python3
"""Load the parsed jantri CSVs into a single normalised SQLite database.

Location strings are held once in `villages`; `rates` and `survey_index` refer to
them by integer village_id (the survey index has ~7.5M entries, so repeating the
text there costs well over a gigabyte).
"""
import csv, glob, os, re, sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "data", "jantri", "out")
DB = os.path.join(ROOT, "data", "jantri", "jantri.sqlite")

SCHEMA = """
CREATE TABLE villages (
  village_id INTEGER PRIMARY KEY,
  district TEXT NOT NULL, taluka TEXT NOT NULL, village TEXT NOT NULL,
  village_key TEXT NOT NULL,          -- A-Z0-9 only, for cross-database matching
  n_rows INTEGER DEFAULT 0, n_surveys INTEGER DEFAULT 0);

-- one table row of the source PDF = one row_id; it may price several land types,
-- so `rates` can hold up to four cells per row_id, all sharing the survey list.
CREATE TABLE rates (
  row_id INTEGER NOT NULL, village_id INTEGER NOT NULL REFERENCES villages,
  page INTEGER, land_type TEXT, road_class TEXT,
  rate_per_acre_2011 INTEGER, rate_per_sqm_2011 INTEGER,
  rate_per_acre_2023 INTEGER, rate_per_sqm_2023 INTEGER,
  survey_numbers_raw TEXT);

CREATE TABLE survey_index (
  village_id INTEGER NOT NULL REFERENCES villages, row_id INTEGER NOT NULL,
  survey_base INTEGER NOT NULL,  -- numeric part, e.g. 857
  survey_suffix TEXT,            -- '' | 'PAIKI' | '1' | 'A' ...
  is_paiki INTEGER);

-- label lookups, so the Gujarati/English text is stored once
CREATE TABLE land_types (code TEXT PRIMARY KEY, gu TEXT, en TEXT);
CREATE TABLE road_classes (code TEXT PRIMARY KEY, gu TEXT, en TEXT);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
"""

INDEXES = """
CREATE INDEX ix_si_base  ON survey_index(village_id, survey_base);
CREATE INDEX ix_si_row   ON survey_index(row_id);
CREATE INDEX ix_rates_row ON rates(row_id);
CREATE INDEX ix_rates_vil ON rates(village_id);
CREATE INDEX ix_vil_key   ON villages(village_key);
CREATE INDEX ix_vil_dist  ON villages(district, village_key);
"""

def vkey(s):
    """Normalised join key: upper case, alphanumerics only.
    'AMARPUR(VARUDI)' -> 'AMARPURVARUDI'; 'ADRAJ MOTI' -> 'ADRAJMOTI'."""
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())

def main():
    if os.path.exists(DB):
        os.remove(DB)
    con = sqlite3.connect(DB)
    con.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    con.executescript(SCHEMA)

    vids, nr, ns = {}, 0, 0
    def vid(d, t, v):
        k = (d, t, vkey(v))
        if k not in vids:
            vids[k] = len(vids) + 1
            con.execute("INSERT INTO villages(village_id,district,taluka,village,"
                        "village_key) VALUES (?,?,?,?,?)", (vids[k], d, t, v, k[2]))
        return vids[k]

    rids = {}
    def rid(s):
        if s not in rids:
            rids[s] = len(rids) + 1
        return rids[s]

    for path in sorted(glob.glob(os.path.join(OUT, "*.rates.csv"))):
        batch = []
        for r in csv.DictReader(open(path, encoding="utf-8")):
            batch.append((rid(r["row_id"]), vid(r["district"], r["taluka"], r["village"]),
                          int(r["page"]), r["land_type"], r["road_class"],
                          int(r["rate_per_acre_2011"]), int(r["rate_per_sqm_2011"]),
                          int(r["rate_per_acre_2023"]), int(r["rate_per_sqm_2023"]),
                          r["survey_numbers_raw"]))
        con.executemany("INSERT INTO rates VALUES (?,?,?,?,?,?,?,?,?,?)", batch)
        nr += len(batch)

    for path in sorted(glob.glob(os.path.join(OUT, "*.survey_index.csv"))):
        batch = []
        for r in csv.DictReader(open(path, encoding="utf-8")):
            batch.append((vid(r["district"], r["taluka"], r["village"]),
                          rid(r["row_id"]), int(r["survey_base"]),
                          r["survey_suffix"], int(r["is_paiki"])))
        con.executemany("INSERT INTO survey_index VALUES (?,?,?,?,?)", batch)
        ns += len(batch)

    con.executescript(INDEXES)
    con.execute("""UPDATE villages SET
        n_rows=(SELECT COUNT(DISTINCT row_id) FROM rates r WHERE r.village_id=villages.village_id),
        n_surveys=(SELECT COUNT(*) FROM survey_index s WHERE s.village_id=villages.village_id)""")

    import sys
    sys.path.insert(0, HERE)
    from parse import LAND_TYPE_GUJ, GUJ_MAP
    con.executemany("INSERT INTO land_types VALUES (?,?,?)",
                    [(k, v[0], v[1]) for k, v in LAND_TYPE_GUJ.items()])
    con.executemany("INSERT INTO road_classes VALUES (?,?,?)",
                    [(v[0], v[1], v[2]) for v in GUJ_MAP.values()])
    con.executemany("INSERT INTO meta VALUES (?,?)", [
        ("source_url", "https://garvi.gujarat.gov.in/PDF/RURAL/<file>.pdf"),
        ("document", "ASR - 2011 Final (Annual Statement of Rates), rural agricultural land"),
        ("gr_date", "18/04/2011"),
        ("rate_2011_basis", "exactly as printed in the source PDF"),
        ("rate_2023_basis", "2011 rate x 2 (Gujarat jantri revision effective 15/04/2023) - DERIVED, not from the PDF"),
        ("sqm_per_acre", "4046.856422"),
        ("rate_cells", str(nr)), ("survey_entries", str(ns)),
        ("villages", str(len(vids))),
    ])
    con.commit()
    con.execute("VACUUM")
    con.close()
    print(f"{DB}\n  rate cells     {nr:>10,}\n  survey entries {ns:>10,}"
          f"\n  villages       {len(vids):>10,}"
          f"\n  file size      {os.path.getsize(DB)/1e6:>10.1f} MB")

if __name__ == "__main__":
    main()
