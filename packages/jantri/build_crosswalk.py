#!/usr/bin/env python3
"""Match jantri (ASR-2011) villages to iRCMS/AnyRoR villages for Anand + Kheda.

The jantri PDFs name villages in Latin transliteration with no codes; AnyRoR uses
Gujarati with official codes. The iRCMS cascade carries BOTH, so this is a join
rather than a transliteration problem.

Two complications, both handled here:
  * The ASR predates the 2013 reorganisation. Jantri's KHEDA still contains the
    Balasinor and Virpur talukas, which are now Mahisagar district -- so matching
    is district-wide, and Kheda additionally searches Mahisagar.
  * A village name is not unique within a district (names repeat across talukas),
    so taluka agreement is used to break ties.

Output: data/jantri/crosswalk.csv
"""
import csv, difflib, json, os, re, sqlite3, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DB = os.path.join(ROOT, "data", "jantri", "jantri.sqlite")
OUT = os.path.join(ROOT, "data", "jantri", "crosswalk.csv")

# jantri district -> cascade files to search (in order)
SOURCES = {
    "ANAND": [os.path.join(ROOT, "apps/android/app/src/main/assets/cascade/anand_15.json")],
    "KHEDA": [os.path.join(ROOT, "apps/android/app/src/main/assets/cascade/16.json"),
              os.path.join(ROOT, "data/jantri/cascade/32_mahisagar.json")],
}

def vk(s):
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())

def base_name(s):
    """Drop a parenthesised alias: 'DABHOU(VIRSADPURA)' -> 'DABHOU'."""
    return re.sub(r"\(.*?\)", " ", s or "").strip()

# 2013 reorganisation: talukas carved out of a 2011 taluka. Used only to break ties
# when a village name occurs more than once in a district.
SUCCESSORS = {
    "NADIAD": ["NADIAD", "NADIAD CITY", "VASO"],
    "THASRA": ["THASRA", "GALTESHWAR"],
    "KAPADVANJ": ["KAPADVANJ", "FAGVEL"],
    "KATHLAL": ["KATHLAL", "FAGVEL"],
    "MATAR": ["MATAR", "VASO"],
    "MAHUDHA": ["MAHUDHA", "VASO"],
    "KHEDA": ["KHEDA", "VASO"],
    "ANAND": ["ANAND", "ANAND (CITY)"],
}

def allowed_talukas(jt):
    return [loose(x) for x in SUCCESSORS.get(jt.upper(), [jt])]

# Transliteration variants seen between the two sources, applied to BOTH sides to
# produce a loose key. Order matters: longer digraphs first.
LOOSE = [("AA", "A"), ("EE", "I"), ("OO", "U"), ("Y", "I"), ("W", "V"),
         ("KH", "K"), ("GH", "G"), ("TH", "T"), ("DH", "D"), ("BH", "B"),
         ("PH", "F"), ("CHH", "CH"), ("SH", "S"), ("Z", "J"), ("H", ""),
         ("NAGAR", "NGR")]

def loose(s):
    s = vk(s)
    for a, b in LOOSE:
        s = s.replace(a, b)
    return re.sub(r"(.)\1+", r"\1", s)     # collapse doubled letters

def load_cascade(paths):
    """[(district_code, district_en, taluka_code, taluka_en, v_code, v_en, v_gu)]"""
    out = []
    for p in paths:
        j = json.load(open(p, encoding="utf-8"))
        d = j["district"]
        for t in j["talukas"]:
            for v in t.get("villages", []):
                out.append((d["code"], d["en"], t["code"], t["en"],
                            v["code"], v["en"], v.get("gu", "")))
    return out

