import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { inflateStreams, pageSize, findDicts } from '../lib/pdf.mjs';

const buf = readFileSync(new URL('./fixtures/badarkha.pdf', import.meta.url));

test('inflateStreams returns the page content, largest first', () => {
  const streams = inflateStreams(buf);
  assert.ok(streams.length >= 1);
  assert.ok(streams[0].length > 100000, 'biggest stream should be the A0 page content');
  assert.ok(streams[0].includes('Tj'), 'page content must contain text operators');
});

test('pageSize reads the A0 MediaBox', () => {
  const [w, h] = pageSize(buf);
  assert.ok(Math.abs(w - 3370.51) < 1, `width ${w}`);
  assert.ok(Math.abs(h - 2384.25) < 1, `height ${h}`);
});

test('findDicts locates the georeferencing viewports', () => {
  assert.ok(findDicts(buf, 'Viewport').length >= 1);
});
