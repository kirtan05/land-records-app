#!/usr/bin/env python3
"""Evaluate basic captcha solvers against the tagged samples.

  packages/captcha/.venv/bin/python packages/captcha/ocr-eval.py [--set ircms|anyror|both]

Approaches:
  tesseract  — several preprocessing variants (the AnyRoR images have patterned
               backgrounds + grid lines; iRCMS have scribble lines + noise dots)
  ddddocr    — small CRNN captcha model, CPU/onnxruntime (the "mini-model" tier)

Reports exact-match and per-char accuracy per variant. Ground truth:
  ircms/labels.csv  (auto-filled from the SVG text nodes — exact)
  packages/anyror/labels.csv (human-tagged via tag-anyror.py)
"""
import argparse
import csv
import os
import sys
import time
from pathlib import Path

# self-contained tessdata (downloaded once into packages/captcha/tessdata)
os.environ.setdefault("TESSDATA_PREFIX", str(Path(__file__).parent / "tessdata"))

import cv2
import numpy as np
import pytesseract

ROOT = Path(__file__).parent / "samples"


def load_labels(ds):
    p = ROOT / ds / "labels.csv"
    if not p.exists():
        return None
    rows = [r for r in csv.reader(p.read_text().splitlines()) if len(r) >= 2 and r[0] != "file"]
    return {f: l.strip().lower() for f, l in rows if l.strip() and l.strip() != "?"}


# ── preprocessing variants (all return a binarized/upscaled image) ─────────────
def v_raw(img):
    return cv2.resize(img, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)


def v_gray_otsu(img):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    g = cv2.resize(g, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)
    return cv2.threshold(g, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]


def v_blackhat(img):
    """Dark text on patterned light bg: blackhat removes slow-varying/patterned bg."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    bh = cv2.morphologyEx(g, cv2.MORPH_BLACKHAT, cv2.getStructuringElement(cv2.MORPH_RECT, (19, 19)))
    bw = cv2.threshold(bh, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
    bw = cv2.bitwise_not(bw)  # tesseract wants black text on white
    return cv2.resize(bw, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)


def v_bgdiff(img):
    """Estimate background with a big median blur; keep pixels much darker than it."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    bg = cv2.medianBlur(g, 25)
    diff = cv2.subtract(bg, g)
    bw = cv2.threshold(diff, 28, 255, cv2.THRESH_BINARY)[1]
    bw = cv2.bitwise_not(bw)
    return cv2.resize(bw, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)


def v_blackhat_clean(img):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    bh = cv2.morphologyEx(g, cv2.MORPH_BLACKHAT, cv2.getStructuringElement(cv2.MORPH_RECT, (19, 19)))
    bw = cv2.threshold(bh, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
    bw = cv2.morphologyEx(bw, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2)))
    bw = cv2.bitwise_not(bw)
    return cv2.resize(bw, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)


def tess(img, charset, psm=7):
    cfg = f'--psm {psm} -c tessedit_char_whitelist={charset}'
    return pytesseract.image_to_string(img, config=cfg).strip().lower().replace(" ", "")


def char_acc(pred, truth):
    if not truth:
        return 0.0
    n = max(len(pred), len(truth))
    hits = sum(1 for a, b in zip(pred, truth) if a == b)
    return hits / n


def evaluate(ds, variants, charset, use_dddd=True):
    labels = load_labels(ds)
    if not labels:
        print(f"[{ds}] no labels yet — tag first (tag-anyror.py)" if ds == "anyror" else f"[{ds}] no labels.csv")
        return
    ocr = None
    if use_dddd:
        import ddddocr
        ocr = ddddocr.DdddOcr(show_ad=False)

    print(f"\n=== {ds}: {len(labels)} labeled samples ===")
    header = f"{'variant':<18} {'exact':>7} {'char-acc':>9} {'avg ms':>7}"
    print(header + "\n" + "-" * len(header))
    for name, fn in variants:
        exact = 0
        ca = 0.0
        t0 = time.time()
        fails = []
        for f, truth in labels.items():
            img = cv2.imread(str(ROOT / ds / f))
            if img is None or img.size == 0:
                print(f"    skip {f}: unreadable/empty image")
                continue
            pred = fn(img, charset) if charset else fn(img)
            pred = "".join(c for c in pred if c.isalnum())
            if pred == truth:
                exact += 1
            elif len(fails) < 5:
                fails.append((f, truth, pred))
            ca += char_acc(pred, truth)
        ms = (time.time() - t0) / len(labels) * 1000
        print(f"{name:<18} {exact / len(labels):>6.1%} {ca / len(labels):>8.1%} {ms:>7.0f}")
        for f, t, p in fails:
            print(f"    miss {f}: truth={t!r} pred={p!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="both", choices=["ircms", "anyror", "both"])
    args = ap.parse_args()

    if args.set in ("anyror", "both"):
        charset = "0123456789"  # AnyRoR sample sheet shows digits only
        import ddddocr
        d_old = ddddocr.DdddOcr(show_ad=False)

        def dd(img):
            ok, buf = cv2.imencode(".png", img)
            return d_old.classification(buf.tobytes()).strip()

        variants = [
            ("tess raw", lambda i, c: tess(v_raw(i), c)),
            ("tess otsu", lambda i, c: tess(v_gray_otsu(i), c)),
            ("tess blackhat", lambda i, c: tess(v_blackhat(i), c)),
            ("tess bgdiff", lambda i, c: tess(v_bgdiff(i), c)),
            ("tess bh+clean", lambda i, c: tess(v_blackhat_clean(i), c)),
            ("tess bh psm8", lambda i, c: tess(v_blackhat(i), c, psm=8)),
            ("ddddocr", lambda i, c: dd(i)),
        ]
        evaluate("anyror", variants, charset)

    if args.set in ("ircms", "both"):
        charset = "0123456789abcdef"
        import ddddocr
        d_old = ddddocr.DdddOcr(show_ad=False)

        def dd2(img):
            ok, buf = cv2.imencode(".png", img)
            return d_old.classification(buf.tobytes()).strip()

        variants = [
            ("tess raw", lambda i, c: tess(v_raw(i), c)),
            ("tess otsu", lambda i, c: tess(v_gray_otsu(i), c)),
            ("tess blackhat", lambda i, c: tess(v_blackhat(i), c)),
            ("ddddocr", lambda i, c: dd2(i)),
        ]
        evaluate("ircms", variants, charset)


if __name__ == "__main__":
    main()
