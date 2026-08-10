// ArcMap writes an OGC "geospatial PDF" registration: each /Viewport carries a /Measure dict whose
// /GPTS are geographic points (lat, lng pairs) and whose /LPTS are the matching points in the
// viewport's own unit square. Four correspondences over-determine an affine, so we least-squares
// three of them — which is exact for the axis-aligned north-up sheets ArcMap produces.
import { findDicts } from './pdf.mjs';

const nums = (s) => [...s.matchAll(/-?\d+\.?\d*/g)].map(Number);

/**
 * The GPTS/LPTS this sheet needs are not inline in the /Viewport dict: ArcMap points at them
 * with an indirect reference, `/Measure 42 0 R`, and the actual `/Type /Measure` dictionary
 * (with /GPTS and /LPTS) lives at its own `42 0 obj ... endobj` elsewhere in the file. findDicts'
 * 2000-char window from each `/Viewport` occurrence never reaches that far, so we resolve the
 * reference by locating the numbered object directly.
 */
function resolveObject(hay, objNum) {
  const i = hay.indexOf(`${objNum} 0 obj`);
  if (i < 0) return null;
  const end = hay.indexOf('endobj', i);
  return end < 0 ? null : hay.slice(i, end);
}

/** Solve a 3x3 system by Gaussian elimination; returns null if singular. */
function solve3(A, b) {
  const M = A.map((row, i) => [...row, b[i]]);
  for (let c = 0; c < 3; c++) {
    let p = c;
    for (let r = c + 1; r < 3; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
    if (Math.abs(M[p][c]) < 1e-12) return null;
    [M[c], M[p]] = [M[p], M[c]];
    for (let r = 0; r < 3; r++) {
      if (r === c) continue;
      const f = M[r][c] / M[c][c];
      for (let k = c; k < 4; k++) M[r][k] -= f * M[c][k];
    }
  }
  return [M[0][3] / M[0][0], M[1][3] / M[1][1], M[2][3] / M[2][2]];
}

/**
 * The page→(lat,lng) affine as [a,b,c, d,e,f] where
 *   lat = a*x + b*y + c
 *   lng = d*x + e*y + f
 * Returns null when the sheet carries no usable registration — the caller must then demote the
 * village to LINK_ONLY rather than invent coordinates.
 */
export function parseGeo(buf) {
  const hay = buf.toString('latin1');
  for (const dict of findDicts(buf, 'Viewport')) {
    const bboxM = dict.match(/\/BBox\s*\[([^\]]+)\]/);
    const measureM = dict.match(/\/Measure\s+(\d+)\s+0\s+R/);
    if (!bboxM || !measureM) continue;

    const measureDict = resolveObject(hay, measureM[1]);
    if (!measureDict) continue;
    const gptsM = measureDict.match(/\/GPTS\s*\[([^\]]+)\]/);
    const lptsM = measureDict.match(/\/LPTS\s*\[([^\]]+)\]/);
    if (!gptsM || !lptsM) continue;

    const [bx0, by0, bx1, by1] = nums(bboxM[1]);
    const g = nums(gptsM[1]); // lat, lng, lat, lng, ... — confirmed against the fixture's real
    // coordinates: this sheet's /GPTS first slot runs ~22.8 (Gujarat's latitude band), not
    // ~72.4 (its longitude band), so it is already lat-then-lng — no swap needed.
    const l = nums(lptsM[1]); // u, v, u, v, ... in the viewport unit square
    const n = Math.min(g.length / 2, l.length / 2);
    if (n < 3) continue;

    // Unit-square point -> absolute page point.
    const pts = [];
    for (let i = 0; i < n; i++) {
      pts.push({
        x: Math.min(bx0, bx1) + l[i * 2] * Math.abs(bx1 - bx0),
        y: Math.min(by0, by1) + l[i * 2 + 1] * Math.abs(by1 - by0),
        lat: g[i * 2],
        lng: g[i * 2 + 1],
      });
    }
    const A = pts.slice(0, 3).map((p) => [p.x, p.y, 1]);
    const lat = solve3(A, pts.slice(0, 3).map((p) => p.lat));
    const lng = solve3(A, pts.slice(0, 3).map((p) => p.lng));
    if (!lat || !lng) continue;
    return { matrix: [...lat, ...lng], crs: 'EPSG:4326' };
  }
  return null;
}

export function pageToLatLng([a, b, c, d, e, f], [x, y]) {
  return [a * x + b * y + c, d * x + e * y + f];
}