def main():
    con = sqlite3.connect(DB)
    rows, stats = [], defaultdict(int)
    for district, paths in SOURCES.items():
        cas = load_cascade(paths)
        by_full, by_exact, by_loose = defaultdict(list), defaultdict(list), defaultdict(list)
        for c in cas:
            by_full[vk(c[5])].append(c)                 # incl. parenthesised alias
            by_exact[vk(base_name(c[5]))].append(c)
            by_loose[loose(base_name(c[5]))].append(c)
        jv = con.execute("SELECT village_id,taluka,village,village_key,n_rows,n_surveys "
                         "FROM villages WHERE district=? ORDER BY taluka,village",
                         (district,)).fetchall()

        # Candidate set per jantri village. Full name (with any parenthesised alias)
        # is tried first: 'KHADOL (HALDARI)' and 'KHADOL (UMETA)' are two different
        # villages and must not both collapse onto the base name 'KHADOL'.
        cand_of, meth_of = {}, {}
        for vid, jt, jvil, jkey, nrows, nsurv in jv:
            for key, table, m in ((vk(jvil), by_full, "exact"),
                                  (vk(base_name(jvil)), by_exact, "exact"),
                                  (loose(base_name(jvil)), by_loose, "loose")):
                if table.get(key):
                    cand_of[vid], meth_of[vid] = table[key], m
                    break
            else:
                near = difflib.get_close_matches(loose(base_name(jvil)),
                                                 list(by_loose), 1, 0.86)
                if near:
                    cand_of[vid], meth_of[vid] = by_loose[near[0]], "fuzzy"

        # Solve each name-group as one assignment rather than greedily in row order,
        # so an exact taluka match always wins over an earlier row's arbitrary claim.
        groups = defaultdict(list)
        for vid in cand_of:
            # identity = (district, taluka, village) codes; a village code alone is
            # NOT unique -- the same code is reused in every taluka.
            groups[tuple(sorted((c[0], c[2], c[4]) for c in cand_of[vid]))].append(vid)
        info = {vid: (jt, jvil) for vid, jt, jvil, _, _, _ in jv}
        chosen, tie_of = {}, {}
        for gkey, vids in groups.items():
            pool = list(cand_of[vids[0]])
            for phase, tie in ((lambda jt, c: loose(c[3]) == loose(jt), "taluka"),
                               (lambda jt, c: loose(c[3]) in allowed_talukas(jt), "successor")):
                for vid in list(vids):
                    if vid in chosen:
                        continue
                    hit = [c for c in pool if phase(info[vid][0], c)]
                    if hit:
                        chosen[vid], tie_of[vid] = hit[0], tie
                        pool.remove(hit[0])
            for vid in vids:                       # leftovers take what remains
                if vid in chosen:
                    continue
                if pool:
                    chosen[vid] = pool.pop(0)
                    tie_of[vid] = "assigned" if len(gkey) > 1 else "unique"

        for vid, jt, jvil, jkey, nrows, nsurv in jv:
            c = chosen.get(vid)
            method, tie = meth_of.get(vid, ""), tie_of.get(vid, "")
            if not c:
                stats[f"{district}:unmatched"] += 1
                rows.append(dict(jantri_district=district, jantri_taluka=jt,
                                 jantri_village=jvil, village_key=jkey,
                                 village_id=vid, n_rows=nrows, n_surveys=nsurv,
                                 match="none", tie="", ircms_district="",
                                 ircms_district_code="", ircms_taluka="",
                                 ircms_taluka_code="", ircms_village_code="",
                                 ircms_village_en="", ircms_village_gu=""))
                continue
            stats[f"{district}:{method}"] += 1
            if tie in ("assigned", "successor"):
                stats[f"{district}:tie={tie}"] += 1
            rows.append(dict(jantri_district=district, jantri_taluka=jt,
                             jantri_village=jvil, village_key=jkey, village_id=vid,
                             n_rows=nrows, n_surveys=nsurv, match=method, tie=tie,
                             ircms_district=c[1], ircms_district_code=c[0],
                             ircms_taluka=c[3], ircms_taluka_code=c[2],
                             ircms_village_code=c[4], ircms_village_en=c[5],
                             ircms_village_gu=c[6]))
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    for k in sorted(stats):
        print(f"  {k:<22} {stats[k]:>5}")
    tot = len(rows)
    matched = sum(1 for r in rows if r["match"] != "none")
    print(f"\n{matched}/{tot} villages matched ({100*matched/tot:.1f}%) -> {OUT}")

if __name__ == "__main__":
    main()
