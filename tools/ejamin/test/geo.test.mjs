import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parseGeo, pageToLatLng } from '../lib/geo.mjs';

const buf = readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url));

test('parseGeo reads the ArcMap viewport registration', () => {
  const geo = parseGeo(buf);
  assert.ok(geo, 'Badarkha is a GeoPDF; a transform must be found');
  assert.equal(geo.matrix.length, 6);
  assert.equal(geo.crs, 'EPSG:4326');
});

test('a page point maps into Gujarat', () => {
  const geo = parseGeo(buf);
  const [lat, lng] = pageToLatLng(geo.matrix, [1685, 1192]); // page centre
  assert.ok(lat > 20 && lat < 25, `lat ${lat} should be inside Gujarat`);
  assert.ok(lng > 68 && lng < 75, `lng ${lng} should be inside Gujarat`);
});

test('pageToLatLng is a plain affine application', () => {
  assert.deepEqual(pageToLatLng([1, 0, 0, 1, 0, 0], [3, 4]), [4, 3]);
});
