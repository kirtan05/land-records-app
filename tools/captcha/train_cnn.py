#!/usr/bin/env python3
"""Train a small multi-head CNN on AnyRoR captchas.
Fixed length (6) × fixed charset (10 digits) → 6 softmax heads, no CTC needed
(the captcha-break recipe, modernised to PyTorch).

The synthetic generator turned out to be far off the real distribution — a
synth-trained model scores ~14% on real captchas — so train on the hand-tagged
real set (`--data real`), which reaches ~97%.

  # real data, 80/10/10 train/val/test, augmented
  tools/captcha/.venv/bin/python tools/captcha/train_cnn.py \
      --data real --augment --epochs 60 --bs 64 --out anyror_cnn_real.pt

Model selection is on val; the test split is scored exactly once at the end.
The chosen weights are written to --out (+ a matching .onnx and .split.json).

  --data synth  keeps the original behaviour: train on synth/, score on the
                whole real set every epoch.
"""
import argparse
import csv
import json
import time
from pathlib import Path

import cv2
import numpy as np
import torch
import torch.nn as nn

ROOT = Path(__file__).parent
SYNTH = ROOT / "synth"
REAL = ROOT / "samples" / "anyror"
IW, IH = 160, 64  # model input (downscaled from 190x80)


def load(dir_path, limit=0, with_names=False):
    rows = [r for r in csv.reader((dir_path / "labels.csv").read_text().splitlines())
            if len(r) >= 2 and r[0] != "file"]
    if limit:
        rows = rows[:limit]
    imgs = np.empty((len(rows), IH, IW), dtype=np.uint8)
    labels = np.empty((len(rows), 6), dtype=np.int64)
    keep = []
    for i, (f, lab) in enumerate(rows):
        lab = lab.strip()
        img = cv2.imread(str(dir_path / f), cv2.IMREAD_GRAYSCALE)
        if img is None or len(lab) != 6:
            continue
        imgs[i] = cv2.resize(img, (IW, IH), interpolation=cv2.INTER_AREA)
        labels[i] = [int(c) for c in lab]
        keep.append(i)
    if with_names:
        return imgs[keep], labels[keep], [rows[i][0] for i in keep]
    return imgs[keep], labels[keep]


def augment(im):
    """Small affine jitter + brightness/noise — the real set is only ~1.6k images."""
    a = np.random.uniform(-4, 4)                       # degrees
    s = np.random.uniform(0.94, 1.06)
    tx, ty = np.random.uniform(-3, 3), np.random.uniform(-2, 2)
    M = cv2.getRotationMatrix2D((IW / 2, IH / 2), a, s)
    M[0, 2] += tx; M[1, 2] += ty
    out = cv2.warpAffine(im, M, (IW, IH), flags=cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_REPLICATE)
    out = np.clip(out.astype(np.float32) * np.random.uniform(0.85, 1.15)
                  + np.random.normal(0, 4, out.shape), 0, 255)
    return out.astype(np.uint8)


class CaptchaCNN(nn.Module):
    def __init__(self):
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(1, 32, 3, padding=1), nn.BatchNorm2d(32), nn.ReLU(), nn.MaxPool2d(2),   # 32x80
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.ReLU(), nn.MaxPool2d(2),  # 16x40
            nn.Conv2d(64, 128, 3, padding=1), nn.BatchNorm2d(128), nn.ReLU(), nn.MaxPool2d(2),  # 8x20
            nn.Conv2d(128, 128, 3, padding=1), nn.BatchNorm2d(128), nn.ReLU(),
            nn.AdaptiveAvgPool2d((2, 5)),
        )
        self.fc = nn.Sequential(nn.Flatten(), nn.Linear(128 * 2 * 5, 384), nn.ReLU(), nn.Dropout(0.25))
        self.heads = nn.ModuleList([nn.Linear(384, 10) for _ in range(6)])

    def forward(self, x):
        h = self.fc(self.features(x))
        return torch.stack([hd(h) for hd in self.heads], dim=1)  # B,6,10


