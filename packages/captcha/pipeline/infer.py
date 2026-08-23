#!/usr/bin/env python3
"""Fast AnyRoR captcha inference — onnxruntime, batched, zero warm-up per call.

At batch size 1 the wall clock is dominated by process start + imports +
session build (~220 ms), against ~0.6 ms of actual inference. That shapes the
three modes — anything solving more than one captcha should avoid paying the
start-up twice:

  # one-shot: ~220 ms, almost all of it interpreter start
  infer_anyror.py shot.png

  # batch a directory: pays start-up once, ~1,600 img/s after that
  infer_anyror.py samples/anyror/*.png
  infer_anyror.py --dir samples/anyror --csv out.csv

  # server: one process, one session, answers a path (or `-` + raw PNG) per
  # line on stdin. Per-solve cost drops to the forward pass alone (sub-ms).
  infer_anyror.py --serve < paths.txt        # lines: a path, or b64:<png-base64>

  # accuracy check against labels.csv
  infer_anyror.py --dir samples/anyror --eval [--split test]

Library use — build the session once and keep it:

  from infer_anyror import Solver
  s = Solver()
  text, conf = s.solve_bytes(png)          # one
  for t, c in s.solve_many(list_of_paths): ...

Speed notes, measured on this box (16 cores, onnxruntime 1.28 CPU):

    threads=1    545 img/s batched    1.830 ms per solve
    threads=4  1,608 img/s batched    0.623 ms per solve   ← default
    threads=8  2,070 img/s batched    0.492 ms per solve
    threads=16 1,758 img/s batched    1.071 ms per solve   (oversubscribed)

The forward pass, not PNG decode, is the cost — so batching buys nothing on
its own and --threads is the dial that matters. 4 is the default as a balance
against the rest of the machine; pass --threads 8 when the box is idle.
"""
import argparse
import base64
import csv
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
IW, IH = 160, 64
DEFAULT_MODEL = ROOT / "model" / "anyror_cnn_real.onnx"


