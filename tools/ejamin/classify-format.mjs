// Which village sheets are VECTOR (searchable) and which are RASTER (image only)?
//
// The state exported the same ArcMap source in batches with different settings. Vector exports kept
// their text + parcel paths + GeoPDF registration; rasterized exports flattened the map to one JPEG
// and destroyed all three. Same template, same Creator string, visually identical — so the only way
// to tell is to look inside the file.
//
// Size is the cheap tell: vector sheets measure 0.3-0.6 MB, rasterized ones 2-5 MB. Google Drive
// reports Content-Length on a 1-byte Range request, so a whole district can be classified without
// downloading a single full PDF. Sizes near the boundary are downloaded and confirmed properly,
// because a guess here would silently decide which villages get a searchable map.
import { readFileSync, writeFileSync } from 'node:fs';

const CONCURRENCY = Number(process.env.CLASSIFY_CONCURRENCY ?? 24);
// Below this a sheet is almost certainly vector, above it almost certainly raster. Anything inside
// the grey band gets a real download + parse rather than a size guess.
const VECTOR_MAX = 1_200_000;
const RASTER_MIN = 1_800_000;

async function contentLength(url) {
  const res = await fetch(url, { headers: { Range: 'bytes=0-0' } });
  const cr = res.headers.get('content-range'); // "bytes 0-0/376613"
  if (cr) return Number(cr.split('/')[1]);
  const cl = res.headers.get('content-length');
  return cl ? Number(cl) : null;
}

/** The authoritative check: a sheet is searchable only if it has BOTH a text layer and geo. */
async function inspect(url) {
  const buf = Buffer.from(await (await fetch(url)).arrayBuffer());
  const h = buf.toString('latin1');
  if (!h.startsWith('%PDF')) return { format: 'NOT_PDF', fonts: 0, geo: 0, bytes: buf.length };
  const fonts = (h.match(/\/Font/g) ?? []).length;
  const geo = (h.match(/\/GPTS/g) ?? []).length;
  return { format: fonts > 0 && geo > 0 ? 'VECTOR' : 'RASTER', fonts, geo, bytes: buf.length };
}

async function pool(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i).catch((e) => ({ error: String(e) }));
    }
  }));
  return out;
}

const districts = (process.env.CLASSIFY_DISTRICTS ?? 'Kheda,Anand')
  .split(',').map((s) => s.trim().toLowerCase());

const catalog = JSON.parse(readFileSync('tools/ejamin/out/catalog.json', 'utf8'));
const sheets = catalog.sheets.filter(
  (s) => s.type === 'VILLAGE_MAP' && districts.includes(String(s.districtName).toLowerCase()),
);
console.log(`classifying ${sheets.length} village sheets in ${districts.join(', ')}`);

let done = 0;
const rows = await pool(sheets, CONCURRENCY, async (s) => {
  const bytes = await contentLength(s.downloadUrl);
  let format, detail = '';
  if (bytes === null) {
    ({ format } = await inspect(s.downloadUrl));
    detail = 'no content-length; downloaded';
  } else if (bytes <= VECTOR_MAX || bytes >= RASTER_MIN) {
    format = bytes <= VECTOR_MAX ? 'VECTOR?' : 'RASTER?';
  } else {
    const r = await inspect(s.downloadUrl);
    format = r.format;
    detail = `grey band; fonts=${r.fonts} geo=${r.geo}`;
  }
  if (++done % 100 === 0) console.log(`  ${done}/${sheets.length}`);
  return { ...s, bytes, format, detail };
});

// Confirm the size heuristic against real files rather than trusting it: sample from each side.
const sample = (f) => rows.filter((r) => r.format === f).slice(0, 5);
for (const guess of ['VECTOR?', 'RASTER?']) {
  for (const r of sample(guess)) {
    const real = await inspect(r.downloadUrl);
    r.format = real.format;
    r.detail = `verified fonts=${real.fonts} geo=${real.geo}`;
    if (!guess.startsWith(real.format)) console.log(`  HEURISTIC MISS: ${r.villageName} ${r.bytes}B guessed ${guess} actually ${real.format}`);
  }
}

const tally = {};
for (const r of rows) tally[r.format] = (tally[r.format] ?? 0) + 1;
writeFileSync('tools/ejamin/out/format-report.json', JSON.stringify({ tally, rows }, null, 1));
console.log('format tally:', tally);
