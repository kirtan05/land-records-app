// Minimal PDF reader — just enough for ArcMap-exported map sheets, with no dependencies.
// These files are simple: one page, Flate-compressed streams, no encryption, no object streams.
// We scan for streams rather than walking the xref, because that is robust to the slightly
// non-conformant output some ArcMap versions produce.
import { inflateSync } from 'node:zlib';

/** Every Flate stream we can inflate, as latin1 text, biggest first. */
export function inflateStreams(buf) {
  const out = [];
  const hay = buf.toString('latin1');
  const re = /stream\r?\n/g;
  let m;
  while ((m = re.exec(hay)) !== null) {
    const start = m.index + m[0].length;
    const end = hay.indexOf('endstream', start);
    if (end < 0) continue;
    try {
      out.push(inflateSync(buf.subarray(start, end)).toString('latin1'));
    } catch {
      // Not Flate (an embedded image, or a raw stream) — content streams always are.
    }
  }
  return out.sort((a, b) => b.length - a.length);
}

/** [width, height] in points from the first /MediaBox. */
export function pageSize(buf) {
  const m = buf.toString('latin1').match(/\/MediaBox\s*\[\s*([\d.-]+)\s+([\d.-]+)\s+([\d.-]+)\s+([\d.-]+)\s*\]/);
  if (!m) throw new Error('pdf: no /MediaBox');
  return [Math.abs(+m[3] - +m[1]), Math.abs(+m[4] - +m[2])];
}

/** Raw text of each region introduced by /<name>, up to a bounded window — enough for /Viewport. */
export function findDicts(buf, name) {
  const hay = buf.toString('latin1');
  const out = [];
  let i = 0;
  while ((i = hay.indexOf(`/${name}`, i)) >= 0) {
    out.push(hay.slice(i, i + 2000));
    i += name.length + 1;
  }
  return out;
}