def exact_acc(model, imgs, labels, bs=256):
    model.eval()
    correct = 0
    with torch.no_grad():
        for i in range(0, len(imgs), bs):
            x = torch.from_numpy(imgs[i:i + bs]).float().div_(255).unsqueeze(1)
            pred = model(x).argmax(dim=2)
            correct += (pred == torch.from_numpy(labels[i:i + bs])).all(dim=1).sum().item()
    return correct / len(labels)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--limit", type=int, default=0, help="cap synth samples (0 = all)")
    ap.add_argument("--bs", type=int, default=128)
    ap.add_argument("--data", choices=["synth", "real", "both"], default="synth",
                    help="what to train on; real/both split the real set into train/val/test")
    ap.add_argument("--val", type=float, default=0.1, help="fraction of real held out for val (model selection)")
    ap.add_argument("--test", type=float, default=0.1, help="fraction of real held out for test (scored once, at the end)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default="anyror_cnn_best.pt")
    ap.add_argument("--augment", action="store_true", help="affine/brightness jitter on train batches")
    args = ap.parse_args()

    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    print("loading real…", flush=True)
    xr_all, yr_all, names = load(REAL, with_names=True)
    empty = (np.empty((0, IH, IW), np.uint8), np.empty((0, 6), np.int64))
    if args.data == "synth":
        xv, yv = xr_all, yr_all          # whole real set is the val set, nothing held back
        xte, yte = empty
        xtr, ytr = empty
    else:
        perm = np.random.permutation(len(xr_all))
        nv = max(1, int(len(perm) * args.val))
        nt = max(1, int(len(perm) * args.test))
        vi, ti, tri = perm[:nv], perm[nv:nv + nt], perm[nv + nt:]
        xv, yv = xr_all[vi], yr_all[vi]
        xte, yte = xr_all[ti], yr_all[ti]
        xtr, ytr = xr_all[tri], yr_all[tri]
        # the split is recorded by filename: labels.csv keeps growing as you tag,
        # so a seed alone would silently reshuffle test images into train later.
        split_path = ROOT / (Path(args.out).stem + ".split.json")
        split_path.write_text(json.dumps(
            {k: sorted(names[i] for i in idx) for k, idx in
             (("train", tri), ("val", vi), ("test", ti))}, indent=1))
        print(f"split recorded → {split_path}", flush=True)
    print(f"real: {len(xr_all)} total → {len(xtr)} train / {len(xv)} val / {len(xte)} test", flush=True)

    if args.data == "real":
        xs, ys = xtr, ytr
    else:
        print("loading synth…", flush=True)
        xsy, ysy = load(SYNTH, args.limit)
        print(f"synth: {len(xsy)}", flush=True)
        xs = np.concatenate([xsy, xtr]) if len(xtr) else xsy
        ys = np.concatenate([ysy, ytr]) if len(ytr) else ysy
    print(f"train set: {len(xs)}", flush=True)

    model = CaptchaCNN()
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)
    lossf = nn.CrossEntropyLoss()
    n = len(xs)
    best, best_ep = 0.0, 0
    for ep in range(1, args.epochs + 1):
        model.train()
        perm = np.random.permutation(n)
        t0 = time.time()
        tot = 0.0
        for i in range(0, n, args.bs):
            idx = perm[i:i + args.bs]
            batch = xs[idx]
            if args.augment:
                batch = np.stack([augment(im) for im in batch])
            x = torch.from_numpy(batch).float().div_(255).unsqueeze(1)
            y = torch.from_numpy(ys[idx])
            out = model(x)
            loss = sum(lossf(out[:, k], y[:, k]) for k in range(6))
            opt.zero_grad(); loss.backward(); opt.step()
            tot += loss.item()
        sched.step()
        tr_acc = exact_acc(model, xs[:4000], ys[:4000])
        val_acc = exact_acc(model, xv, yv)
        marker = ""
        if val_acc > best:
            best = val_acc
            best_ep = ep
            torch.save(model.state_dict(), ROOT / args.out)
            marker = "  ← saved"
        print(f"epoch {ep:2d}  loss {tot / (n / args.bs):.3f}  train {tr_acc:.1%}  val {val_acc:.1%}  ({time.time() - t0:.0f}s){marker}", flush=True)

    # score the selected model ONCE on the untouched test split
    model.load_state_dict(torch.load(ROOT / args.out))
    model.eval()
    print(f"\nbest val {best:.1%} (epoch {best_ep})  →  weights: {ROOT / args.out}")
    if len(xte):
        print(f"TEST exact (never seen, scored once): {exact_acc(model, xte, yte):.1%}  on {len(xte)} images")

    # export the best model to ONNX for onnxruntime inference (node side / anywhere)
    torch.onnx.export(model, torch.zeros(1, 1, IH, IW), ROOT / (Path(args.out).stem + ".onnx"),
                      input_names=["img"], output_names=["logits"], opset_version=17,
                      dynamic_axes={"img": {0: "batch"}}, external_data=False)  # single file, not .onnx + .onnx.data
    print("exported", ROOT / (Path(args.out).stem + ".onnx"))


if __name__ == "__main__":
    main()
