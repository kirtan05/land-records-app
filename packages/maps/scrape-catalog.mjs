// Catalogues every map sheet eJamin publishes: all map types, all districts.
// Cheap and politely serial; run it again whenever eJamin adds sheets.
//
// CRITICAL: each tab has its OWN district id space (Kheda is 14 on the TP tab's "cityData"
// select but 18 on the village/GDCR/DP tabs' "districtData"-style selects). Ids are therefore
// always read from that tab's own <select> and stored per type — never assumed shared.
import { writeFileSync, mkdirSync } from 'node:fs';
import { Session, extractTpMapData } from './lib/session.mjs';
import { driveUrls } from './lib/drive.mjs';

// Each type names the CSS class of its district <select>. NOTE: the brief's draft used
// "tpDistrictData" for TP maps, but that class does not exist in the live markup — the TP
// tab's district-equivalent select is class "cityData" (verified against
// packages/maps/test/fixtures/homepage.html). Corrected here rather than guessed.
const TYPES = [
  { type: 'VILLAGE_MAP', selectClass: 'districtData', hops: ['district', 'taluka'], leaf: 'village' },
  { type: 'TP_MAP', selectClass: 'cityData', hops: [], leaf: 'tpTitle', fromTpMapData: true },
  { type: 'GDCR', selectClass: 'districtGdcrData', hops: [], leaf: 'districtGdcr' },
  { type: 'DP', selectClass: 'districtDpData', hops: ['districtDp'], leaf: 'talukaDp' },
];

/** The <option>s of one tab's district <select>, minus the placeholder. */
export function districtOptions(html, selectClass) {
  const sel = new RegExp(`<select[^>]*class="[^"]*${selectClass}[^"]*"[\\s\\S]*?</select>`, 'i');
  const block = html.match(sel);
  if (!block) return [];
  return [...block[0].matchAll(/<option value="(\d+)">([^<]+)<\/option>/g)]
    .map((m) => ({ id: Number(m[1]), name: m[2].trim() }))
    .filter((d) => d.name && !/^select /i.test(d.name));
}

function sheet(type, d, t, v, link) {
  const urls = driveUrls(link);
  if (!urls) return null; // no link = no sheet; never fabricate one
  return {
    type,
    districtId: d?.id ?? null, districtName: d?.name ?? null,
    talukaId: t?.id ?? null, talukaName: t?.name ?? null,
    villageId: v?.id ?? null, villageName: v?.name ?? null,
    ...urls,
  };
}

async function main() {
  // Mutable session holder: Session never refreshes its cookie from Set-Cookie after open(),
  // and this crawl makes thousands of requests over a long period. If the cookie goes stale
  // partway through, `post()` below re-opens a fresh session and retries once rather than
  // silently skipping the village/taluka/district it was working on.
  let s = await Session.open();
  let reopens = 0;

  async function post(type, id) {
    try {
      return await s.post(type, id);
    } catch (err) {
      reopens++;
      console.error(`eJamin: ${type}/${id} failed (${err.message}); re-opening session (reopen #${reopens})`);
      s = await Session.open();
      return await s.post(type, id);
    }
  }

  const sheets = [];

  // TP maps come free: the homepage embeds every district's schemes.
  const tp = extractTpMapData(s.html);
  const tpDistricts = districtOptions(s.html, 'cityData');
  for (const [districtId, rows] of Object.entries(tp)) {
    const d = tpDistricts.find((x) => x.id === Number(districtId)) ?? { id: Number(districtId), name: null };
    for (const r of rows) {
      const row = sheet('TP_MAP', d, null, { id: r.id, name: r.tp_title }, r.link);
      if (row) sheets.push(row);
      const g = sheet('GDSR', d, null, { id: r.id, name: r.tp_title }, r.gdsr_link);
      if (g) sheets.push(g);
      const f = sheet('F_FORM', d, null, { id: r.id, name: r.tp_title }, r.f_form_link);
      if (f) sheets.push(f);
    }
  }

  // Village maps: district -> taluka -> village -> sheet row.
  //
  // This is the ONLY expensive walk: one request per village, ~18k villages statewide, and the
  // server answers in seconds rather than milliseconds — a full-state crawl measured out at ~14
  // hours. Every other type resolves at district or taluka level and stays statewide (cheap).
  // So village maps are scoped to the districts we actually index. Widen with:
  //   EJAMIN_VILLAGE_DISTRICTS='all' node packages/maps/scrape-catalog.mjs
  //   EJAMIN_VILLAGE_DISTRICTS='Kheda,Anand,Vadodara' node packages/maps/scrape-catalog.mjs
  const wanted = (process.env.EJAMIN_VILLAGE_DISTRICTS ?? 'all').trim();
  const villageDistricts = districtOptions(s.html, 'districtData').filter(
    (d) => wanted.toLowerCase() === 'all' ||
      wanted.split(',').some((w) => w.trim().toLowerCase() === d.name.toLowerCase()),
  );
  console.log(`VILLAGE_MAP scope: ${villageDistricts.map((d) => d.name).join(', ') || '(none)'}`);
  if (villageDistricts.length === 0) throw new Error(`no district matched EJAMIN_VILLAGE_DISTRICTS='${wanted}'`);

  for (const d of villageDistricts) {
    const talukas = (await post('district', d.id)) ?? [];
    // Fan out across the whole district at once: every village lookup is independent, and
    // Session.post's gate is what bounds the real request rate.
    const perTaluka = await Promise.all(talukas.map(async (t) => {
      const villages = (await post('taluka', t.id)) ?? [];
      const rows = await Promise.all(villages.map(async (v) => {
        const row = await post('village', v.id);
        return sheet('VILLAGE_MAP', d, t, v, row?.link);
      }));
      return rows.filter(Boolean);
    }));
    const found = perTaluka.flat();
    sheets.push(...found);
    console.log(`VILLAGE_MAP ${d.name}: ${talukas.length} talukas, ${found.length} sheets`);
  }

  // GDCR resolves at district level and returns an array of named links.
  for (const d of districtOptions(s.html, 'districtGdcrData')) {
    const rows = (await post('districtGdcr', d.id)) ?? [];
    for (const r of Array.isArray(rows) ? rows : [rows]) {
      const out = sheet('GDCR', d, null, { id: r.id, name: r.name }, r.link);
      if (out) sheets.push(out);
    }
  }

  // DP resolves at taluka level.
  for (const d of districtOptions(s.html, 'districtDpData')) {
    const talukas = (await post('districtDp', d.id)) ?? [];
    for (const t of talukas) {
      const rows = (await post('talukaDp', t.id)) ?? [];
      for (const r of Array.isArray(rows) ? rows : [rows]) {
        const out = sheet('DP', d, t, { id: r.id, name: r.name }, r.link);
        if (out) sheets.push(out);
      }
    }
  }

  mkdirSync('packages/maps/out', { recursive: true });
  const catalog = { generatedAt: new Date().toISOString().slice(0, 10), sheets };
  writeFileSync('packages/maps/out/catalog.json', JSON.stringify(catalog, null, 1));
  console.log(`catalog: ${sheets.length} sheets (${reopens} session re-opens)`);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();