class Solver:
    def __init__(self, model=DEFAULT_MODEL, threads=4):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(str(model), so, providers=["CPUExecutionProvider"])
        self.name = self.sess.get_inputs()[0].name

    @staticmethod
    def prep(img):
        """grayscale uint8 (any size) → 1×IH×IW float32 in [0,1]."""
        if img.ndim == 3:
            img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        if img.shape != (IH, IW):
            img = cv2.resize(img, (IW, IH), interpolation=cv2.INTER_AREA)
        return img.astype(np.float32, copy=False) / 255.0

    def _run(self, batch):
        """batch: N×IH×IW float32 → (list[str], list[float])."""
        logits = self.sess.run(None, {self.name: batch[:, None]})[0]      # N,6,10
        logits -= logits.max(axis=-1, keepdims=True)
        np.exp(logits, out=logits)
        logits /= logits.sum(axis=-1, keepdims=True)
        pred = logits.argmax(axis=-1)                                     # N,6
        conf = np.take_along_axis(logits, pred[..., None], -1)[..., 0].prod(axis=1)
        return ["".join(map(str, row)) for row in pred], conf.tolist()

    def solve_bytes(self, png_bytes):
        img = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_GRAYSCALE)
        if img is None:
            raise ValueError("not a decodable image")
        t, c = self._run(self.prep(img)[None])
        return t[0], c[0]

    def solve_path(self, path):
        img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if img is None:
            raise ValueError(f"not a decodable image: {path}")
        t, c = self._run(self.prep(img)[None])
        return t[0], c[0]

    def solve_many(self, paths, bs=256):
        """Batched over the whole list. Decode is cheap next to the forward pass,
        so this is compute-bound — see --threads."""
        out = []
        for i in range(0, len(paths), bs):
            chunk = paths[i:i + bs]
            batch = np.empty((len(chunk), IH, IW), np.float32)
            ok = []
            for j, p in enumerate(chunk):
                img = cv2.imread(str(p), cv2.IMREAD_GRAYSCALE)
                if img is None:
                    continue
                batch[len(ok)] = self.prep(img)
                ok.append(p)
            if not ok:
                out.extend([(None, 0.0)] * len(chunk))
                continue
            texts, confs = self._run(batch[:len(ok)])
            res = dict(zip(map(str, ok), zip(texts, confs)))
            out.extend(res.get(str(p), (None, 0.0)) for p in chunk)
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="*", help="PNG files to solve")
    ap.add_argument("--dir", help="solve every .png in this directory")
    ap.add_argument("--model", default=str(DEFAULT_MODEL))
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--bs", type=int, default=256)
    ap.add_argument("--csv", help="write file,pred,conf here")
    ap.add_argument("--serve", action="store_true",
                    help="read a path per line on stdin, print a prediction per line")
    ap.add_argument("--eval", action="store_true", help="score against labels.csv in --dir")
    ap.add_argument("--split", choices=["train", "val", "test"],
                    help="with --eval, restrict to one split from <model>.split.json")
    ap.add_argument("--bench", action="store_true", help="report throughput")
    args = ap.parse_args()

    solver = Solver(args.model, args.threads)

    if args.serve:
        for line in sys.stdin:
            p = line.strip()
            if not p:
                continue
            try:
                # a line is either a path, or `b64:<data>` (AnyRoR bakes the captcha
                # into the page as a data URI, so the browser has the bytes already)
                if p.startswith("b64:"):
                    text, conf = solver.solve_bytes(base64.b64decode(p[4:]))
                else:
                    text, conf = solver.solve_path(p)
                print(f"{text}\t{conf:.4f}", flush=True)
            except Exception as e:                       # never kill the server on one bad file
                print(f"ERR\t{e}", flush=True)
        return

    paths = [Path(p) for p in args.paths]
    if args.dir:
        paths += sorted(Path(args.dir).glob("*.png"))

    truth = {}
    if args.eval:
        d = Path(args.dir or ROOT / "samples" / "anyror")
        truth = {r[0]: r[1].strip() for r in csv.reader((d / "labels.csv").read_text().splitlines())
                 if len(r) >= 2 and r[0] != "file"}
        if args.split:
            sp = Path(args.model).with_suffix("").with_suffix(".split.json")
            if not sp.exists():
                sp = Path(str(Path(args.model).with_suffix("")) + ".split.json")
            keep = set(json.loads(sp.read_text())[args.split])
            paths = [p for p in paths if p.name in keep]
        paths = [p for p in paths if p.name in truth]

    if not paths:
        sys.exit("no images given (pass files, --dir, or --serve)")

    t0 = time.perf_counter()
    results = solver.solve_many(paths, args.bs)
    dt = time.perf_counter() - t0

    if args.csv:
        with open(args.csv, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["file", "pred", "conf"])
            w.writerows([p.name, t or "", f"{c:.4f}"] for p, (t, c) in zip(paths, results))
    elif not args.eval:
        for p, (t, c) in zip(paths, results):
            print(f"{t}\t{c:.4f}\t{p.name}" if len(paths) > 1 else t)

    if args.eval:
        hit = sum(t == truth[p.name] for p, (t, _) in zip(paths, results))
        # a wrong-but-confident read is the expensive failure: it burns a submit
        wrong_conf = [c for p, (t, c) in zip(paths, results) if t != truth[p.name] and c > 0.9]
        label = f"{args.split} split" if args.split else "all labelled"
        print(f"{label}: {hit}/{len(paths)} exact = {hit / len(paths):.2%}"
              f"   ({len(wrong_conf)} wrong at conf>0.9)")

    if args.bench or args.eval:
        print(f"{len(paths)} images in {dt * 1000:.0f} ms = {len(paths) / dt:,.0f} img/s"
              f"  ({dt / len(paths) * 1000:.3f} ms each, decode included)", file=sys.stderr)


if __name__ == "__main__":
    main()
