import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { districtOptions } from '../scrape-catalog.mjs';

const html = readFileSync(new URL('./fixtures/homepage.html', import.meta.url), 'utf8');

// NOTE: verified against the fixture — the TP tab's district <select> uses CSS class
// "cityData" (Kheda=14), while the village/GDCR/DP/F-Form tabs all share "districtData"-style
// selects on the standard alphabetical numbering (Kheda=18). That TP-vs-rest split is the real
// separate id space in this fixture (village and DP happen to share the same numbering here),
// so the guard below is written against cityData vs districtData.
test('TP tab and village-map tab have SEPARATE district id spaces', () => {
  const tp = districtOptions(html, 'cityData');
  const village = districtOptions(html, 'districtData');
  const kt = tp.find((d) => /^kheda$/i.test(d.name));
  const kv = village.find((d) => /^kheda$/i.test(d.name));
  assert.ok(kt && kv, 'Kheda must appear in both tabs');
  assert.notEqual(kt.id, kv.id, 'ids MUST NOT be shared across map types');
});

test('districtOptions skips the placeholder option', () => {
  assert.ok(districtOptions(html, 'districtData').every((d) => d.id && d.name !== 'Select District'));
});
