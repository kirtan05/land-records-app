// Plane geometry on page-space polygons. Kept free of PDF and of Android concerns so both the
// pipeline and (ported) the app can rely on identical semantics.

export function bbox(poly) {
  let a = Infinity, b = Infinity, c = -Infinity, d = -Infinity;
  for (const [x, y] of poly) {
    if (x < a) a = x; if (y < b) b = y;
    if (x > c) c = x; if (y > d) d = y;
  }
  return [a, b, c, d];
}

/** Absolute shoelace area. */
export function area(poly) {
  let s = 0;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    s += poly[j][0] * poly[i][1] - poly[i][0] * poly[j][1];
  }
  return Math.abs(s) / 2;
}

/** Ray casting. Points exactly on an edge are treated as inside by the half-open test. */
export function contains(poly, [x, y]) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i], [xj, yj] = poly[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/**
 * Neighbours = parcels sharing a boundary, not merely parcels drawn near each other.
 * Two parcels are adjacent when at least two of their vertices coincide within [tol] points —
 * i.e. they share a whole edge. A single coincident vertex is a corner touch, which is NOT
 * an adjoining property, so the threshold is two.
 */
export function adjacency(polys, tol = 1.5) {
  const boxes = polys.map(bbox);
  const out = polys.map(() => []);
  const key = (p) => `${Math.round(p[0] / tol)}:${Math.round(p[1] / tol)}`;
  const sets = polys.map((p) => new Set(p.map(key)));

  for (let i = 0; i < polys.length; i++) {
    for (let j = i + 1; j < polys.length; j++) {
      const [ax0, ay0, ax1, ay1] = boxes[i], [bx0, by0, bx1, by1] = boxes[j];
      if (ax1 + tol < bx0 || bx1 + tol < ax0 || ay1 + tol < by0 || by1 + tol < ay0) continue;
      let shared = 0;
      for (const k of sets[i]) if (sets[j].has(k) && ++shared === 2) break;
      if (shared >= 2) { out[i].push(j); out[j].push(i); }
    }
  }
  return out;
}
