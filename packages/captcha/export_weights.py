#!/usr/bin/env python3
"""Export anyror_cnn_real.pt to a flat float32 blob for the pure-Kotlin forward pass.

BatchNorm is FOLDED INTO the preceding conv, so the on-device pass is only
conv3x3 → ReLU → maxpool ×3, one more conv3x3 → ReLU, adaptive-avg-pool to 2×5,
then two linears. No BN, no epsilon, nothing to get subtly wrong in Kotlin.

    conv:   w' = w * gamma/sqrt(var+eps)
            b' = (b - mean) * gamma/sqrt(var+eps) + beta

Layout is little-endian float32, weights then bias, in this exact order:
    conv1 (32,1,3,3)   conv2 (64,32,3,3)   conv3 (128,64,3,3)   conv4 (128,128,3,3)
    fc    (384,1280)   head0..head5 (10,384)
A tiny header records the shapes so the Kotlin loader can assert them.

    packages/captcha/.venv/bin/python packages/captcha/export_weights.py
"""
import struct
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).parent))
from train_cnn import CaptchaCNN, ROOT

OUT = ROOT / "anyror_cnn_real.weights"


def fold(conv, bn):
    """conv+bn → an equivalent conv with bias."""
    w = conv.weight.detach().numpy().astype(np.float32)
    b = (conv.bias.detach().numpy() if conv.bias is not None
         else np.zeros(w.shape[0], np.float32)).astype(np.float32)
    g = bn.weight.detach().numpy().astype(np.float32)
    beta = bn.bias.detach().numpy().astype(np.float32)
    mean = bn.running_mean.detach().numpy().astype(np.float32)
    var = bn.running_var.detach().numpy().astype(np.float32)
    scale = g / np.sqrt(var + bn.eps)
    return w * scale[:, None, None, None], (b - mean) * scale + beta


def main():
    m = CaptchaCNN()
    m.load_state_dict(torch.load(ROOT / "anyror_cnn_real.pt"))
    m.eval()

    f = m.features
    layers = []
    # features = [conv,bn,relu,pool, conv,bn,relu,pool, conv,bn,relu,pool, conv,bn,relu, avgpool]
    for ci, bi in ((0, 1), (4, 5), (8, 9), (12, 13)):
        layers.append(fold(f[ci], f[bi]))

    fcw = m.fc[1].weight.detach().numpy().astype(np.float32)
    fcb = m.fc[1].bias.detach().numpy().astype(np.float32)
    heads = [(h.weight.detach().numpy().astype(np.float32),
              h.bias.detach().numpy().astype(np.float32)) for h in m.heads]

    buf = bytearray()
    buf += b"LRCNN\x01"                       # magic + version
    buf += struct.pack("<i", len(layers))     # conv count
    for w, b in layers:
        buf += struct.pack("<4i", *w.shape)   # out,in,kh,kw
    buf += struct.pack("<2i", *fcw.shape)     # 384,1280
    buf += struct.pack("<2i", len(heads), heads[0][0].shape[0])
    for w, b in layers:
        buf += w.tobytes(); buf += b.tobytes()
    buf += fcw.tobytes(); buf += fcb.tobytes()
    for w, b in heads:
        buf += w.tobytes(); buf += b.tobytes()

    OUT.write_bytes(buf)
    n = sum(w.size + b.size for w, b in layers) + fcw.size + fcb.size + sum(w.size + b.size for w, b in heads)
    print(f"{OUT}  {len(buf) / 1e6:.2f} MB  ({n:,} params)")

    # Prove the fold is exact: folded-conv output must equal the original module's.
    x = torch.rand(4, 1, 64, 160)
    with torch.no_grad():
        ref = m(x).numpy()
    got = forward_numpy(buf, x.numpy())
    err = np.abs(ref - got).max()
    print(f"fold check: max|logit diff| = {err:.3e}  {'OK' if err < 1e-3 else 'FAILED'}")
    return 0 if err < 1e-3 else 1


def forward_numpy(buf, x):
    """Reference implementation of the exact pass Kotlin must reproduce."""
    o = 6
    (nconv,) = struct.unpack_from("<i", buf, o); o += 4
    shapes = []
    for _ in range(nconv):
        shapes.append(struct.unpack_from("<4i", buf, o)); o += 16
    fcs = struct.unpack_from("<2i", buf, o); o += 8
    nheads, ncls = struct.unpack_from("<2i", buf, o); o += 8

    def take(n):
        nonlocal o
        a = np.frombuffer(buf, np.float32, n, o); o += n * 4
        return a

    convs = []
    for s in shapes:
        w = take(int(np.prod(s))).reshape(s); b = take(s[0])
        convs.append((w, b))
    fcw = take(fcs[0] * fcs[1]).reshape(fcs); fcb = take(fcs[0])
    heads = [(take(ncls * fcs[0]).reshape(ncls, fcs[0]), take(ncls)) for _ in range(nheads)]

    def conv3(x, w, b):
        n, cin, h, wd = x.shape
        p = np.pad(x, ((0, 0), (0, 0), (1, 1), (1, 1)))
        out = np.empty((n, w.shape[0], h, wd), np.float32)
        for co in range(w.shape[0]):
            acc = np.full((n, h, wd), b[co], np.float32)
            for ci in range(cin):
                for ky in range(3):
                    for kx in range(3):
                        acc += w[co, ci, ky, kx] * p[:, ci, ky:ky + h, kx:kx + wd]
            out[:, co] = acc
        return out

    relu = lambda a: np.maximum(a, 0)
    pool = lambda a: a.reshape(a.shape[0], a.shape[1], a.shape[2] // 2, 2, a.shape[3] // 2, 2).max(axis=(3, 5))

    h = pool(relu(conv3(x, *convs[0])))
    h = pool(relu(conv3(h, *convs[1])))
    h = pool(relu(conv3(h, *convs[2])))
    h = relu(conv3(h, *convs[3]))
    n, c, hh, ww = h.shape                       # adaptive avg pool → 2×5
    h = h.reshape(n, c, 2, hh // 2, 5, ww // 5).mean(axis=(3, 5))
    h = h.reshape(n, -1)
    h = relu(h @ fcw.T + fcb)
    return np.stack([h @ w.T + b for w, b in heads], axis=1)


if __name__ == "__main__":
    raise SystemExit(main())
