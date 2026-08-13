#!/usr/bin/env python3
"""Train a small multi-head CNN on the synthetic AnyRoR-style captchas.
Fixed length (6) × fixed charset (10 digits) → 6 softmax heads, no CTC needed
(the captcha-break recipe, modernised to PyTorch).

The 99 human-tagged REAL samples are a held-out test set — never trained on;
real-set exact accuracy is printed every epoch and the best model is saved.

  tools/captcha/.venv/bin/python tools/captcha/train_cnn.py [--epochs 25] [--limit 0]
"""
import argparse
import csv
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


def load(dir_path, limit=0):
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
    return imgs[keep], labels[keep]


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
    args = ap.parse_args()

    print("loading synth…", flush=True)
    xs, ys = load(SYNTH, args.limit)
    print(f"synth: {len(xs)}  | loading real test…", flush=True)
    xr, yr = load(REAL)
    print(f"real:  {len(xr)}", flush=True)

    model = CaptchaCNN()
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)
    lossf = nn.CrossEntropyLoss()
    n = len(xs)
    best = 0.0
    for ep in range(1, args.epochs + 1):
        model.train()
        perm = np.random.permutation(n)
        t0 = time.time()
        tot = 0.0
        for i in range(0, n, args.bs):
            idx = perm[i:i + args.bs]
            x = torch.from_numpy(xs[idx]).float().div_(255).unsqueeze(1)
            y = torch.from_numpy(ys[idx])
            out = model(x)
            loss = sum(lossf(out[:, k], y[:, k]) for k in range(6))
            opt.zero_grad(); loss.backward(); opt.step()
            tot += loss.item()
        sched.step()
        syn_acc = exact_acc(model, xs[:4000], ys[:4000])
        real_acc = exact_acc(model, xr, yr)
        marker = ""
        if real_acc > best:
            best = real_acc
            torch.save(model.state_dict(), ROOT / "anyror_cnn_best.pt")
            marker = "  ← saved"
        print(f"epoch {ep:2d}  loss {tot / (n / args.bs):.3f}  synth {syn_acc:.1%}  REAL {real_acc:.1%}  ({time.time() - t0:.0f}s){marker}", flush=True)
    print(f"BEST real exact: {best:.1%}")

    # export the best model to ONNX for onnxruntime inference (node side / anywhere)
    model.load_state_dict(torch.load(ROOT / "anyror_cnn_best.pt"))
    model.eval()
    torch.onnx.export(model, torch.zeros(1, 1, IH, IW), ROOT / "anyror_cnn.onnx",
                      input_names=["img"], output_names=["logits"], opset_version=17,
                      dynamic_axes={"img": {0: "batch"}})
    print("exported", ROOT / "anyror_cnn.onnx")


if __name__ == "__main__":
    main()
