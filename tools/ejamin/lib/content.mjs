// Tokenises an ArcMap page content stream into the only two things the app needs:
// parcel outlines (closed subpaths) and placed text runs (survey numbers, road names).
//
// This is deliberately NOT a general PDF interpreter. ArcMap emits axis-plain geometry:
// `m`/`l` for parcel boundaries, `h` to close, and text as `Tm`-positioned `Tj` runs.
// Curves (`c`, `v`, `y`) are flattened to their endpoint — parcel edges are straight in
// cadastral sheets, and the few curved decorations do not need sub-point fidelity.

const NUM = '-?\\d*\\.?\\d+';

/** Closed subpaths, in page space. */
export function parsePaths(stream) {
  const polys = [];
  let cur = null;
  const re = new RegExp(
    `(${NUM})\\s+(${NUM})\\s+(m|l)\\b` +
    `|(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+c\\b` +
    `|\\bh\\b`,
    'g',
  );
  let m;
  while ((m = re.exec(stream)) !== null) {
    if (m[3] === 'm') {
      if (cur && cur.length >= 3) polys.push(cur);
      cur = [[+m[1], +m[2]]];
    } else if (m[3] === 'l') {
      cur?.push([+m[1], +m[2]]);
    } else if (m[9] !== undefined) {
      cur?.push([+m[9], +m[10]]); // curve endpoint only
    } else {
      if (cur && cur.length >= 3) polys.push(cur);
      cur = null;
    }
  }
  if (cur && cur.length >= 3) polys.push(cur);
  return polys;
}

/** PDF string literal → text, honouring the escapes ArcMap actually emits. */
function decode(lit) {
  return lit.replace(/\\([nrtbf()\\])/g, (_, c) =>
    ({ n: '\n', r: '\r', t: '\t', b: '\b', f: '\f' }[c] ?? c));
}

/**
 * Text runs with the origin from the current text matrix.
 *
 * ArcMap renders each label glyph-by-glyph — one `Tj` per character, chained by small `Td`
 * advances, all inside a single `BT ... ET` text object. A label is that whole object, not
 * the individual `Tj`: the brief's original per-Tj version returned one single-character
 * "label" per glyph and could never find a multi-digit survey number in the real sheet, since
 * no `Tj` there ever carries more than one character. So we buffer text across `Tj`s and flush
 * on `BT`/`ET`, anchored at the object's first `Tm` (the position `Td` advances are relative to).
 */
export function parseTexts(stream) {
  const out = [];
  let x = 0, y = 0;
  let buf = '';
  let originX = null, originY = null;
  const re = new RegExp(
    `\\bBT\\b` +
    `|\\bET\\b` +
    `|(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+(${NUM})\\s+Tm\\b` +
    `|(${NUM})\\s+(${NUM})\\s+Td\\b` +
    `|\\(((?:[^()\\\\]|\\\\.)*)\\)\\s*Tj\\b`,
    'g',
  );
  const flush = () => {
    if (buf) out.push({ text: buf, x: originX ?? 0, y: originY ?? 0 });
    buf = '';
    originX = null;
    originY = null;
  };
  let m;
  while ((m = re.exec(stream)) !== null) {
    if (m[0] === 'BT') {
      flush();
    } else if (m[0] === 'ET') {
      flush();
    } else if (m[5] !== undefined) {
      x = +m[5]; y = +m[6];
      if (originX === null) { originX = x; originY = y; }
    } else if (m[7] !== undefined) {
      x += +m[7]; y += +m[8];
    } else if (m[9] !== undefined) {
      buf += decode(m[9]);
    }
  }
  flush();
  return out;
}
