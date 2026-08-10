// Squeezes the scraped catalogue into the asset the Android app ships.
//
// catalog.json is ~7.7 MB because every row repeats the district/taluka names and three full URLs.
// The app only needs, per village, a name and a Drive file id — both URLs are derivable from the
// id. Grouping by district -> taluka and dropping the derivable fields takes it to a few hundred KB,
// small enough to bundle so the browse list works offline from first launch.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const OUT = 'android/app/src/main/assets/maps/villages.json';

const catalog = JSON.parse(readFileSync('tools/ejamin/out/catalog.json', 'utf8'));
const villages = catalog.sheets.filter((s) => s.type === 'VILLAGE_MAP' && s.driveFileId);

// district -> taluka -> [[villageName, driveFileId], ...]
const tree = new Map();
for (const s of villages) {
  const d = s.districtName ?? '—';
  const t = s.talukaName ?? '—';
  if (!tree.has(d)) tree.set(d, new Map());
  const tals = tree.get(d);
  if (!tals.has(t)) tals.set(t, []);
  tals.get(t).push([s.villageName ?? '—', s.driveFileId]);
}

const districts = [...tree.entries()]
  .sort((a, b) => a[0].localeCompare(b[0]))
  .map(([n, tals]) => ({
    n,
    t: [...tals.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([tn, vs]) => ({ n: tn, v: vs.sort((a, b) => a[0].localeCompare(b[0])) })),
  }));

mkdirSync('android/app/src/main/assets/maps', { recursive: true });
const json = JSON.stringify({ generatedAt: catalog.generatedAt, districts });
writeFileSync(OUT, json);

const talukas = districts.reduce((n, d) => n + d.t.length, 0);
console.log(`${OUT}: ${districts.length} districts, ${talukas} talukas, ${villages.length} villages, ${(json.length / 1024).toFixed(0)} KB`);
for (const d of ['Kheda', 'Anand']) {
  const row = districts.find((x) => x.n === d);
  console.log(`  ${d}: ${row.t.length} talukas, ${row.t.reduce((n, t) => n + t.v.length, 0)} villages`);
}
