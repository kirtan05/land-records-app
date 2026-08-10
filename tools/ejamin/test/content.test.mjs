import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { inflateStreams } from '../lib/pdf.mjs';
import { parsePaths, parseTexts } from '../lib/content.mjs';

const page = inflateStreams(readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url)))[0];

test('parsePaths finds closed parcel polygons', () => {
  const polys = parsePaths(page);
  assert.ok(polys.length > 50, `expected many parcels, got ${polys.length}`);
  assert.ok(polys.every((p) => p.length >= 3), 'every polygon needs 3+ points');
});

test('parsePaths handles a hand-built subpath with CRLF and h close', () => {
  const polys = parsePaths('q\r\n0 0 m\r\n10 0 l\r\n10 10 l\r\nh\r\nW* n\r\n');
  assert.deepEqual(polys[0], [[0, 0], [10, 0], [10, 10]]);
});

test('parseTexts places survey numbers at real coordinates', () => {
  const texts = parseTexts(page);
  const hit = texts.find((t) => t.text.trim() === '221' || t.text.trim() === '74/P');
  assert.ok(hit, 'expected a known survey label');
  assert.ok(hit.x > 0 && hit.y > 0, 'label must carry a placed origin');
});

test('parseTexts decodes a simple Tm + Tj pair', () => {
  const out = parseTexts('BT\r\n1 0 0 1 100 200 Tm\r\n(42) Tj\r\nET\r\n');
  assert.deepEqual(out, [{ text: '42', x: 100, y: 200 }]);
});
